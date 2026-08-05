package com.navneet.executespec.integration;

/**
 * A durable plan-gate row set that can never satisfy the V348 decision contract.
 *
 * <p>Raised only for structural partial state — a missing gate row, a missing continuation outbox
 * row, a gate/continuation identity or digest mismatch, an invalid continuation payload, or a
 * missing immutable plan artifact. These states can be produced by interrupted rolling deploys and
 * are permanent: no retry of the same decision can succeed. {@link PlanGateDecisionService}
 * converts this exception into deterministic behavior instead of an opaque failure — APPROVE
 * refuses with the stable {@code PLAN_GATE_STATE_UNRECOVERABLE[code]} conflict, while REJECT and
 * pre-approval CANCEL fence the run to a terminal cancelled state so the customer can always stop a
 * broken run. It never carries or fabricates receipt material.
 */
class BrokenPlanGateStateException extends RuntimeException {

  /** Stable machine-readable partial-state classifications. */
  enum Code {
    GATE_ROW_MISSING,
    CONTINUATION_ROW_MISSING,
    GATE_CONTINUATION_IDENTITY_MISMATCH,
    CONTINUATION_PAYLOAD_INVALID,
    PLAN_ARTIFACT_MISSING
  }

  private final Code code;

  BrokenPlanGateStateException(Code code, String message) {
    super(message);
    this.code = code;
  }

  BrokenPlanGateStateException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  Code code() {
    return code;
  }
}
