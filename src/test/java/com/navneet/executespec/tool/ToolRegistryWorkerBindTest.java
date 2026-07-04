package com.navneet.executespec.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.PreviewVerifier;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Pins the worker-tool deny list. {@link ToolRegistry#bindForWorker} must:
 *
 * <ul>
 *   <li>Exclude every name in {@link ToolRegistry#WORKER_DENY_TOOLS} (currently {@code gitCommit}).
 *   <li>Otherwise return the same set as {@link ToolRegistry#bind}, in the same order.
 * </ul>
 *
 * <p>Diagnosed from run {@code a076c876} task T2 (2026-05-03): the worker burned 22 of its 60
 * iterations cycling through {@code git status} / {@code git add} / {@code gitCommit} after the
 * actual code change had already been applied. Removing {@code gitCommit} from the worker's
 * authorities makes that runaway structurally impossible. If a future change adds a new
 * commit-flavored tool, this test pins the default-deny posture so it stays out of the worker bind
 * unless deliberately allowed.
 *
 * <p>Pure unit test — uses fake {@link Tool} stubs so no Spring context is needed.
 */
class ToolRegistryWorkerBindTest {

  private static Tool fakeTool(String name) {
    return new Tool() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public String description() {
        return "stub-" + name;
      }

      @Override
      public String inputSchema() {
        return "{\"type\":\"object\",\"additionalProperties\":false}";
      }

      @Override
      public ToolResult execute(JsonNode args, ExecutionContext ctx) {
        return new ToolResult.Ok("ok");
      }
    };
  }

  private static ExecutionContext ctx() {
    return new ExecutionContext("run-1", "T1", java.nio.file.Path.of("/tmp/ws"));
  }

  @Test
  void bindForWorker_excludes_gitCommit() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(
                fakeTool("readFile"),
                fakeTool("writeFile"),
                fakeTool("editFile"),
                fakeTool("gitStatus"),
                fakeTool("gitDiff"),
                fakeTool("gitCommit"),
                fakeTool("completeTask")),
            new ObjectMapper(),
            null,
            null,
            null); // remoteToolDispatcher — not exercised here; the dispatch branch is null-guarded

    List<ToolCallback> workerTools = registry.bindForWorker(ctx());
    List<String> workerNames = workerTools.stream().map(t -> t.getToolDefinition().name()).toList();

    assertFalse(
        workerNames.contains("gitCommit"),
        "Worker bind must exclude gitCommit (see ToolRegistry.WORKER_DENY_TOOLS).");
    // Read-only git tools should remain available — they're useful for inspecting state.
    assertTrue(workerNames.contains("gitStatus"), "gitStatus should stay in worker bind");
    assertTrue(workerNames.contains("gitDiff"), "gitDiff should stay in worker bind");
    // Core editing tools must remain.
    assertTrue(workerNames.contains("readFile"));
    assertTrue(workerNames.contains("writeFile"));
    assertTrue(workerNames.contains("editFile"));
    assertTrue(workerNames.contains("completeTask"));
    assertEquals(6, workerTools.size(), "Expected 6 tools after deny-list filtering (was 7).");
  }

  @Test
  void bind_keeps_gitCommit_for_non_worker_callers() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("writeFile"), fakeTool("gitCommit"), fakeTool("completeTask")),
            new ObjectMapper(),
            null,
            null,
            null);

    List<ToolCallback> all = registry.bind(ctx());
    List<String> names = all.stream().map(t -> t.getToolDefinition().name()).toList();

    assertTrue(
        names.contains("gitCommit"),
        "Non-worker bind() must keep gitCommit — reviewer / orchestration paths still need it.");
    assertEquals(3, all.size());
  }

  @Test
  void deny_set_pins_gitCommit() {
    // Independent of any registry instance — guards against a future commit
    // accidentally emptying the deny set.
    assertNotNull(ToolRegistry.WORKER_DENY_TOOLS);
    assertTrue(
        ToolRegistry.WORKER_DENY_TOOLS.contains("gitCommit"),
        "WORKER_DENY_TOOLS must keep gitCommit. If you're intentionally re-allowing"
            + " gitCommit for the worker, update this test with the rationale.");
  }

  // W4.2 (P6) — a flag-gated tool (backgroundJob) must be ABSENT from the model's schema when OFF,
  // not bound-but-inert: a tool merely present in the schema can stall schema-sensitive providers
  // (DeepSeek, RL-2026-05-061). The instanceof gate in ToolRegistry.isToolBound enforces this.

  private static BackgroundJobTool backgroundJob(boolean enabled) {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(BackgroundJobTool.FLAG)).thenReturn(enabled);
    return new BackgroundJobTool(flags, mock(JobRegistry.class), new ShellSandboxPolicy());
  }

  private static WorkspaceRunTestsTool workspaceRunTests(boolean enabled) {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(WorkspaceRunTestsTool.FLAG_BUILD_TDD)).thenReturn(enabled);
    return new WorkspaceRunTestsTool(mock(WorkspaceTestRunner.class), flags, new ObjectMapper());
  }

  private static VerifyBootTool verifyBoot(boolean enabled) {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(VerifyBootTool.FLAG_VERIFY_BOOT)).thenReturn(enabled);
    return new VerifyBootTool(mock(PreviewVerifier.class), flags, new ObjectMapper());
  }

  @Test
  void bindForWorker_excludes_backgroundJob_when_flag_off() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), backgroundJob(false)), new ObjectMapper(), null, null, null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    assertFalse(
        names.contains("backgroundJob"),
        "backgroundJob must be ABSENT from the worker schema when its flag is OFF (DeepSeek"
            + " schema-safety), not merely inert.");
    assertTrue(names.contains("readFile"), "other tools stay bound");
  }

  @Test
  void bindForWorker_includes_backgroundJob_when_flag_on() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), backgroundJob(true)), new ObjectMapper(), null, null, null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    assertTrue(names.contains("backgroundJob"), "backgroundJob must be bound when its flag is ON");
  }

  @Test
  void bindForWorker_excludes_runWorkspaceTests_when_flag_off() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), workspaceRunTests(false)), new ObjectMapper(), null, null, null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    assertFalse(
        names.contains("runWorkspaceTests"),
        "runWorkspaceTests must be ABSENT from the worker schema when its flag is OFF.");
    assertTrue(names.contains("readFile"), "other tools stay bound");
  }

  @Test
  void bindForWorker_includes_runWorkspaceTests_when_flag_on() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), workspaceRunTests(true)), new ObjectMapper(), null, null, null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    assertTrue(
        names.contains("runWorkspaceTests"), "runWorkspaceTests must be bound when its flag is ON");
  }

  @Test
  void bindForWorker_excludes_verifyBoot_when_flag_off() {
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), verifyBoot(false)), new ObjectMapper(), null, null, null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    assertFalse(
        names.contains("verifyBoot"),
        "verifyBoot must be ABSENT from the worker schema when its flag is OFF.");
    assertTrue(names.contains("readFile"), "other tools stay bound");
  }

  @Test
  void bindForWorker_includes_verifyBoot_when_flag_on() {
    ToolRegistry registry =
        new ToolRegistry(List.of(fakeTool("readFile"), verifyBoot(true)), new ObjectMapper(), null, null, null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    assertTrue(names.contains("verifyBoot"), "verifyBoot must be bound when its flag is ON");
  }

  // ---- harness-resolution T1: lean worker toolset (flag worker.lean-toolset.enabled) ----

  @Test
  void leanToolsetFiltersCompatAliasesForServerWorkers() {
    var flags = org.mockito.Mockito.mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class);
    org.mockito.Mockito.when(flags.isEnabled("worker.lean-toolset.enabled")).thenReturn(true);
    ToolRegistry registry =
        new ToolRegistry(
            List.of(
                fakeTool("readFile"),
                fakeTool("read_file"),
                fakeTool("shell"),
                fakeTool("bash"),
                fakeTool("grep"),
                fakeTool("glob"),
                fakeTool("lsp"),
                fakeTool("completeTask")),
            new ObjectMapper(),
            null,
            flags,
            null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    org.assertj.core.api.Assertions.assertThat(names)
        .containsExactlyInAnyOrder("readFile", "shell", "glob", "completeTask");
  }

  @Test
  void leanToolsetOffKeepsFullBinding() {
    var flags = org.mockito.Mockito.mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class);
    org.mockito.Mockito.when(flags.isEnabled("worker.lean-toolset.enabled")).thenReturn(false);
    ToolRegistry registry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), fakeTool("read_file"), fakeTool("bash")),
            new ObjectMapper(),
            null,
            flags,
            null);
    List<String> names =
        registry.bindForWorker(ctx()).stream().map(t -> t.getToolDefinition().name()).toList();
    org.assertj.core.api.Assertions.assertThat(names)
        .containsExactlyInAnyOrder("readFile", "read_file", "bash");
  }

  // ---- harness-resolution task 10: capability-profile optional tools ----

  @Test
  void profileOptionalToolsBindFlagGatedToolForThatProviderOnly() {
    var registry = org.mockito.Mockito.mock(com.navneet.executespec.router.ModelRegistry.class);
    var profile = new com.navneet.executespec.router.ModelMatrixProperties.CapabilityProfile();
    profile.setOptionalTools(List.of("runWorkspaceTests"));
    org.mockito.Mockito.when(registry.capabilityProfileFor("anthropic")).thenReturn(profile);

    ToolRegistry toolRegistry =
        new ToolRegistry(
            List.of(fakeTool("readFile"), workspaceRunTests(false)),
            new ObjectMapper(),
            null,
            null,
            registry);

    List<String> withProfile =
        toolRegistry.bindForWorker(ctx(), "anthropic").stream()
            .map(t -> t.getToolDefinition().name())
            .toList();
    org.assertj.core.api.Assertions.assertThat(withProfile)
        .containsExactlyInAnyOrder("readFile", "runWorkspaceTests");

    List<String> withoutProfile =
        toolRegistry.bindForWorker(ctx(), "deepseek").stream()
            .map(t -> t.getToolDefinition().name())
            .toList();
    org.assertj.core.api.Assertions.assertThat(withoutProfile).containsExactly("readFile");

    List<String> nullProvider =
        toolRegistry.bindForWorker(ctx()).stream()
            .map(t -> t.getToolDefinition().name())
            .toList();
    org.assertj.core.api.Assertions.assertThat(nullProvider).containsExactly("readFile");
  }
}
