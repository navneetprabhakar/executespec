package com.navneet.executespec.agent;

/**
 * Spec-complexity classification driving planner behavior and task caps.
 *
 * <p><strong>Legacy V1 enum.</strong> The V2 pipeline uses {@link PlanStrategy} — 5 values
 * ({@code SINGLE_SHOT}, {@code SMALL}, {@code MEDIUM}, {@code LARGE}, {@code LEGACY}) emitted by
 * {@link SmartClassifierAgent}. This 3-value enum survives only on the {@code rollout/} canary
 * path and {@code PlannerRequest.classification} for back-compat. New code MUST use {@code
 * PlanStrategy}.
 *
 * <p>Audit 2026-05-12 review item L #65 — corrected doc drift between root CLAUDE.md ("5-value
 * PlanStrategy") and this enum.
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
