package com.navneet.executespec.harness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannedTask.Complexity;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the 2026-05-02 multi-repo bug: {@code SequentialExecutor.composeSystemPrompt}
 * was not receiving the workspace summary built by {@code RunOrchestrator.buildMultiRepoSummary}.
 * The planner saw the "MULTI-REPO PROJECT WORKSPACE / repos/&lt;name&gt;/" block and routed every
 * task's {@code touchesFiles} under the right subdir; the worker had no such context, so writes
 * landed at workspace root and the per-repo push step found no diff to ship — runs finished
 * COMPLETE with empty PRs.
 *
 * <p>The fix threaded {@code workspaceSummary} through {@link ExecutionRequest} and into {@link
 * SequentialExecutor#composeSystemPrompt}. This test pins the contract: when the executor receives
 * a multi-repo workspace summary, the worker's system prompt MUST include the block verbatim.
 * Without this assertion the wire could silently get unplugged again — exactly how it shipped
 * broken in the first place.
 *
 * <p>Tests {@code composeSystemPrompt} directly (package-private) rather than going through {@link
 * SequentialExecutor#execute(ExecutionRequest)} so we don't need to mock the full LLM pipeline +
 * workspace + Spring context — this is a prompt-shaping invariant, not an end-to-end test.
 */
class MultiRepoWorkerPromptTest {

  /**
   * Build a bare-minimum executor instance with all collaborators mocked. {@code
   * composeSystemPrompt} doesn't touch any of them — it only reads from its arguments and {@code
   * SYSTEM_PROMPT_BASE} — so passing nulls / mocks is safe and keeps the fixture small.
   */
  private SequentialExecutor newExecutor() {
    // 2026-05-04 — SequentialExecutor gained a ContextStore field on PR #71 for the resume path.
    // composeSystemPrompt doesn't touch ContextStore (resume only fires from execute(req,
    // ResumeRequest)), so a mock is safe.
    return new SequentialExecutor(
        mock(com.navneet.executespec.engine.WorkerAgent.class),
        mock(com.navneet.executespec.tool.BuildProjectTool.class),
        new com.fasterxml.jackson.databind.ObjectMapper(),
        mock(com.navneet.executespec.observability.Metrics.class),
        mock(com.navneet.executespec.sse.SseBroadcaster.class),
        mock(com.navneet.executespec.state.repository.AgentTaskRepository.class),
        mock(com.navneet.executespec.router.ModelResolver.class),
        mock(com.navneet.executespec.router.ModelMatrixProperties.class),
        mock(com.navneet.executespec.project.rag.RetrievalService.class),
        mock(com.navneet.executespec.smoketest.WorkspaceTestRunner.class),
        mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
        mock(com.navneet.executespec.context.ContextStore.class),
        mock(com.navneet.executespec.cost.RunCostTracker.class),
        mock(com.navneet.executespec.deployment.GcsTaskCheckpointService.class),
        mock(com.navneet.executespec.router.AvailableProviderResolver.class),
        null, null);
  }

  private PlannedTask task() {
    return PlannedTask.builder()
        .id("T1")
        .title("Add OAuth login")
        .description("Wire Google OAuth into the existing auth service.")
        .complexity(Complexity.MEDIUM)
        .touchesFiles(List.of("repos/api-server/src/auth.ts"))
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .build();
  }

  @Test
  void multiRepo_summary_appears_in_worker_system_prompt() {
    String workspaceSummary =
        "MULTI-REPO PROJECT WORKSPACE.\n\n"
            + "Each selected repo is cloned into its own subdirectory under `repos/`.\n"
            + "All file paths you create or edit MUST live inside the matching `repos/<name>/`\n"
            + "subdirectory — never at the workspace root. Only one PR per repo is opened,\n"
            + "based on the diff inside that subdirectory.\n\n"
            + "Repos available for this run:\n"
            + "- `repos/api-server/` → github.com/acme/api-server (default branch: main)\n"
            + "- `repos/web-app/` → github.com/acme/web-app (default branch: main)\n";

    String prompt = newExecutor().composeSystemPrompt(task(), null, null, null, workspaceSummary);

    assertTrue(
        prompt.contains("MULTI-REPO PROJECT WORKSPACE"),
        "Worker prompt must include the multi-repo header — without it the worker writes at"
            + " workspace root and per-repo push opens empty PRs.");
    assertTrue(
        prompt.contains("repos/api-server/"),
        "Worker prompt must include each per-repo subdir from the summary.");
    assertTrue(
        prompt.contains("repos/web-app/"),
        "Worker prompt must include every per-repo subdir, not just the first.");
  }

  @Test
  void single_repo_run_does_not_receive_multi_repo_block() {
    // RunOrchestrator passes null when buildMultiRepoSummary returns null (single-repo
    // / scratch workspace). The executor must not invent a header in that case.
    String prompt = newExecutor().composeSystemPrompt(task(), null, null, null, null);

    assertFalse(
        prompt.contains("MULTI-REPO PROJECT WORKSPACE"),
        "No multi-repo header for runs without a project-side multi-repo summary.");
  }

  @Test
  void local_hands_mode_adds_exact_file_scope_prompt_guard() {
    String prompt =
        newExecutor().composeSystemPrompt(task(), null, null, null, null, "deepseek", true);

    assertTrue(prompt.contains("LOCAL-HANDS MODE"));
    assertTrue(
        prompt.contains("Obey explicit file scope exactly"),
        "Local-hands must keep the worker from inventing files outside the user's requested edit.");
    assertTrue(
        prompt.contains("test requirement is satisfied by producing the requested files correctly"),
        "Content-only local edits should not sprout validation scaffolds.");
    assertTrue(
        prompt.contains("Default CLI/local-hands behavior is PROTOTYPE speed"),
        "CLI local-hands should not inherit production's create-tests-by-default behavior.");
    assertTrue(
        prompt.contains("tests only when the user asks for tests"),
        "Prototype local-hands must not create test files unless explicitly requested.");
    assertTrue(
        prompt.contains("review is")
            && prompt.contains("not part of the default CLI prototype path"),
        "CLI local-hands must not self-upgrade into review/report workflow.");
    assertTrue(
        prompt.contains("LOCAL-HANDS FINAL OVERRIDE"),
        "The local-hands scope rule must explicitly override the generic test mandate.");
    assertTrue(
        prompt.contains("creating any additional test, validator"),
        "Exact-scope local requests must not create extra validation files.");
  }

  @Test
  void unrelated_workspace_summary_is_not_treated_as_multi_repo() {
    // The codemap-fallback branch in RunOrchestrator emits a different summary
    // ("Existing repository — codebase map below..."). The executor's contains-check
    // must not false-trigger on that and dump a stale codemap into the worker prompt.
    String codemapSummary = "Existing repository — codebase map below.\n\n(... codemap ...)";

    String prompt = newExecutor().composeSystemPrompt(task(), null, null, null, codemapSummary);

    assertFalse(
        prompt.contains("MULTI-REPO PROJECT WORKSPACE"),
        "Codemap-only summaries must not surface as multi-repo — different consumer.");
    assertFalse(
        prompt.contains("Existing repository — codebase map below"),
        "Codemap content has its own injection path (planner-side); the worker prompt should"
            + " stay narrow on multi-repo only.");
  }

  @Test
  void hard_constraints_and_touches_files_still_render_alongside_multi_repo_block() {
    // The pre-fix worker prompt assembled hard constraints + touchesFiles via existing branches.
    // The new multi-repo block must not displace either.
    PlannedTask t =
        PlannedTask.builder()
            .id("T1")
            .title("Wire Stripe")
            .description("Add /checkout endpoint.")
            .complexity(Complexity.MEDIUM)
            .touchesFiles(List.of("repos/api-server/src/checkout.ts"))
            .dependsOn(List.of())
            .hardConstraints(List.of("Do NOT use the deprecated payment_intents.create flow."))
            .build();

    String workspaceSummary =
        "MULTI-REPO PROJECT WORKSPACE.\n\nRepos available for this run:\n"
            + "- `repos/api-server/` → github.com/acme/api-server (default branch: main)\n";

    String prompt = newExecutor().composeSystemPrompt(t, null, null, null, workspaceSummary);

    assertTrue(prompt.contains("MULTI-REPO PROJECT WORKSPACE"));
    assertTrue(
        prompt.contains("later bullet is the corrected one and wins"),
        "Coherence conflict patches append corrected constraints, so worker precedence must be"
            + " explicit.");
    assertTrue(
        prompt.contains("Do NOT use the deprecated payment_intents.create flow."),
        "Hard constraints must still appear in the prompt.");
    assertTrue(
        prompt.contains("repos/api-server/src/checkout.ts"),
        "touchesFiles hint must still appear.");
  }

  @Test
  void java_spring_boot_project_type_loads_spring_boot_scaffold() {
    String prompt = newExecutor().composeSystemPrompt(task(), null, null, "java-spring-boot", null);

    assertTrue(
        prompt.contains("SPRING_BOOT"),
        "Planner projectType=java-spring-boot must resolve to prompts/scaffolds/spring-boot.md.");
    assertTrue(
        prompt.contains("@SpringBootApplication"),
        "Spring Boot scaffold guidance must include the mandatory application entry point.");
  }

  @Test
  void scaffold_key_normalizes_java_spring_boot_alias() {
    assertTrue(
        "spring-boot".equals(SequentialExecutor.scaffoldKeyForProjectType("java-spring-boot")),
        "java-spring-boot is the planner-facing projectType; spring-boot is the scaffold file"
            + " key.");
  }

  @Test
  void node_fullstack_project_receives_web_stack_worker_fragments() {
    String prompt = newExecutor().composeSystemPrompt(task(), null, null, "fullstack", null);

    assertTrue(prompt.contains("API URL RULE"), "Full-stack workers need relative API URL rules.");
    assertTrue(
        prompt.contains("ROUTER NAMESPACE ISOLATION"),
        "Full-stack workers need router mount path guidance.");
    assertTrue(
        prompt.contains("MODULE SYSTEM (ESM vs CJS)"),
        "Full-stack workers need ESM/CJS guidance before writing JS imports.");
    assertTrue(
        prompt.contains("FULL-STACK DIRECTORY STRUCTURE"),
        "Compiled-backend + frontend workers need the directory split rule.");
  }

  @Test
  void unrelated_project_type_does_not_receive_web_stack_worker_fragments() {
    String prompt = newExecutor().composeSystemPrompt(task(), null, null, "rust", null);

    assertFalse(
        prompt.contains("API URL RULE"), "Rust backend tasks should not get frontend URL rules.");
    assertFalse(
        prompt.contains("ROUTER NAMESPACE ISOLATION"),
        "Rust backend tasks should not get Express router guidance.");
    assertFalse(
        prompt.contains("MODULE SYSTEM (ESM vs CJS)"),
        "Rust backend tasks should not get JS module-system guidance.");
  }

  @Test
  void project_type_fragment_router_is_explicit_and_excludes_cors_until_policy_is_resolved() {
    assertTrue(
        SequentialExecutor.fragmentKeysForProjectType("node-backend").contains("router-isolation"));
    assertFalse(
        SequentialExecutor.fragmentKeysForProjectType("node-backend").contains("cors-config"),
        "cors-config still permits a wildcard fallback; keep it out of automatic worker"
            + " injection.");
  }
}
