package com.navneet.executespec.sandbox;

import java.time.Instant;
import java.util.UUID;

/**
 * One durable {@code run_sandbox} row (V307 + V342 cleanup retry metadata).
 *
 * <p>Identity, immutable source binding, lifecycle state, isolation provenance, fenced ownership,
 * digests, policy, timestamps, failure class, durable cleanup intent/retry evidence and retention
 * state. Credentials are deliberately absent: they are re-resolved from {@link
 * #requestedByUserId()} at use time and never persisted on the sandbox.
 */
public record RunSandbox(
    UUID id,
    String runId,
    String projectId,
    UUID sessionId,
    String requestedByUserId,
    String orgId,
    String sourceRevision,
    String repositorySet,
    SandboxState state,
    SandboxIsolationProfile isolationProfile,
    int isolationProfileVersion,
    String runtimeOwner,
    long leaseToken,
    Instant leaseExpiresAt,
    long runtimeEpoch,
    Instant heartbeatAt,
    String runtimeHandle,
    String imageDigest,
    String configDigest,
    SandboxNetworkPolicy networkPolicy,
    SandboxSecretPolicy secretPolicy,
    Instant expiresAt,
    String failureClass,
    String failureDetail,
    SandboxState cleanupTarget,
    String cleanupFailureClass,
    String cleanupFailureDetail,
    int cleanupAttempts,
    String cleanupLastError,
    SandboxRetentionState retentionState,
    String artifactUri,
    String idempotencyKey,
    Instant createdAt,
    Instant startedAt,
    Instant readyAt,
    Instant releasedAt,
    Instant terminalAt) {

  /** The immutable admission facts required to create a sandbox. */
  public record CreateRequest(
      UUID id,
      String runId,
      String projectId,
      UUID sessionId,
      String requestedByUserId,
      String orgId,
      String sourceRevision,
      String repositorySet,
      SandboxIsolationProfile isolationProfile,
      String configDigest,
      SandboxNetworkPolicy networkPolicy,
      SandboxSecretPolicy secretPolicy,
      Instant expiresAt,
      String idempotencyKey) {}

  /**
   * Proof that this JVM currently owns a sandbox. Every fenced write must present it; a takeover
   * increments {@code fencingToken} and invalidates every outstanding lease.
   */
  public record Lease(UUID sandboxId, String owner, long fencingToken, Instant expiresAt) {}
}
