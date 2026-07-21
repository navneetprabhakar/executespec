package com.navneet.executespec.cost;

/**
 * How the tokens consumed before the executor are split between the PLANNER phase column and the
 * pre-planner work that precedes it (RR-1561).
 *
 * <p>{@code RunOrchestrator} used to hand {@link RunTokenAttributor#attributePhase} the WHOLE-RUN
 * snapshot for the planner phase. The smart classifier runs on every run, before the outline
 * planner, so its usage — plus the per-run codemap and feature assessment when they run — was
 * always booked as planner spend. Every other phase already uses a delta; the planner now does too.
 *
 * @param plannerIn input tokens attributable to the outline planner itself
 * @param plannerOut output tokens attributable to the outline planner itself
 * @param prePlannerIn input tokens spent before the planner (codemap, assessment, classifier)
 * @param prePlannerOut output tokens spent before the planner
 */
public record PlannerPhaseSplit(
    long plannerIn, long plannerOut, long prePlannerIn, long prePlannerOut) {

  /**
   * Split the pre-executor spend into the planner phase and the pre-planner remainder.
   *
   * <p>{@code afterClassifier} is the snapshot taken the moment the smart classifier returns, which
   * makes the planner figure a true delta.
   *
   * <p>The remainder is returned rather than discarded because {@code
   * RunTokenAttributor.attributePhase} also increments the run TOTAL by whatever it is handed;
   * narrowing the planner argument alone would silently shrink {@code total_*_tokens}. The caller
   * adds the remainder back, so {@code plannerIn + prePlannerIn} still equals the whole-run input
   * count.
   *
   * <p>A null {@code afterClassifier} (the planner path taken without the classifier ever
   * completing) degrades to the legacy whole-snapshot behaviour rather than losing the tokens. A
   * baseline above the later snapshot — possible if the tracker is evicted between the two reads —
   * is clamped so neither figure can go negative.
   */
  public static PlannerPhaseSplit of(
      RunCostTracker.Snapshot afterPlan, RunCostTracker.Snapshot afterClassifier) {
    long afterPlanIn = afterPlan == null ? 0L : afterPlan.inputTokens();
    long afterPlanOut = afterPlan == null ? 0L : afterPlan.outputTokens();
    long preIn = afterClassifier == null ? 0L : afterClassifier.inputTokens();
    long preOut = afterClassifier == null ? 0L : afterClassifier.outputTokens();
    preIn = Math.max(0L, Math.min(afterPlanIn, preIn));
    preOut = Math.max(0L, Math.min(afterPlanOut, preOut));
    return new PlannerPhaseSplit(afterPlanIn - preIn, afterPlanOut - preOut, preIn, preOut);
  }
}
