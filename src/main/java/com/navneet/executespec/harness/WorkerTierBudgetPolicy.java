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
    // Wall-clock sized for slow models (2026-08-08): each iteration is ONE model turn, and a very
    // slow reasoning model can take ~10 min per turn. The prior 30/45/75-min caps allowed only
    // ~3-7 slow turns and terminated productive work as WALL_CLOCK_EXCEEDED. These give roughly
    // LOW ~9 / MEDIUM ~15 / HIGH ~24 productive 10-min turns (project runs ×2). This only binds for
    // slow models — a fast model hits the iteration cap (60/120/180) first — and cost is separately
    // bounded per iteration by the run's hardCapUsd dollar cap, so a raised wall-clock cannot
    // overspend. Anti-runaway is the iteration-counted stagnation/churn/mutation watchdogs, not
    // this
    // coarse backstop.
    return switch (complexity) {
      case LOW -> new Budget(lowComplexityStartTier, 60, 4096, 10, 16, 90 * 60);
      case MEDIUM -> new Budget(AgentTier.WORKHORSE, 120, 8192, 16, 28, 150 * 60);
      case HIGH -> new Budget(AgentTier.PLANNING, 180, 16384, 24, 40, 240 * 60);
    };
  }
}
