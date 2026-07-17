package com.navneet.executespec.executioncontext;

/** Fail-closed rejection raised before any provider transport is invoked. */
public class LlmDispatchPolicyException extends RuntimeException {

  public enum Reason {
    CONTEXT_REQUIRED,
    CONTEXT_NOT_FOUND,
    CONTEXT_NOT_DISPATCHABLE,
    TENANT_MISMATCH,
    POLICY_STALE,
    PROVIDER_NOT_ALLOWED,
    MODEL_NOT_ALLOWED,
    EXACT_ROUTE_VIOLATION,
    CREDENTIAL_UNAVAILABLE,
    CREDENTIAL_SOURCE_CHANGED,
    BUDGET_EXHAUSTED
  }

  private final Reason reason;

  public LlmDispatchPolicyException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
