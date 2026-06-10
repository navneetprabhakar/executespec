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

  private static String prompt(String classpath) throws IOException {
    return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
  }
}
