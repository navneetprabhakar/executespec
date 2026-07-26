package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.navneet.executespec.harness.SequentialExecutor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PromptConformanceTest {

  @Test
  void criticalRuntimePromptsLoad() throws IOException {
    for (String path :
        List.of(
            "prompts/classifier.md",
            "prompts/outline-planner.md",
            "prompts/outline-planner-skeleton.md",
            "prompts/reviewer-general.md",
            "prompts/reviewer-wiring.md",
            "prompts/patch-agent.md")) {
      assertThat(prompt(path)).as(path).isNotBlank();
    }
  }

  @Test
  void outlinePlannerPromptUsesCurrentClassificationTaxonomy() throws IOException {
    String full = prompt("prompts/outline-planner.md");

    assertThat(full).doesNotContain("MULTI_TASK");
    assertThat(full).contains("`SINGLE_SHOT`", "`SMALL`", "`MEDIUM`", "`LARGE`", "`LEGACY`");
  }

  @Test
  void plannerPromptAndToolSchemaAdvertiseCurrentProjectTypes() throws IOException {
    String full = prompt("prompts/outline-planner.md");
    String skeleton = prompt("prompts/outline-planner-skeleton.md");

    for (String projectType :
        List.of(
            "java-spring-boot",
            "node-backend",
            "node-frontend",
            "python",
            "go",
            "rust",
            "cli",
            "react-native-expo",
            "unknown")) {
      assertThat(full).as("full prompt projectType " + projectType).contains(projectType);
      assertThat(skeleton).as("skeleton prompt projectType " + projectType).contains(projectType);
      assertThat(OutlinePlannerAgent.SUBMIT_OUTLINE_SCHEMA)
          .as("schema projectType " + projectType)
          .contains(projectType);
    }
  }

  @Test
  void plannerPromptAndToolSchemaAdvertiseMobileRuntimeSurface() throws IOException {
    String full = prompt("prompts/outline-planner.md");
    String skeleton = prompt("prompts/outline-planner-skeleton.md");

    assertThat(full).contains("MOBILE_APP");
    assertThat(skeleton).contains("MOBILE_APP");
    assertThat(OutlinePlannerAgent.SUBMIT_OUTLINE_SCHEMA).contains("MOBILE_APP");
  }

  @Test
  void skeletonPromptCarriesEnterpriseContextAndNoPhantomReposField() throws IOException {
    String skeleton = prompt("prompts/outline-planner-skeleton.md");

    assertThat(skeleton)
        .contains("{{CONTRACTS}}")
        .contains("{{PROJECT_ADRS}}")
        .contains("{{PR_REVIEW_COMMENTS}}")
        .contains("touchesFiles");
    assertThat(skeleton)
        .doesNotContain("complexity, repos, dependsOn")
        .doesNotContain("v3 coverage check runs after expansion");
  }

  @Test
  void skeletonAllocatesRequirementOwnershipBeforeParallelExpansion() throws IOException {
    String skeleton = prompt("prompts/outline-planner-skeleton.md");

    assertThat(skeleton)
        .contains("\"meetsRequirements\": [\"R1\", \"R4\"]")
        .contains("Every requirement ID must be cited by at least one task")
        .contains("rejected before any task-expansion calls");
    assertThat(OutlinePlannerAgent.SUBMIT_OUTLINE_SCHEMA)
        .contains("REQUIRED in both modes so requirement ownership survives hierarchical fan-out");
  }

  @Test
  void reviewerGeneralTreatsMissingChangedCodeTestsAsInScope() throws IOException {
    String reviewer = prompt("prompts/reviewer-general.md");

    assertThat(reviewer).contains("Missing or invalid tests for changed production code");
    assertThat(reviewer)
        .contains(
            "Missing, tautological, or requirement-bypassing tests for changed production code are"
                + " in scope");
    assertThat(reviewer).doesNotContain("Missing tests (unless a spec explicitly asked for them)");
    assertThat(reviewer)
        .contains("test reimplements the target logic")
        .contains("fixture disables the security/validation/middleware boundary")
        .contains("Remove such bypasses and fix production");
  }

  @Test
  void runtimePromptsCarryInjectionGuards() throws IOException {
    assertThat(prompt("prompts/outline-planner.md")).contains("{{include:security-rules:planner}}");
    assertThat(prompt("prompts/outline-planner-skeleton.md"))
        .contains("{{include:security-rules:planner}}");
    assertThat(prompt("prompts/patch-agent.md")).contains("{{include:security-rules:worker}}");
    assertThat(prompt("prompts/tool-output-compactor.md"))
        .contains("{{include:security-rules:worker}}");
    assertThat(prompt("prompts/worker-history-compactor.md"))
        .contains("{{include:security-rules:worker}}");
  }

  @Test
  void plannerSecurityFragmentUsesPlannerToolContract() throws IOException {
    String plannerSecurity = prompt("prompts/fragments/security-rules-planner.md");

    assertThat(plannerSecurity)
        .contains("Your only action is the `submitOutline` tool call")
        .contains("Use the camelCase schema exactly")
        .contains("planTitle=\"Invalid request\"")
        .contains("projectType=\"unknown\"")
        .doesNotContain("project_type")
        .doesNotContain("plan_title")
        .doesNotContain("Run approved commands");
  }

  @Test
  void taskExpanderDoesNotAdvertiseIgnoredSmokeStrategy() throws IOException {
    String prompt = prompt("prompts/task-expander.md");

    assertThat(prompt).doesNotContain("smokeTestStrategy");
  }

  @Test
  void taskExpanderRequiresCompleteProducerSchemaBeforeConsumerExpansion() throws IOException {
    String prompt = prompt("prompts/task-expander.md");

    assertThat(prompt)
        .contains("every model it owns")
        .contains("complete field, relationship, constraint, and lifecycle contract")
        .contains("Do not abbreviate a schema inventory")
        .contains("downstream consumers can copy it verbatim");
  }

  @Test
  void plannerAndExpanderKeepFocusedTestsWithProductionWork() throws IOException {
    String full = prompt("prompts/outline-planner.md");
    String skeleton = prompt("prompts/outline-planner-skeleton.md");
    String expander = prompt("prompts/task-expander.md");

    assertThat(full)
        .contains("Tests travel with production code")
        .contains("in the SAME task")
        .contains("replay returns the original result")
        .contains("schema initialization")
        .contains("No invented deliverables")
        .contains("Bound task breadth");
    assertThat(skeleton)
        .contains("Tests travel with production code")
        .contains("first/only verification")
        .contains("forward migrations")
        .contains("No invented deliverables")
        .contains("Bound task breadth");
    assertThat(expander)
        .contains("focused maintained tests belong in this SAME task")
        .contains("one baseline DDL file proves initialization, not migration")
        .contains("do not create disposable root-level smoke scripts");
  }

  @Test
  void generationPromptsProtectCrossFeatureJourneysAndVisualEvidence() throws IOException {
    String full = prompt("prompts/outline-planner.md");
    String skeleton = prompt("prompts/outline-planner-skeleton.md");
    String expander = prompt("prompts/task-expander.md");
    String reviewer = prompt("prompts/reviewer-general.md");

    assertThat(full)
        .contains("Preserve cross-feature journeys")
        .contains("DOM structure alone does not prove responsive CSS");
    assertThat(skeleton)
        .contains("creating the first item without seeding demo data")
        .contains("DOM order alone is not proof");
    assertThat(expander)
        .contains("the first item can still be created without invoking optional demo data")
        .contains("actual CSS/computed/browser evidence");
    assertThat(reviewer)
        .contains("Cross-feature journey regressions and false visual tests")
        .contains("asserting that an empty board hides every create control");
  }

  @Test
  void generationPromptsChallengeConcurrentIdempotencyAndBoundedMemory() throws IOException {
    String reviewer = prompt("prompts/reviewer-general.md");
    String testPlanner = prompt("prompts/test-planner.txt");
    String testWorker = prompt("prompts/test-worker.txt");

    assertThat(SequentialExecutor.workerSystemPromptBase())
        .contains("one atomic critical section/transaction")
        .contains("atomic database claim for the scoped idempotency key")
        .contains("Never mutate")
        .contains("rely on a later unique-key insert")
        .contains("SELECT FOR UPDATE is ignored")
        .contains("first-decision")
        .contains("Raw delimiter concatenation")
        .contains("eviction of every live bucket/index/map")
        .contains("raw configurable header copied into context")
        .contains("black-box functional tests through the real HTTP/runtime boundary")
        .contains("unit tests are supplemental and never sufficient quality proof")
        .contains("test-only app factory, dependency override, or shared session")
        .contains("reject the same key with a different payload")
        .contains("assert every response plus final persisted state");
    assertThat(reviewer)
        .contains("force concurrent callers to observe the initial miss")
        .contains("atomic scoped-key database claim")
        .contains("hopeful unique insert is a critical race")
        .contains("reject the same key with a different payload")
        .contains("transaction races that surface intermittent 500s")
        .contains("Wildcard CORS combined with credentials")
        .contains("exact original status, body, and contract-relevant headers")
        .contains("must not ship predictable/sample bearer tokens")
        .contains("detached cleanup store is not proof")
        .contains("Cancellation must reach blocking core work");
    assertThat(testPlanner)
        .contains("exact replay of status, body, and contract-relevant headers")
        .contains("repeat the race enough to expose intermittent failures")
        .contains("truly parallel live-server clients/connections")
        .contains("predictable sample/default credential is not accepted");
    assertThat(testWorker)
        .contains("genuinely parallel requests to the live server")
        .contains("repeat the race to expose intermittent unique/lock/busy failures")
        .contains("compare the first and replayed status, body, and contract-relevant headers")
        .contains("intermittent 5xx responses fail the contract");
  }

  @Test
  void scaffoldPromptsDoNotMandateUnsafeWildcardCors() throws IOException {
    for (String scaffold : List.of("all.md", "python.md", "spring-boot.md", "fullstack.md")) {
      String content = prompt("prompts/scaffolds/" + scaffold);
      assertThat(content)
          .as(scaffold)
          .containsAnyOf("explicit origin allow-list", "explicit allowed origins")
          .doesNotContain("allowedOriginPatterns(\"*\")")
          .doesNotContain("CORS_ALLOW_ALL_ORIGINS=True")
          .doesNotContain("allow_origins=['*']");
    }
  }

  @Test
  void planningPromptsPreserveTrustInputAndMigrationSemantics() throws IOException {
    String plannerSecurity = prompt("prompts/fragments/security-rules-planner.md");
    String expander = prompt("prompts/task-expander.md");
    String coherence = prompt("prompts/coherence-planner.md");

    assertThat(plannerSecurity)
        .contains("Never translate it into a raw caller-selected identity header")
        .contains("zero/negative reservation")
        .contains("must run the migration mechanism");
    assertThat(expander)
        .contains("never invent a raw")
        .contains("caller-selected identity header/query/body field")
        .contains("zero/negative adversarial tests")
        .contains("migration runner rather than ORM");
    assertThat(coherence)
        .contains("weakens an authenticated")
        .contains("principal into a caller-selected identity")
        .contains("bypasses a required migration/runtime path")
        .contains("real integration proof with mocks");
  }

  @Test
  void patchPromptNamesRunTestsGate() throws IOException {
    String patch = prompt("prompts/patch-agent.md");

    assertThat(patch).contains("call it before `completeTask`");
    assertThat(patch).contains("PATCH pipeline may refuse completion until `runTests` has passed");
  }

  @Test
  void providerFragmentsAreSplitByRunMode() throws IOException {
    for (String provider :
        List.of("anthropic", "deepseek", "fireworks", "gemini", "openai", "openrouter")) {
      String build = prompt("prompts/fragments/provider/" + provider + "/build.md");
      String patch = prompt("prompts/fragments/provider/" + provider + "/patch.md");

      assertThat(build).as(provider + " build fragment").contains("PROVIDER-SPECIFIC BUILD NOTES");
      assertThat(patch).as(provider + " patch fragment").contains("PROVIDER-SPECIFIC PATCH NOTES");
      assertThat(build)
          .as(provider + " build fragment should not contain patch-only guidance")
          .doesNotContain("PROVIDER-SPECIFIC PATCH NOTES")
          .doesNotContain("patch SOURCE ONLY")
          .doesNotContain("scratch repro")
          .doesNotContain("SWE-bench");
      assertThat(build)
          .as(provider + " build verification-artifact parity")
          .contains("maintained conventional test suite")
          .contains("root-level scratch smoke file")
          .contains("`/tmp`");
    }
  }

  @Test
  void validationReportPromptDoesNotOverclaimResolutionOrLint() throws IOException {
    String prompt = prompt("prompts/validation-report.txt");

    assertThat(prompt)
        .contains("Only claim a check passed if it appears in \"Checks passed\"")
        .contains("Only say an issue was resolved when its line includes an explicit")
        .contains("Accuracy Assessment");
    assertThat(prompt)
        .doesNotContain("resolved by automated checks before merge")
        .doesNotContain("lint checks on first pass");
  }

  @Test
  void coherenceConstraintConflictPromptUsesReplacementOrFailClosedSemantics() throws IOException {
    String prompt = prompt("prompts/coherence-planner.md");

    assertThat(prompt)
        .contains("oldConstraint")
        .contains("fatalConflicts")
        .contains("the patch replaces it")
        .contains("Cross-task contract registry")
        .contains("Derive the contract from the current")
        .contains("REPLACE_DESCRIPTION_TEXT")
        .contains("Never import a route, schema, state")
        .contains("field vocabulary from an example or a previous application");
    assertThat(prompt).doesNotContain("later conflicting constraints as higher precedence");
  }

  @Test
  void planningReviewAndTestsPreserveExplicitCollectionCardinality() throws IOException {
    for (String path :
        List.of(
            "prompts/outline-planner.md",
            "prompts/task-expander.md",
            "prompts/coherence-planner.md",
            "prompts/reviewer-general.md",
            "prompts/test-planner.txt",
            "prompts/test-worker.txt")) {
      String prompt = prompt(path).toLowerCase();
      if (path.endsWith("coherence-planner.md")) {
        assertThat(prompt).as(path).contains("preserve requested cardinality");
      } else {
        assertThat(prompt).as(path).contains("multiple sku");
        assertThat(prompt).as(path).containsAnyOf("single", "one product");
      }
    }
  }

  @Test
  void generationPromptsRejectSplitIdempotencyAndSQLiteUuidDrift() throws IOException {
    for (String path :
        List.of(
            "prompts/outline-planner.md",
            "prompts/task-expander.md",
            "prompts/reviewer-general.md")) {
      String prompt = prompt(path);
      assertThat(prompt).as(path).contains("PROCESSING/COMPLETED");
      assertThat(prompt).as(path).containsAnyOf("func.uuid4()", "uuid_generate_v4()");
      assertThat(prompt).as(path).contains("\"code\"").contains("\"message\"");
    }
    assertThat(prompt("prompts/coherence-planner.md"))
        .contains("lifecycle, atomicity, and error semantics")
        .contains("serialization/encoding")
        .contains("Never import a route, schema, state")
        .contains("field vocabulary from an example or a previous application");
    assertThat(prompt("prompts/fragments/backend-robustness.md"))
        .contains("commits a claim")
        .contains("share the business transaction")
        .contains("SQLITE PORTABILITY")
        .contains("ERROR ENVELOPE")
        .contains("framework-native HTTP errors/exceptions")
        .contains("framework request-validation errors")
        .contains("catch-all handler is not sufficient");
    assertThat(prompt("prompts/reviewer-general.md"))
        .contains("framework's native HTTP-error/exception handler")
        .contains("request-validation handler")
        .contains("catch-all is not sufficient");
  }

  private static String prompt(String classpath) throws IOException {
    return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
  }
}
