package com.navneet.executespec.execution.model;

/**
 * Evidence-backed state of a task or run goal.
 *
 * <p>This is intentionally separate from worker loop outcomes. A worker can stop, hit a cap, or
 * call completeTask without proving the goal is satisfied.
 */
public enum GoalDonenessState {
  DONE,
  NOT_DONE_BUT_PROGRESSING,
  BLOCKED,
  FAILED,
  UNCERTAIN,
  WASTE_RISK
}
