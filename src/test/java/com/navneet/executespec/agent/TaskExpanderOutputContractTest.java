package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TaskExpanderOutputContractTest {

  @Test
  void schemaRequestsOnlyAdditionalArrayValues() {
    assertThat(TaskExpansionToolSchema.SUBMIT_TASK_DETAIL)
        .contains("\"additionalTouchesFiles\"", "\"additionalHardConstraints\"")
        .doesNotContain("\"touchesFiles\":", "\"hardConstraints\":", "\"meetsRequirements\":");
    assertThat(TaskExpansionToolSchema.SUBMIT_TASK_DETAIL)
        .doesNotContain("\"additionalMeetsRequirements\"");
  }

  @Test
  void evidencePublishesCapacityAfterNormalizedAuthoritativeValues() {
    PlannedTask skeleton =
        PlannedTask.builder()
            .id("T2")
            .title("Add endpoint")
            .touchesFiles(List.of("src/App.java", " src/App.java ", "src/Test.java"))
            .hardConstraints(List.of("keep auth", "keep auth"))
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan = Plan.builder().tasks(List.of(skeleton)).build();

    String evidence =
        TaskExpanderAgent.buildExpansionEvidence(
            plan,
            skeleton,
            PlannerRequest.builder()
                .spec("add an endpoint")
                .context(new ExecutionContext("run-1", "planner", Path.of("/tmp")))
                .provider("openai")
                .model("test-model")
                .workspaceSummary("existing repository")
                .build());

    assertThat(evidence)
        .contains("authoritativeTouchesFiles=src/App.java")
        .contains("additionalTouchesFilesCapacity=22")
        .contains("additionalHardConstraintsCapacity=11")
        .contains("authoritativeMeetsRequirements=R1")
        .doesNotContain("additionalMeetsRequirementsCapacity");
  }

  @Test
  void mergeAcceptsAdditionsUpToDeclaredCapacityAndCountsProviderOverflow() {
    List<String> authoritative = new ArrayList<>();
    for (int i = 0; i < 23; i++) authoritative.add("src/F" + i + ".java");

    TaskExpansionValueMerger.Result result =
        TaskExpansionValueMerger.merge(
            authoritative,
            List.of("src/F0.java", "src/New1.java", "src/New2.java", "src/New3.java"),
            TaskExpansionValueMerger.MAX_TOUCHES_FILES,
            TaskExpansionValueMerger.MAX_TOUCH_FILE_CHARS);

    assertThat(result.declaredRemainingCapacity()).isEqualTo(1);
    assertThat(result.values()).hasSize(24).endsWith("src/New1.java");
    assertThat(result.acceptedAdditions()).isEqualTo(1);
    assertThat(result.providerOverflow()).isEqualTo(3);
    assertThat(result.preMergeDrops()).isZero();
    assertThat(result.postMergeDrops()).isEqualTo(3);
  }

  @Test
  void mergeReportsNormalizationAndPostMergeDropsWithoutBreakingFinalEnvelope() {
    TaskExpansionValueMerger.Result result =
        TaskExpansionValueMerger.merge(
            List.of("R1"),
            List.of(" R1 ", "R2", "R2", "R" + "x".repeat(80)),
            3,
            TaskExpansionValueMerger.MAX_REQUIREMENT_ID_CHARS);

    assertThat(result.values()).containsExactly("R1", "R2", "R" + "x".repeat(39));
    assertThat(result.preMergeDrops()).isEqualTo(1);
    assertThat(result.postMergeDrops()).isEqualTo(1);
    assertThat(result.truncatedItems()).isEqualTo(1);
    assertThat(result.values()).allMatch(value -> value.length() <= 40);
  }

  @Test
  void providerRequirementIdsCannotBroadenAuthoritativeSkeletonOwnership() {
    TaskExpansionValueMerger.Result result =
        TaskExpansionValueMerger.authoritativeOnlyAndReport(
            "T8",
            "meetsRequirements",
            List.of("R8"),
            List.of("R1", "R2", "R3", "R8"),
            TaskExpansionValueMerger.MAX_MEETS_REQUIREMENTS,
            TaskExpansionValueMerger.MAX_REQUIREMENT_ID_CHARS);

    assertThat(result.values()).containsExactly("R8");
    assertThat(result.declaredRemainingCapacity()).isZero();
    assertThat(result.providerItems()).isEqualTo(4);
    assertThat(result.acceptedAdditions()).isZero();
    assertThat(result.providerOverflow()).isEqualTo(4);
    assertThat(result.postMergeDrops()).isEqualTo(4);
  }

  @Test
  void promptExplainsDeltaContractWithoutLegacyEchoInstruction() throws Exception {
    String prompt =
        new ClassPathResource("prompts/task-expander.md")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(prompt)
        .contains("Requirement ownership is closed")
        .contains("remaining capacity stated in the")
        .doesNotContain("repeating them wastes output tokens");
  }
}
