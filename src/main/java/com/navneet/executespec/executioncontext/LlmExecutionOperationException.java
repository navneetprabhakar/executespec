package com.navneet.executespec.executioncontext;

/** Typed fail-closed error for durable operation ownership and identity violations. */
public class LlmExecutionOperationException extends RuntimeException {

  public enum Reason {
    CONTEXT_NOT_FOUND,
    CONTEXT_NOT_ACTIVE,
    IDENTITY_CONFLICT,
    ACTIVE_INVOCATION_EXISTS,
    OPERATION_NOT_FOUND,
    LEASE_NOT_OWNED
  }

  private final Reason reason;

  LlmExecutionOperationException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
