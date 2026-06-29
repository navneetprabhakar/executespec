package com.navneet.executespec.execution.model;

/**
 * Policy-level decision after evaluating goal evidence, budget state, and provider/model options.
 *
 * <p>No runtime behavior is attached to this enum. Callers must still enforce the configured retry,
 * escalation, provider-switch, and waste-guard ceilings.
 */
public enum RunContinuationDecision {
  CONTINUE_SAME_MODEL_PROVIDER,
  STEP_UP_MODEL,
  STEP_DOWN_MODEL,
  CHANGE_PROVIDER,
  STOP_DONE,
  STOP_BLOCKED,
  STOP_FAILED,
  STOP_WASTE_GUARD
}
