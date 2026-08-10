package com.navneet.executespec.executioncontext;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Root-facing lifecycle facade for one durable logical LLM operation.
 *
 * <p>Provider transports never use this facade directly. A production root claims a lease, binds
 * its opaque authority into an {@link ExecutionContext}, persists its durable business output, and
 * only then completes the lease. Keeping completion explicit prevents a successful provider HTTP
 * response from being mistaken for a committed business outcome.
 */
@Service
@RequiredArgsConstructor
public class LlmExecutionOperationCoordinator {

  private final LlmExecutionOperationService operationService;

  public Optional<Lease> claimOrResume(
      LlmExecutionReference reference,
      LlmExecutionOperationDescriptor descriptor,
      String leaseOwner,
      Duration leaseDuration) {
    return operationService
        .claimOrResume(reference, descriptor, leaseOwner, leaseDuration)
        .map(claim -> Lease.issue(claim, leaseOwner));
  }

  public Optional<Lease> resumeOwned(Lease lease, Duration leaseDuration) {
    requireLease(lease);
    return operationService
        .resumeOwned(lease.claim, lease.leaseOwner, leaseDuration)
        .map(claim -> Lease.issue(claim, lease.leaseOwner));
  }

  public Lease beginNewInvocation(
      LlmExecutionReference reference,
      LlmExecutionOperationDescriptor descriptor,
      String leaseOwner,
      Duration leaseDuration) {
    return Lease.issue(
        operationService.beginNewInvocation(reference, descriptor, leaseOwner, leaseDuration),
        leaseOwner);
  }

  /** Binds the exact process-local authority without reconstructing it from durable identifiers. */
  public ExecutionContext bind(ExecutionContext context, Lease lease) {
    requireLease(lease);
    if (context == null) throw new IllegalArgumentException("context required");
    LlmExecutionReference reference =
        context.llmExecutionContextId() == null
            ? null
            : new LlmExecutionReference(
                context.llmExecutionContextId(), context.llmExecutionScope());
    if (!lease.claim.context().equals(reference)) {
      throw new IllegalArgumentException(
          "execution context belongs to a different durable operation reference");
    }
    return new ExecutionContext(
        context.runId(), context.taskId(), context.workspaceRoot(), reference, lease.authority);
  }

  public boolean heartbeat(Lease lease, Duration leaseDuration) {
    requireLease(lease);
    return operationService.heartbeat(lease.claim, lease.leaseOwner, leaseDuration);
  }

  /** Secret-bearing identity exposed only to the owning root for durable cross-row fencing. */
  public LeaseIdentity identity(Lease lease) {
    requireLease(lease);
    return new LeaseIdentity(
        lease.claim.operationId(), lease.claim.attempt(), lease.claim.leaseToken());
  }

  @JsonIgnoreType
  public record LeaseIdentity(String operationId, int attempt, UUID leaseToken) {}

  public boolean complete(Lease lease) {
    requireLease(lease);
    return operationService.complete(lease.claim, lease.leaseOwner);
  }

  public boolean fail(
      Lease lease, String code, String redactedMessage, boolean retryable, Duration retryDelay) {
    requireLease(lease);
    return operationService.fail(
        lease.claim,
        lease.leaseOwner,
        new LlmExecutionOperationService.Failure(code, redactedMessage, retryable, retryDelay));
  }

  public boolean acknowledgeCancellation(Lease lease) {
    requireLease(lease);
    return operationService.acknowledgeCancellation(lease.claim, lease.leaseOwner);
  }

  public Optional<LlmExecutionOperation.Status> requestCancellation(
      LlmExecutionReference reference, String operationKey) {
    return operationService.requestCancellation(reference, operationKey);
  }

  private static void requireLease(Lease lease) {
    if (lease == null) throw new IllegalArgumentException("lease required");
  }

  /**
   * Opaque root capability. It deliberately exposes neither the durable claim nor its owner/token.
   */
  @JsonIgnoreType
  public static final class Lease {
    private final LlmExecutionOperationClaim claim;
    private final String leaseOwner;
    private final LlmExecutionOperationAuthority authority;

    private Lease(LlmExecutionOperationClaim claim, String leaseOwner) {
      this.claim = claim;
      this.leaseOwner = leaseOwner.trim();
      this.authority = LlmExecutionOperationAuthority.issue(claim, leaseOwner);
    }

    private static Lease issue(LlmExecutionOperationClaim claim, String leaseOwner) {
      if (claim == null) throw new IllegalArgumentException("claim required");
      if (leaseOwner == null || leaseOwner.isBlank()) {
        throw new IllegalArgumentException("leaseOwner required");
      }
      return new Lease(claim, leaseOwner);
    }

    @Override
    public String toString() {
      return "LlmExecutionOperationCoordinator.Lease[REDACTED]";
    }
  }
}
