package com.navneet.executespec.integration;

/** Typed pre-gate stop when bounded same-route repair cannot produce an executable plan. */
final class PlannerRecoveryPolicyBlockedException extends IllegalStateException {

  enum Code {
    EXACT_REPAIR_ROUTE_UNAVAILABLE,
    PHYSICAL_ROUTE_MISMATCH,
    SAME_ROUTE_REPAIR_FAILED,
    ROUTE_PINNED,
    CONFIRMATION_REQUIRED,
    NO_AVAILABLE_MATRIX_ROUTE,
    BENCHMARK_EVIDENCE_REQUIRED,
    NO_QUALITY_STEP_UP,
    ESCALATION_LIMIT_EXHAUSTED
  }

  private final Code code;

  PlannerRecoveryPolicyBlockedException(Code code, String message) {
    super(message);
    this.code = code;
  }

  PlannerRecoveryPolicyBlockedException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  Code code() {
    return code;
  }
}
