package com.navneet.executespec.executioncontext;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/**
 * Process-local proof that a named owner holds an exact durable operation claim.
 *
 * <p>The type deliberately exposes no bean properties, never prints either the owner or lease
 * token, and is ignored by Jackson. It must be passed directly to the atomic admission service;
 * callers must never persist, serialize, log, or reconstruct it from request data.
 */
@JsonIgnoreType
public final class LlmExecutionOperationAuthority {

  private final LlmExecutionOperationClaim claim;
  private final String leaseOwner;

  private LlmExecutionOperationAuthority(LlmExecutionOperationClaim claim, String leaseOwner) {
    if (claim == null) throw new IllegalArgumentException("claim required");
    if (leaseOwner == null || leaseOwner.isBlank()) {
      throw new IllegalArgumentException("leaseOwner required");
    }
    this.claim = claim;
    this.leaseOwner = leaseOwner.trim();
  }

  /**
   * Issues an in-process authority from the claim returned by {@link LlmExecutionOperationService}.
   * This performs no database validation; admission always reacquires both durable locks and
   * validates the complete capability.
   */
  public static LlmExecutionOperationAuthority issue(
      LlmExecutionOperationClaim claim, String leaseOwner) {
    return new LlmExecutionOperationAuthority(claim, leaseOwner);
  }

  LlmExecutionOperationClaim claim() {
    return claim;
  }

  String leaseOwner() {
    return leaseOwner;
  }

  @Override
  public String toString() {
    return "LlmExecutionOperationAuthority[REDACTED]";
  }
}
