package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
  void reviewerGeneralTreatsMissingChangedCodeTestsAsInScope() throws IOException {
    String reviewer = prompt("prompts/reviewer-general.md");

    assertThat(reviewer).contains("Missing required tests or verification evidence");
    assertThat(reviewer).contains("Missing tests for changed production code are in scope");
    assertThat(reviewer).doesNotContain("Missing tests (unless a spec explicitly asked for them)");
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
  void coherenceConstraintConflictPromptMatchesAppendSemantics() throws IOException {
    String prompt = prompt("prompts/coherence-planner.md");

    assertThat(prompt)
        .contains("appends the corrected constraint")
        .contains("later conflicting constraints as higher precedence");
    assertThat(prompt).doesNotContain("replaces the incompatible constraint");
  }

  private static String prompt(String classpath) throws IOException {
    return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
  }
}
