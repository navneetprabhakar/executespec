package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RR-1217 + RR-1459 — the two pure decisions that bind a run's approval to what was reviewed. */
class RunControllerGateBindingTest {

  // RR-1217 — executionMode is PRODUCTION/PROTOTYPE, NOT the REALTIME/BATCH scheduling axis.

  @Test
  void forkInheritingASchedulingAxisValueFallsBackToProduction() {
    // This is the exact bug: approvedExecutionMode's default "REALTIME" used to be persisted as the
    // run's executionMode, which never equals "PRODUCTION" and therefore skipped the plan gate.
    assertThat(RunController.normalizeForkExecutionMode("REALTIME")).isEqualTo("PRODUCTION");
    assertThat(RunController.normalizeForkExecutionMode("BATCH")).isEqualTo("PRODUCTION");
  }

  @Test
  void forkFallsBackToProductionForNullAndUnknownValues() {
    assertThat(RunController.normalizeForkExecutionMode(null)).isEqualTo("PRODUCTION");
    assertThat(RunController.normalizeForkExecutionMode("")).isEqualTo("PRODUCTION");
    assertThat(RunController.normalizeForkExecutionMode("nonsense")).isEqualTo("PRODUCTION");
  }

  @Test
  void forkPreservesTheTwoLegalGovernanceModes() {
    assertThat(RunController.normalizeForkExecutionMode("PRODUCTION")).isEqualTo("PRODUCTION");
    assertThat(RunController.normalizeForkExecutionMode(" prototype ")).isEqualTo("PROTOTYPE");
  }

  // RR-1459 — plan identity, which creditsConfirmed is not.

  @Test
  void planFingerprintDistinguishesTwoPlansWithIdenticalCost() {
    String planA = "{\"tasks\":[{\"id\":\"1\",\"title\":\"Add login\"}]}";
    String planB = "{\"tasks\":[{\"id\":\"1\",\"title\":\"Delete accounts\"}]}";
    assertThat(RunController.planFingerprintOf(planA))
        .isNotNull()
        .isNotEqualTo(RunController.planFingerprintOf(planB));
  }

  @Test
  void planFingerprintIsStableForTheSamePlan() {
    String plan = "{\"tasks\":[]}";
    assertThat(RunController.planFingerprintOf(plan))
        .isEqualTo(RunController.planFingerprintOf(plan));
  }

  @Test
  void planFingerprintIsNullWhenThereIsNoPlanToBindTo() {
    assertThat(RunController.planFingerprintOf(null)).isNull();
    assertThat(RunController.planFingerprintOf("  ")).isNull();
  }
}
