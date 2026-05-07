package com.navneet.executespec.agent;

/**
 * Spec-complexity classification driving planner behavior and task caps.
 *
 * <p>Phase 4 accepts classification as input (caller decides). Phase 5 adds auto-classification via
 * triage that inspects the spec + workspace.
 */
public enum Classification {
  /**
   * Trivial single-file request (e.g. "write fibonacci to fib.py"). No planner call — a single
   * synthetic task is generated directly from the spec.
   */
  SINGLE_SHOT(1),

  /** Moderate scope. Planner caps at ≤ 4 tasks. */
  MEDIUM(4),

  /** Open-ended multi-component spec. Planner has no upper bound. */
  MULTI_TASK(Integer.MAX_VALUE);

  private final int maxTasks;

  Classification(int maxTasks) {
    this.maxTasks = maxTasks;
  }

  /** Maximum number of tasks a plan with this classification may contain. */
  public int maxTasks() {
    return maxTasks;
  }
}
