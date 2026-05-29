package com.navneet.executespec.engine;

/**
 * Provider-agnostic terminal state of a code-gen run. Phase 2's wrap facade maps the worker's
 * {@code WorkerResult.Outcome} onto this: COMPLETED→COMPLETED, COMPLETED_BY_STAGNATION→STAGNATED,
 * ITERATION_LIMIT_EXCEEDED→ITER_LIMIT, BUDGET_EXCEEDED→BUDGET, WALL_CLOCK_EXCEEDED→WALL_CLOCK,
 * STOPPED_WITHOUT_COMPLETE→STOPPED, ERROR→ERROR.
 */
public enum EngineOutcome {
  /** The agent asserted completion (called completeTask). */
  COMPLETED,
  /** Force-terminated after no-progress; the change set is presumably already in the workspace. */
  STAGNATED,
  /** Iteration cap hit before completion. */
  ITER_LIMIT,
  /** Hit the hard $ cap mid-task. */
  BUDGET,
  /** Exceeded the per-task wall-clock budget. */
  WALL_CLOCK,
  /** Model returned with no tool calls and no completion. */
  STOPPED,
  /** Router or tool dispatch threw an unrecoverable error. */
  ERROR
}
