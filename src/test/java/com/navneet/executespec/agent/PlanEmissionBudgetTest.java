package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the plan-emission ceiling contract. The defect these guard against is dev run f86d1ad1: the
 * coherence pass and its protocol repair both ran at a fixed 4,096 output tokens, both returned
 * exactly 4,096 (truncated mid-tool-call), and the run failed with no plan on a model whose own
 * ceiling was 25,344.
 */
class PlanEmissionBudgetTest {

  @Test
  @DisplayName("a repair is never tighter than the attempt that already truncated")
  void repairIsNeverTighterThanFirstAttempt() {
    for (int modelMax : new int[] {0, 1_024, 4_096, 8_192, 16_000, 25_344, 200_000}) {
      assertThat(PlanEmissionBudget.repairAttempt(modelMax))
          .as("repair >= first attempt for modelMax=%d", modelMax)
          .isGreaterThanOrEqualTo(PlanEmissionBudget.firstAttempt(modelMax));
    }
  }

  @Test
  @DisplayName("never requests more than the model's configured ceiling")
  void neverExceedsModelCeiling() {
    assertThat(PlanEmissionBudget.firstAttempt(4_096)).isEqualTo(4_096);
    assertThat(PlanEmissionBudget.repairAttempt(4_096)).isEqualTo(4_096);
    assertThat(PlanEmissionBudget.firstAttempt(1_024)).isEqualTo(1_024);
  }

  @Test
  @DisplayName("a roomy model gets real headroom, and the repair gets the model's full ceiling")
  void roomyModelGetsHeadroomAndRepairGetsFullCeiling() {
    // glm-5.2-fw: the exact model from the failing run.
    int glm = 25_344;
    assertThat(PlanEmissionBudget.firstAttempt(glm))
        .isEqualTo(PlanEmissionBudget.PREFERRED_FIRST_ATTEMPT)
        .isGreaterThan(4_096);
    assertThat(PlanEmissionBudget.repairAttempt(glm)).isEqualTo(glm);
  }

  @Test
  @DisplayName("an unconfigured model falls back rather than emitting a zero/unbounded ceiling")
  void unconfiguredModelFallsBack() {
    assertThat(PlanEmissionBudget.firstAttempt(0))
        .isEqualTo(PlanEmissionBudget.FALLBACK_MODEL_CEILING);
    assertThat(PlanEmissionBudget.firstAttempt(-1))
        .isEqualTo(PlanEmissionBudget.FALLBACK_MODEL_CEILING);
  }
}
