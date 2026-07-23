package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-1561 — {@code planner_*_tokens} must be a DELTA, like every other phase column.
 *
 * <p>The orchestrator used to hand {@code attributePhase(PLANNER, …)} the whole-run snapshot. The
 * smart classifier runs on every run, before the outline planner, so its usage — plus the per-run
 * codemap and feature assessment when they run — was always booked as planner spend.
 *
 * <p>{@code attributePhase} also increments the run TOTAL by whatever it is handed, so the split
 * returns the pre-planner remainder instead of discarding it; the caller adds it back and {@code
 * total_*_tokens} stays exact.
 */
class PlannerPhaseSplitTest {

  private static RunCostTracker.Snapshot snapshot(long in, long out) {
    return new RunCostTracker.Snapshot(in, out, 0L, 0L, 0.0, 0L, 0L);
  }

  @Test
  void classifierSpendIsExcludedFromThePlannerColumn() {
    // 1 200 in / 300 out consumed by codemap + assessment + classifier, 5 000 / 900 total.
    PlannerPhaseSplit split = PlannerPhaseSplit.of(snapshot(5_000, 900), snapshot(1_200, 300));

    assertThat(split.plannerIn()).isEqualTo(3_800);
    assertThat(split.plannerOut()).isEqualTo(600);
    assertThat(split.prePlannerIn()).isEqualTo(1_200);
    assertThat(split.prePlannerOut()).isEqualTo(300);
  }

  @Test
  void theSplitAlwaysReconstructsTheWholeRunTotal() {
    PlannerPhaseSplit split = PlannerPhaseSplit.of(snapshot(5_000, 900), snapshot(1_200, 300));

    assertThat(split.plannerIn() + split.prePlannerIn()).isEqualTo(5_000);
    assertThat(split.plannerOut() + split.prePlannerOut()).isEqualTo(900);
  }

  @Test
  void aSynthesizedPlanWithNoPlannerCallReportsZeroPlannerSpend() {
    // SINGLE_SHOT: the classifier ran, the outline planner did not.
    PlannerPhaseSplit split = PlannerPhaseSplit.of(snapshot(1_200, 300), snapshot(1_200, 300));

    assertThat(split.plannerIn()).isZero();
    assertThat(split.plannerOut()).isZero();
    assertThat(split.prePlannerIn()).isEqualTo(1_200);
    assertThat(split.prePlannerOut()).isEqualTo(300);
  }

  @Test
  void aMissingBaselineDegradesToTheLegacyWholeSnapshotRatherThanLosingTokens() {
    PlannerPhaseSplit split = PlannerPhaseSplit.of(snapshot(5_000, 900), null);

    assertThat(split.plannerIn()).isEqualTo(5_000);
    assertThat(split.plannerOut()).isEqualTo(900);
    assertThat(split.prePlannerIn()).isZero();
    assertThat(split.prePlannerOut()).isZero();
  }

  @Test
  void aBaselineAboveTheLaterSnapshotCannotProduceNegativePlannerSpend() {
    // Defensive: a tracker eviction between the two reads must not corrupt either figure.
    PlannerPhaseSplit split = PlannerPhaseSplit.of(snapshot(400, 50), snapshot(1_200, 300));

    assertThat(split.plannerIn()).isZero();
    assertThat(split.plannerOut()).isZero();
    assertThat(split.prePlannerIn()).isEqualTo(400);
    assertThat(split.prePlannerOut()).isEqualTo(50);
    assertThat(split.plannerIn() + split.prePlannerIn()).isEqualTo(400);
  }
}
