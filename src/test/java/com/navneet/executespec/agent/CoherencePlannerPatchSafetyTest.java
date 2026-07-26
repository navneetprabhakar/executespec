package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.router.ModelResolver;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1192 / RR-1194 — coherence patch application must be atomic and provenance-bearing: a
 * MOVE_FILE with an absent source must not inject an undisclosed target, a from==to move is a
 * no-op, and a blank-reason patch must not mutate the plan.
 */
class CoherencePlannerPatchSafetyTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final CoherencePlannerAgent agent =
      new CoherencePlannerAgent(mock(LlmRouter.class), mapper, mock(ModelResolver.class));

  private Plan planWith(String... files) {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("t")
            .description("d")
            .touchesFiles(List.of(files))
            .build();
    return Plan.builder().tasks(List.of(task)).build();
  }

  private CoherencePlannerAgent.PatchResult apply(Plan plan, String argsJson) {
    return ReflectionTestUtils.invokeMethod(agent, "applyPatches", plan, argsJson);
  }

  @Test
  void onlyProtocolShapeFailuresAreRepairable() {
    Plan plan = planWith("src/A.java");
    assertThat(
            CoherencePlannerAgent.isProtocolRepairable(
                new CoherencePlannerAgent.PatchResult(
                    plan, 0, List.of(), false, "patchPlan arguments were invalid JSON")))
        .isTrue();
    assertThat(
            CoherencePlannerAgent.isProtocolRepairable(
                new CoherencePlannerAgent.PatchResult(
                    plan, 0, List.of(), false, "fatal contract conflicts: schema mismatch")))
        .isFalse();
  }

  @Test
  void absentSourceMoveInjectsNothing() {
    // RR-1192 — `to` must not be added when `from` was never present on the task.
    Plan plan = planWith("src/Real.java");
    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"patches\":[{\"kind\":\"MOVE_FILE_TO_REPO_SUBDIR\",\"taskId\":\"T1\","
                + "\"from\":\"ghost.java\",\"to\":\"evil/inject.java\",\"reason\":\"tidy\"}]}");
    assertThat(result.patchesApplied()).isZero();
    assertThat(result.patchedPlan().tasks().get(0).touchesFiles())
        .doesNotContain("evil/inject.java")
        .containsExactly("src/Real.java");
  }

  @Test
  void samePathMoveIsNoOp() {
    Plan plan = planWith("src/A.java");
    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"patches\":[{\"kind\":\"MOVE_FILE_TO_REPO_SUBDIR\",\"taskId\":\"T1\","
                + "\"from\":\"src/A.java\",\"to\":\"src/A.java\",\"reason\":\"noop\"}]}");
    assertThat(result.patchesApplied()).isZero();
  }

  @Test
  void validMoveIsAppliedAndLedgered() {
    Plan plan = planWith("old/A.java");
    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"patches\":[{\"kind\":\"MOVE_FILE_TO_REPO_SUBDIR\",\"taskId\":\"T1\","
                + "\"from\":\"old/A.java\",\"to\":\"new/A.java\",\"reason\":\"repo layout\"}]}");
    assertThat(result.patchesApplied()).isEqualTo(1);
    assertThat(result.patchedPlan().tasks().get(0).touchesFiles()).containsExactly("new/A.java");
  }

  @Test
  void blankReasonPatchDoesNotMutate() {
    // RR-1194 — a patch with no explanation must be rejected.
    Plan plan = planWith("old/A.java");
    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"patches\":[{\"kind\":\"MOVE_FILE_TO_REPO_SUBDIR\",\"taskId\":\"T1\","
                + "\"from\":\"old/A.java\",\"to\":\"new/A.java\",\"reason\":\"   \"}]}");
    assertThat(result.patchesApplied()).isZero();
    assertThat(result.patchedPlan().tasks().get(0).touchesFiles()).containsExactly("old/A.java");
  }

  @Test
  void contractEvidencePrioritizesLateWireFormatContradictions() {
    String description =
        java.util.stream.IntStream.range(0, 80)
                .mapToObj(i -> "Tenant request setup detail " + i + " with repository fixture")
                .collect(java.util.stream.Collectors.joining("\n"))
            + "\nHMAC signature helper returns Base64 encoded bytes"
            + "\nAPI key fixture uses BCrypt hashing";

    assertThat(CoherencePlannerAgent.extractContractEvidence(description))
        .contains("HMAC signature helper returns Base64 encoded bytes")
        .contains("API key fixture uses BCrypt hashing");
  }

  @Test
  void malformedOrInapplicablePatchOutputIsNotValidated() {
    Plan plan = planWith("src/A.java");

    CoherencePlannerAgent.PatchResult malformed = apply(plan, "not-json");
    CoherencePlannerAgent.PatchResult inapplicable =
        apply(
            plan,
            "{\"patches\":[{\"kind\":\"MOVE_FILE_TO_REPO_SUBDIR\",\"taskId\":\"T1\","
                + "\"from\":\"missing.java\",\"to\":\"src/B.java\",\"reason\":\"fix\"}]}");

    assertThat(malformed.validated()).isFalse();
    assertThat(malformed.validationFailure()).contains("invalid JSON");
    assertThat(inapplicable.validated()).isFalse();
    assertThat(inapplicable.validationFailure()).contains("no applicable patches");
  }

  @Test
  void fatalRequirementContractConflictRejectsPlanBeforeWorkerSpend() {
    CoherencePlannerAgent.PatchResult result =
        apply(
            planWith("src/A.java"),
            "{\"fatalConflicts\":[\"Producer collapses required multi-line order into one"
                + " product\"],\"patches\":[]}");

    assertThat(result.validated()).isFalse();
    assertThat(result.validationFailure()).contains("fatal contract conflicts");
  }

  @Test
  void hardConstraintFixReplacesExactOldBulletInsteadOfStackingContradictions() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("t")
            .description("d")
            .hardConstraints(List.of("Use ammo.js", "Keep port 8080"))
            .build();
    Plan plan = Plan.builder().tasks(List.of(task)).build();

    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"fatalConflicts\":[],\"patches\":[{\"kind\":\"FIX_HARD_CONSTRAINT_CONFLICT\","
                + "\"taskId\":\"T1\",\"oldConstraint\":\"Use ammo.js\","
                + "\"newConstraint\":\"Use cannon-es\",\"reason\":\"shared contract\"}]}");

    assertThat(result.validated()).isTrue();
    assertThat(result.patchedPlan().tasks().get(0).hardConstraints())
        .containsExactly("Keep port 8080", "Use cannon-es")
        .doesNotContain("Use ammo.js");
  }

  @Test
  void hardConstraintRemovalDeletesExactBulletWithoutLeavingWorkerMetaProse() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T7")
            .title("Frontend tests")
            .description("d")
            .hardConstraints(List.of("Apply backend migrations", "Use Vitest"))
            .build();
    Plan plan = Plan.builder().tasks(List.of(task)).build();

    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"fatalConflicts\":[],\"patches\":[{\"kind\":\"REMOVE_HARD_CONSTRAINT\","
                + "\"taskId\":\"T7\",\"oldConstraint\":\"Apply backend migrations\","
                + "\"reason\":\"backend-only\"}]}");

    assertThat(result.validated()).isTrue();
    assertThat(result.patchedPlan().tasks().getFirst().hardConstraints())
        .containsExactly("Use Vitest");
  }

  @Test
  void descriptionContractRepairReplacesOnlyExactConflictingFragment() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T2")
            .title("Activity client")
            .description("Keep loading states. Call GET /activity-history with page and page_size.")
            .build();
    Plan plan = Plan.builder().tasks(List.of(task)).build();

    CoherencePlannerAgent.PatchResult result =
        apply(
            plan,
            "{\"fatalConflicts\":[],\"patches\":[{\"kind\":\"REPLACE_DESCRIPTION_TEXT\","
                + "\"taskId\":\"T2\",\"oldText\":\"GET /activity-history with page and"
                + " page_size\",\"newText\":\"GET /activity with offset and limit\","
                + "\"reason\":\"match producer T1\"}]}");

    assertThat(result.validated()).isTrue();
    assertThat(result.patchedPlan().tasks().getFirst().description())
        .isEqualTo("Keep loading states. Call GET /activity with offset and limit.");
  }

  @Test
  void descriptionContractRepairFailsClosedWhenExactSourceIsAbsent() {
    CoherencePlannerAgent.PatchResult result =
        apply(
            planWith("src/A.java"),
            "{\"fatalConflicts\":[],\"patches\":[{\"kind\":\"REPLACE_DESCRIPTION_TEXT\","
                + "\"taskId\":\"T1\",\"oldText\":\"invented text\","
                + "\"newText\":\"replacement\",\"reason\":\"match producer\"}]}");

    assertThat(result.validated()).isFalse();
    assertThat(result.patchedPlan().tasks().getFirst().description()).isEqualTo("d");
  }
}
