package com.navneet.executespec.executioncontext;

import java.util.UUID;

/** Opaque fencing capability returned only to the owner of a claimed operation attempt. */
public record LlmExecutionOperationClaim(
    LlmExecutionReference context,
    String operationId,
    int invocation,
    int attempt,
    UUID leaseToken) {

  public LlmExecutionOperationClaim {
    if (context == null) throw new IllegalArgumentException("context required");
    if (operationId == null || operationId.isBlank()) {
      throw new IllegalArgumentException("operationId required");
    }
    operationId = operationId.trim();
    if (invocation <= 0) throw new IllegalArgumentException("invocation must be positive");
    if (attempt <= 0) throw new IllegalArgumentException("attempt must be positive");
    if (leaseToken == null) throw new IllegalArgumentException("leaseToken required");
  }
}
