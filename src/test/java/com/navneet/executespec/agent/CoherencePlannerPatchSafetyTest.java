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
}
