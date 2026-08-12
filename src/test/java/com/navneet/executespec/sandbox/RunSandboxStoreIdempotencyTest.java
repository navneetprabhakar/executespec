package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * RR-329 — a reused idempotency key must return the existing sandbox only when the requester's
 * identity and immutable source/isolation policy match exactly; every other reuse is a typed
 * conflict, so one request can never receive another tenant/run's runtime identity and a changed
 * source revision or isolation policy is never treated as an idempotent retry.
 */
class RunSandboxStoreIdempotencyTest {

  private static final UUID SESSION = UUID.randomUUID();

  @Test
  void anExactIdentityMatchIsAcceptedAsIdempotent() {
    RunSandbox.CreateRequest request = request("org-1", "run-1", "rev-1", "digest-1");
    RunSandbox existing = rowFor(request);

    assertThatCode(() -> RunSandboxStore.requireSameRequest(existing, request))
        .doesNotThrowAnyException();
  }

  @Test
  void aDifferentTenantIsAConflict() {
    RunSandbox.CreateRequest request = request("org-attacker", "run-1", "rev-1", "digest-1");
    RunSandbox existing = rowFor(request("org-owner", "run-1", "rev-1", "digest-1"));

    assertThatThrownBy(() -> RunSandboxStore.requireSameRequest(existing, request))
        .isInstanceOf(SandboxIdempotencyConflictException.class);
  }

  @Test
  void aChangedSourceRevisionIsAConflict() {
    RunSandbox.CreateRequest request = request("org-1", "run-1", "rev-2", "digest-1");
    RunSandbox existing = rowFor(request("org-1", "run-1", "rev-1", "digest-1"));

    assertThatThrownBy(() -> RunSandboxStore.requireSameRequest(existing, request))
        .isInstanceOf(SandboxIdempotencyConflictException.class);
  }

  @Test
  void aChangedConfigDigestIsAConflict() {
    RunSandbox.CreateRequest request = request("org-1", "run-1", "rev-1", "digest-2");
    RunSandbox existing = rowFor(request("org-1", "run-1", "rev-1", "digest-1"));

    assertThatThrownBy(() -> RunSandboxStore.requireSameRequest(existing, request))
        .isInstanceOf(SandboxIdempotencyConflictException.class);
  }

  private static RunSandbox.CreateRequest request(
      String orgId, String runId, String sourceRevision, String configDigest) {
    return new RunSandbox.CreateRequest(
        UUID.randomUUID(),
        runId,
        "project-1",
        SESSION,
        "user-1",
        orgId,
        sourceRevision,
        "[\"repo-1\"]",
        SandboxIsolationProfile.CONTAINER_STRICT_V1,
        configDigest,
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(600),
        "idem-key");
  }

  private static RunSandbox rowFor(RunSandbox.CreateRequest r) {
    return new RunSandbox(
        r.id(),
        r.runId(),
        r.projectId(),
        r.sessionId(),
        r.requestedByUserId(),
        r.orgId(),
        r.sourceRevision(),
        r.repositorySet(),
        r.purpose(),
        SandboxState.REQUESTED,
        r.isolationProfile(),
        r.isolationProfile().version(),
        null,
        0L,
        null,
        0L,
        null,
        null,
        null,
        r.configDigest(),
        r.networkPolicy(),
        r.secretPolicy(),
        r.expiresAt(),
        null,
        null,
        null,
        null,
        null,
        0,
        null,
        SandboxRetentionState.RETAINED,
        null,
        r.idempotencyKey(),
        Instant.now(),
        null,
        null,
        null,
        null);
  }
}
