package com.navneet.executespec.agent;

/**
 * Hierarchical-planner strategy. Produced by {@link SmartClassifierAgent} and persisted on {@code
 * pipeline_run.plan_strategy}. Controls which planner phases run downstream:
 *
 * <pre>
 *   SINGLE_SHOT — synthesised plan, no LLM
 *   SMALL       — phase 1 (full plan in one outline call)
 *   MEDIUM      — phase 1 + phase 3 (coherence)
 *   LARGE       — phase 1 + phase 2 (parallel expansion) + phase 3
 *   LEGACY      — phase 1 + phase 2 (recursive depth 2) + phase 3 (strict)
 * </pre>
 *
 * See {@code docs/hierarchical-planner-plan.md}.
 */
public enum PlanStrategy {
  SINGLE_SHOT(1),
  SMALL(6),
  MEDIUM(10),
  LARGE(25),
  LEGACY(60);

  private final int maxTasks;

  PlanStrategy(int maxTasks) {
    this.maxTasks = maxTasks;
  }

  public int maxTasks() {
    return maxTasks;
  }

  public static PlanStrategy fromStringSafe(String s) {
    if (s == null) return null;
    try {
      return PlanStrategy.valueOf(s.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
