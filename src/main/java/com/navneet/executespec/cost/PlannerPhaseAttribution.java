package com.navneet.executespec.cost;

import com.navneet.executespec.state.repository.PipelineRunRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Books the pre-executor spend of a run: the planner phase column gets the planner's own delta, and
 * the pre-planner remainder is rolled into the run total (RR-1561).
 *
 * <p>Extracted from {@code RunOrchestrator} so the two halves of the correction stay together — see
 * {@link PlannerPhaseSplit} for why the remainder must not simply be dropped.
 */
@Slf4j
public final class PlannerPhaseAttribution {

  private PlannerPhaseAttribution() {}

  /**
   * @param afterPlan whole-run snapshot taken once the plan exists
   * @param afterClassifier snapshot taken the moment the smart classifier returned, or null when
   *     the classifier never completed (then the legacy whole-snapshot behaviour applies)
   */
  public static void apply(
      PipelineRunRepository runRepo,
      RunTokenAttributor tokenAttributor,
      String runId,
      RunCostTracker.Snapshot afterPlan,
      RunCostTracker.Snapshot afterClassifier,
      String providerUpper,
      String model) {
    PlannerPhaseSplit split = PlannerPhaseSplit.of(afterPlan, afterClassifier);
    if (split.prePlannerIn() > 0 || split.prePlannerOut() > 0) {
      // attributePhase moves the run total by whatever it is handed, so the tokens spent before
      // the planner (codemap, assessment, classifier) are added here to keep the total exact.
      try {
        runRepo.addTokens(
            runId,
            RunCostTracker.saturatedInt(split.prePlannerIn()),
            RunCostTracker.saturatedInt(split.prePlannerOut()));
      } catch (Exception e) {
        log.warn("V2 run={} pre-planner token roll-up failed: {}", runId, e.getMessage());
      }
    }
    tokenAttributor.attributePhase(
        runId,
        RunTokenAttributor.Phase.PLANNER,
        split.plannerIn(),
        split.plannerOut(),
        providerUpper,
        model);
  }
}
