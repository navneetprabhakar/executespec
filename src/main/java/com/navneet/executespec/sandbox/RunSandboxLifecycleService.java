package com.navneet.executespec.sandbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Application-facing sandbox lifecycle: admission, cancellation, expiry and reconciliation.
 *
 * <p>The three flags are enforced at three different points, which is what makes them independent:
 *
 * <ul>
 *   <li>{@code sandbox.lifecycle.enabled} gates {@link #create} only. Turning it off stops new
 *       sandboxes; existing rows stay readable, cancellable and reconcilable, and none is deleted.
 *   <li>{@code sandbox.runtime.dispatch.enabled} is enforced inside the dispatcher and gates
 *       execution only.
 *   <li>{@code sandbox.runtime.container-strict-v1.enabled} selects which runtime executes.
 * </ul>
 *
 * <p>{@link #cancel}, {@link #reconcile} and {@link #expire} deliberately consult no flag at all.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunSandboxLifecycleService {

  public static final String FLAG_LIFECYCLE = "sandbox.lifecycle.enabled";

  private final RunSandboxStore store;
  private final RunSandboxRuntimeDispatcher dispatcher;
  private final FeatureFlagService featureFlags;
  private final SandboxProperties properties;

  /**
   * Admits a sandbox. The isolation profile is resolved from the currently selected runtime and
   * recorded on the row, so a later flag change cannot retroactively claim a stronger profile than
   * the one this sandbox actually ran under.
   */
  public RunSandbox create(SandboxAdmission admission, SandboxRuntimeSpec spec) {
    if (!featureFlags.isEnabled(FLAG_LIFECYCLE)) {
      throw new SandboxLifecycleDisabledException();
    }
    SandboxIsolationProfile profile = dispatcher.selectedProfile();
    return store.createOrGet(
        new RunSandbox.CreateRequest(
            UUID.randomUUID(),
            admission.runId(),
            admission.projectId(),
            admission.sessionId(),
            admission.requestedByUserId(),
            admission.orgId(),
            admission.sourceRevision(),
            admission.repositorySet(),
            profile,
            spec.configDigest(),
            spec.networkPolicy(),
            spec.secretPolicy(),
            Instant.now().plus(properties.getTimeToLive()),
            admission.idempotencyKey()));
  }

  public Optional<RunSandbox> get(UUID id) {
    return store.get(id);
  }

  public List<RunSandbox> listForOrg(String orgId, int limit) {
    return store.listForOrg(orgId, limit);
  }

  /**
   * Requests cancellation. Works with the runtime disabled: an unclaimed sandbox settles
   * immediately and a claimed one is marked for its owner, or swept by {@link #reconcile} if that
   * owner never returns.
   */
  public Optional<RunSandbox> cancel(UUID id) {
    return store.requestCancellation(id);
  }

  /**
   * Settles sandboxes whose owning JVM is gone. Safe to call on startup and on a schedule; each
   * orphan is claimed under a fresh fence, so two nodes sweeping concurrently produce exactly one
   * winner per sandbox.
   */
  public int reconcile(String owner) {
    List<RunSandbox> orphans = store.orphanCandidates(properties.getReconcileBatchSize());
    int settled = 0;
    for (RunSandbox orphan : orphans) {
      try {
        if (dispatcher.reconcileOrphan(orphan, owner, properties.getLeaseDuration()).isPresent()) {
          settled++;
        }
      } catch (RuntimeException failure) {
        // One unsettleable orphan must not stop the sweep, but it is never silently dropped.
        log.error("sandbox {} could not be reconciled", orphan.id(), failure);
      }
    }
    if (settled > 0) {
      log.info("reconciled {} orphaned sandboxes as {}", settled, owner);
    }
    return settled;
  }

  /**
   * Settles sandboxes past their TTL. A sandbox no runtime ever claimed is settled directly; one
   * with a live owner is left alone and will be caught by the next sweep once its lease lapses.
   */
  public int expire() {
    List<RunSandbox> candidates = store.expiryCandidates(properties.getReconcileBatchSize());
    int expired = 0;
    for (RunSandbox candidate : candidates) {
      try {
        if (dispatcher
            .reconcileExpired(
                candidate, "expiry:" + ProcessHandle.current().pid(), properties.getLeaseDuration())
            .isPresent()) {
          expired++;
        }
      } catch (RuntimeException failure) {
        log.error("expired sandbox {} could not be torn down", candidate.id(), failure);
      }
    }
    return expired;
  }

  /** Marks artifact retention. Never called as part of teardown: evidence outlives the runtime. */
  public boolean retain(UUID id, SandboxRetentionState retention, String artifactUri) {
    return store.markRetention(id, retention, artifactUri);
  }

  /** The immutable admission facts a caller supplies. */
  public record SandboxAdmission(
      String runId,
      String projectId,
      UUID sessionId,
      String requestedByUserId,
      String orgId,
      String sourceRevision,
      String repositorySet,
      String idempotencyKey) {}

  /** Thrown when sandbox creation is requested while the lifecycle flag is off. */
  public static class SandboxLifecycleDisabledException extends RuntimeException {
    public SandboxLifecycleDisabledException() {
      super(FLAG_LIFECYCLE + " is off; no new sandbox may be created");
    }
  }
}
