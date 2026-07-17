package com.navneet.executespec.executioncontext;

/** Expected lifecycle or idempotency failure for a durable LLM execution context. */
public class LlmExecutionContextException extends RuntimeException {

  public enum Reason {
    NOT_FOUND,
    IDEMPOTENCY_CONFLICT
  }

  private final Reason reason;

  private LlmExecutionContextException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public static LlmExecutionContextException notFound() {
    return new LlmExecutionContextException(Reason.NOT_FOUND, "LLM execution context not found");
  }

  public static LlmExecutionContextException idempotencyConflict() {
    return new LlmExecutionContextException(
        Reason.IDEMPOTENCY_CONFLICT,
        "Idempotency key already belongs to a different LLM execution request");
  }

  public Reason reason() {
    return reason;
  }
}
