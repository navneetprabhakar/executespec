package com.navneet.executespec.harness;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.router.AgentTier;

/** Pure complexity-to-worker-budget mapping used by the sequential executor. */
final class WorkerTierBudgetPolicy {

  private WorkerTierBudgetPolicy() {}

  record Budget(
      AgentTier tier,
      int maxIterations,
      int maxTokens,
      int stagnationWarnAfter,
      int stagnationForceAfter,
      int wallClockSeconds) {}

  static Budget forTask(
      PlannedTask.Complexity complexity, boolean projectRun, AgentTier lowComplexityStartTier) {
    PlannedTask.Complexity effectiveComplexity =
        complexity == null ? PlannedTask.Complexity.MEDIUM : complexity;
    Budget base = baseBudget(effectiveComplexity, lowComplexityStartTier);
    if (!projectRun) {
      return base;
    }

    int projectForceAfter =
        switch (effectiveComplexity) {
          case LOW -> 24;
          case MEDIUM -> 36;
          case HIGH -> base.stagnationForceAfter();
        };
    return new Budget(
        base.tier(),
        base.maxIterations() * 2,
        base.maxTokens(),
        base.stagnationWarnAfter(),
        projectForceAfter,
        base.wallClockSeconds() * 2);
  }

  private static Budget baseBudget(
      PlannedTask.Complexity complexity, AgentTier lowComplexityStartTier) {
    return switch (complexity) {
      case LOW -> new Budget(lowComplexityStartTier, 60, 4096, 10, 16, 30 * 60);
      case MEDIUM -> new Budget(AgentTier.WORKHORSE, 120, 8192, 16, 28, 45 * 60);
      case HIGH -> new Budget(AgentTier.PLANNING, 180, 16384, 24, 40, 75 * 60);
    };
  }
}
