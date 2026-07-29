package com.navneet.executespec.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Leased, restart-safe delivery of approved plan continuations.
 *
 * <p>The database outbox is the authority. Events are only latency hints; startup and scheduled
 * sweeps independently recover READY rows, expired PROCESSING leases, and stale COMPLETED handoffs
 * that still carry the explicit queued-owner marker. Runtime-started workers use normal checkpoint
 * recovery instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanGateResumeDispatcher {
  static final int SUPPORTED_HANDLER_CONTRACT_VERSION = 1;
  public static final String QUEUED_HANDOFF_OWNER_MARKER = ":plan-gate-queued:";
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
  private static final String OWNER = ExecutionHostIdentity.current() + ":plan-gate-resume";
  private static final String EXECUTION_OWNER = ExecutionHostIdentity.current();
  private static final String DISPATCH_OWNER = newQueuedHandoffOwner();

  private final PlanGateResumeStore store;
  private final ObjectMapper objectMapper;
  private final PlanGateWorkspaceRecovery workspaceRecovery;
  private final PipelineExecutorPool pool;
  private final RunControlPort controlPort;
  private final SseBroadcaster broadcaster;
  private final AtomicBoolean draining = new AtomicBoolean();
  private final ScheduledExecutorService leaseRenewalExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "plan-gate-lease-renewal");
            thread.setDaemon(true);
            return thread;
          });

  @Value("${execution.plan-gate-resume.lease-seconds:120}")
  private long leaseSeconds;

  @Value("${execution.plan-gate-resume.max-batch:16}")
  private int maxBatch;

  @Value("${execution.plan-gate-resume.max-workspace-restore-seconds:900}")
  private long maxWorkspaceRestoreSeconds;

  @Value("${execution.plan-gate-resume.execution-stale-seconds:120}")
  private long executionStaleSeconds;

  @Async
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onResumeReady(PlanGateResumeReadyCommitted ignored) {
    kick();
  }

  /** Startup outbox recovery is independent of the generic crash-recovery feature flag. */
  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    kick();
  }

  /** Scheduled lease recovery is independent of the generic crash-recovery feature flag. */
  @Scheduled(
      fixedDelayString = "${execution.plan-gate-resume.scan-interval-ms:15000}",
      initialDelayString = "${execution.plan-gate-resume.scan-initial-delay-ms:15000}")
  public void scheduledSweep() {
    kick();
  }

  /** Public so approval/recovery callers can request a low-latency sweep after their commit. */
  public void kick() {
    if (!draining.compareAndSet(false, true)) return;
    try {
      int limit = Math.max(1, maxBatch);
      Instant scanAt = Instant.now();
      Instant staleBefore = scanAt.minus(executionStaleDuration());
      var staleHandoffs = store.staleCompletedHandoffRunIds(scanAt, staleBefore, limit);
      if (staleHandoffs != null) {
        staleHandoffs.forEach(runId -> recoverStaleCompletedHandoff(runId, staleBefore, scanAt));
      }
      store.exhaustedClaimableRunIds(scanAt).stream()
          .limit(limit)
          .forEach(runId -> store.deadExhausted(runId, scanAt));
      for (int i = 0; i < limit; i++) {
        var lease = store.claimNext(OWNER, Instant.now(), leaseDuration()).orElse(null);
        if (lease == null) break;
        deliver(lease);
      }
    } finally {
      draining.set(false);
    }
  }

  private void deliver(PlanGateResumeStore.Lease lease) {
    try {
      PlanGateContinuation continuation = decodeAndValidate(lease);
      if (!store.heartbeat(lease, Instant.now(), leaseDuration())) {
        log.info("plan-gate resume={} lost lease before workspace restore", lease.runId());
        return;
      }
      // Fail closed before an expensive or externally visible workspace restore. The store locks
      // and validates the leased run/gate/outbox receipt here; handoff repeats the same fence after
      // restore because cancellation or corruption can still race that I/O boundary.
      store.preflight(lease, Instant.now());
      workspaceRecovery.restoreWithLease(lease, leaseDuration(), maxWorkspaceRestoreSeconds);
      if (!store.heartbeat(lease, Instant.now(), leaseDuration())) {
        log.info("plan-gate resume={} lost lease after workspace restore", lease.runId());
        return;
      }

      PlanGateResumeStore.Handoff handoff = store.handoff(lease, DISPATCH_OWNER, Instant.now());
      try {
        RunOrchestrator.RunPipelineRequest request =
            RunOrchestrator.RunPipelineRequest.fromApprovedPlanRow(handoff.run(), continuation);
        dispatchRegistered(handoff, request);
      } catch (PostHandoffDispatchException dispatchFailure) {
        handlePostHandoffDispatchFailure(handoff, dispatchFailure);
        return;
      } catch (RuntimeException requestFailure) {
        // The durable handoff has committed, but no local task has been registered yet. Release
        // this exact owner so the completed-handoff sweep can retry instead of stranding WORKING.
        handlePostHandoffDispatchFailure(
            handoff, new PostHandoffDispatchException(requestFailure, true));
        return;
      }
      emitStarted(lease.runId());
    } catch (PlanGateResumeStore.StaleLeaseException e) {
      log.info("plan-gate resume={} lease fenced: {}", lease.runId(), e.getMessage());
    } catch (PlanGateResumeStore.PoisonMessageException | IllegalArgumentException e) {
      log.error("plan-gate resume={} rejected durable payload: {}", lease.runId(), e.getMessage());
      try {
        store.dead(lease, e.getMessage(), Instant.now());
      } catch (PlanGateResumeStore.StaleLeaseException stale) {
        log.info("plan-gate resume={} poison lease already fenced", lease.runId());
      }
    } catch (Exception e) {
      handleTransientFailure(lease, e);
    }
  }

  private void handlePostHandoffDispatchFailure(
      PlanGateResumeStore.Handoff handoff, PostHandoffDispatchException failure) {
    String error =
        "PLAN_GATE_POST_HANDOFF_DISPATCH_FAILED:"
            + failure.getCause().getClass().getSimpleName()
            + ":"
            + String.valueOf(failure.getCause().getMessage());
    boolean ownershipReleased = false;
    try {
      Instant now = Instant.now();
      if (failure.localRegistrationReleased()) {
        ownershipReleased =
            store.releaseRejectedHandoff(
                handoff, error, now.plus(retryBackoff(handoff.attempt())), now);
      }
    } catch (RuntimeException releaseFailure) {
      log.error(
          "plan-gate resume={} immediate post-handoff ownership release failed",
          handoff.run().getRunId(),
          releaseFailure);
    }
    if (ownershipReleased) {
      log.warn(
          "plan-gate resume={} executor rejected durable handoff; local registration released and"
              + " durable redispatch backoff scheduled",
          handoff.run().getRunId(),
          failure);
    } else {
      // A newer heartbeat means the worker did start and owns execution. Otherwise the independent
      // stale-completed sweep below will recover this handoff without relying on generic
      // auto-resume.
      log.error(
          "plan-gate resume={} executor dispatch failed after handoff; ownership release lost its"
              + " exact local fence",
          handoff.run().getRunId(),
          failure);
    }
    emitRecoveryWaiting(handoff.run().getRunId());
  }

  private void recoverStaleCompletedHandoff(String runId, Instant staleBefore, Instant scanAt) {
    try {
      store
          .claimStaleCompletedHandoff(runId, DISPATCH_OWNER, staleBefore, scanAt)
          .ifPresent(this::dispatchRecoveredHandoff);
    } catch (RuntimeException recoveryFailure) {
      log.error(
          "plan-gate resume={} failed stale completed-handoff recovery", runId, recoveryFailure);
    }
  }

  private void dispatchRecoveredHandoff(PlanGateResumeStore.Handoff handoff) {
    try {
      PlanGateContinuation continuation = decodeAndValidate(handoff.validationLease());
      if (!isQueuedHandoffOwner(handoff.executionOwner())) {
        throw new IllegalStateException("PLAN_GATE_RECOVERY_OWNER_IS_NOT_QUEUED");
      }
      if (!store.heartbeatRegisteredHandoff(handoff, Instant.now())) {
        log.info(
            "plan-gate resume={} lost queued ownership before workspace recovery",
            handoff.run().getRunId());
        return;
      }
      workspaceRecovery.restoreQueuedHandoff(
          handoff, executionStaleDuration(), maxWorkspaceRestoreSeconds);
      if (!store.heartbeatRegisteredHandoff(handoff, Instant.now())) {
        log.info(
            "plan-gate resume={} lost queued ownership after workspace recovery",
            handoff.run().getRunId());
        return;
      }
      RunOrchestrator.RunPipelineRequest request =
          RunOrchestrator.RunPipelineRequest.fromApprovedPlanRow(handoff.run(), continuation);
      dispatchRegistered(handoff, request);
      log.warn(
          "plan-gate resume={} redispatched a stale completed handoff", handoff.run().getRunId());
      emitStarted(handoff.run().getRunId());
    } catch (PostHandoffDispatchException dispatchFailure) {
      handlePostHandoffDispatchFailure(handoff, dispatchFailure);
    } catch (RuntimeException preparationFailure) {
      // Payload validation/request reconstruction failed before local registration. Preserve the
      // immutable completed handoff and release only this claimed owner for bounded redispatch.
      handlePostHandoffDispatchFailure(
          handoff, new PostHandoffDispatchException(preparationFailure, true));
    }
  }

  private void emitStarted(String runId) {
    try {
      broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
      broadcaster.emit(
          runId, RunEvent.log(runId, "approval", "Approved plan continuation started"));
    } catch (RuntimeException emitFailure) {
      log.debug("plan-gate resume={} start evidence emit failed", runId, emitFailure);
    }
  }

  private void emitRecoveryWaiting(String runId) {
    try {
      broadcaster.emit(
          runId,
          RunEvent.log(runId, "recovery", "Continuation dispatch failed; durable retry is queued"));
    } catch (RuntimeException emitFailure) {
      log.debug("plan-gate resume={} recovery evidence emit failed", runId, emitFailure);
    }
  }

  private PlanGateContinuation decodeAndValidate(PlanGateResumeStore.Lease lease) {
    if (lease.handlerContractVersion() != SUPPORTED_HANDLER_CONTRACT_VERSION) {
      throw new PlanGateResumeStore.PoisonMessageException(
          "UNSUPPORTED_PLAN_GATE_CONTINUATION_SCHEMA:" + lease.handlerContractVersion());
    }
    if (lease.payload() == null
        || lease.payloadSha256() == null
        || !MessageDigest.isEqual(
            sha256(lease.payload()).getBytes(StandardCharsets.US_ASCII),
            lease.payloadSha256().getBytes(StandardCharsets.US_ASCII))) {
      throw new PlanGateResumeStore.PoisonMessageException(
          "PLAN_GATE_CONTINUATION_DIGEST_MISMATCH");
    }
    try {
      PlanGateContinuation continuation =
          objectMapper.readValue(lease.payload(), PlanGateContinuation.class);
      if (continuation.schemaVersion() != lease.handlerContractVersion()) {
        throw new PlanGateResumeStore.PoisonMessageException(
            "PLAN_GATE_CONTINUATION_SCHEMA_MISMATCH");
      }
      return continuation;
    } catch (PlanGateResumeStore.PoisonMessageException e) {
      throw e;
    } catch (Exception e) {
      throw new PlanGateResumeStore.PoisonMessageException("PLAN_GATE_CONTINUATION_INVALID_JSON");
    }
  }

  void runWithQueuedHandoffRenewal(
      PlanGateResumeStore.Handoff handoff,
      Runnable operation,
      Duration renewalInterval,
      int maxRenewals) {
    workspaceRecovery.runWithQueuedHandoffRenewal(handoff, operation, renewalInterval, maxRenewals);
  }

  void runWithLeaseRenewal(
      PlanGateResumeStore.Lease lease,
      Runnable operation,
      Duration renewalInterval,
      int maxRenewals) {
    workspaceRecovery.runWithLeaseRenewal(
        lease, operation, renewalInterval, maxRenewals, leaseDuration());
  }

  @PreDestroy
  void shutdownLeaseRenewalExecutor() {
    leaseRenewalExecutor.shutdownNow();
    workspaceRecovery.shutdown();
  }

  private void dispatchRegistered(
      PlanGateResumeStore.Handoff handoff, RunOrchestrator.RunPipelineRequest request) {
    String runId = handoff.run().getRunId();
    AtomicReference<ScheduledFuture<?>> queuedHeartbeatRef = new AtomicReference<>();
    AtomicReference<FutureTask<Void>> taskReference = new AtomicReference<>();
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              ScheduledFuture<?> queuedHeartbeat = queuedHeartbeatRef.getAndSet(null);
              if (queuedHeartbeat != null) queuedHeartbeat.cancel(false);
              try {
                if (!store.startRegisteredHandoff(handoff, EXECUTION_OWNER, Instant.now())) {
                  log.info(
                      "plan-gate resume={} skipped stale queued Future after ownership takeover",
                      runId);
                  return null;
                }
                if (!controlPort.adoptRuntimeHeartbeat(runId, taskReference.get())) {
                  log.info(
                      "plan-gate resume={} skipped runtime start after local registration changed",
                      runId);
                  return null;
                }
                controlPort.runPipelineForResume(request);
              } finally {
                controlPort.removeActiveRun(runId, taskReference.get());
              }
              return null;
            });
    taskReference.set(task);
    // Registration and the dispatcher-owned heartbeat happen before execute: even a deeply queued
    // task remains cancellable and durably leased until its first instruction transfers ownership
    // to the normal runtime host.
    boolean registered = false;
    try {
      controlPort.putExternallyHeartbeatedActiveRun(runId, task);
      registered = true;
      long heartbeatPeriodMillis = queuedHeartbeatPeriod().toMillis();
      ScheduledFuture<?> queuedHeartbeat =
          leaseRenewalExecutor.scheduleAtFixedRate(
              () -> {
                try {
                  if (!store.heartbeatRegisteredHandoff(handoff, Instant.now())) {
                    ScheduledFuture<?> scheduled = queuedHeartbeatRef.getAndSet(null);
                    if (scheduled != null) scheduled.cancel(false);
                    if (task.cancel(false)) {
                      controlPort.removeActiveRun(runId, task);
                    }
                  }
                } catch (RuntimeException heartbeatFailure) {
                  // A transient database failure is not proof of ownership loss. The next tick can
                  // recover; if this process dies or remains unhealthy, the durable stale sweep
                  // takes over and the start-time fence prevents this Future from running later.
                  log.warn(
                      "plan-gate resume={} queued handoff heartbeat failed: {}",
                      runId,
                      heartbeatFailure.getMessage());
                }
              },
              heartbeatPeriodMillis,
              heartbeatPeriodMillis,
              TimeUnit.MILLISECONDS);
      queuedHeartbeatRef.set(queuedHeartbeat);
      pool.executor().execute(task);
    } catch (RuntimeException e) {
      ScheduledFuture<?> queuedHeartbeat = queuedHeartbeatRef.getAndSet(null);
      if (queuedHeartbeat != null) queuedHeartbeat.cancel(false);
      // Once registration succeeded, only compare-and-remove of this exact Future proves that
      // durable queued ownership is safe to release. A stale Future must never remove a newer
      // same-run registration.
      boolean removedExactRegistration = controlPort.removeActiveRun(runId, task);
      boolean exactRegistrationReleased = removedExactRegistration || !registered;
      throw new PostHandoffDispatchException(e, exactRegistrationReleased);
    }
  }

  private static final class PostHandoffDispatchException extends RuntimeException {
    private final boolean localRegistrationReleased;

    private PostHandoffDispatchException(Throwable cause, boolean localRegistrationReleased) {
      super(cause);
      this.localRegistrationReleased = localRegistrationReleased;
    }

    private boolean localRegistrationReleased() {
      return localRegistrationReleased;
    }
  }

  private void handleTransientFailure(PlanGateResumeStore.Lease lease, Exception error) {
    String message = error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage());
    if (lease.attempt() >= lease.maxAttempts()) {
      log.error("plan-gate resume={} exhausted after {} attempts", lease.runId(), lease.attempt());
      store.dead(lease, "PLAN_GATE_RESUME_ATTEMPTS_EXHAUSTED:" + message, Instant.now());
      return;
    }
    Duration backoff = retryBackoff(lease.attempt());
    log.warn(
        "plan-gate resume={} attempt={} retrying in {}s: {}",
        lease.runId(),
        lease.attempt(),
        backoff.toSeconds(),
        message);
    store.retry(lease, message, Instant.now().plus(backoff), Instant.now());
  }

  private Duration leaseDuration() {
    return Duration.ofSeconds(Math.max(10, leaseSeconds));
  }

  private Duration executionStaleDuration() {
    return Duration.ofSeconds(Math.max(30, executionStaleSeconds));
  }

  private Duration queuedHeartbeatPeriod() {
    return Duration.ofSeconds(Math.max(1, Math.min(30, executionStaleDuration().toSeconds() / 3)));
  }

  static Duration retryBackoff(int attempt) {
    long seconds = 1L << Math.min(20, Math.max(0, attempt));
    return Duration.ofSeconds(Math.min(MAX_BACKOFF.toSeconds(), seconds));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  static String newQueuedHandoffOwner() {
    String suffix = QUEUED_HANDOFF_OWNER_MARKER + UUID.randomUUID();
    String host = ExecutionHostIdentity.current();
    int hostLength = Math.max(0, 128 - suffix.length());
    return host.substring(0, Math.min(host.length(), hostLength)) + suffix;
  }

  /** True only for the explicit per-process owner used before a queued Future starts. */
  public static boolean isQueuedHandoffOwner(String owner) {
    return owner != null && owner.contains(QUEUED_HANDOFF_OWNER_MARKER);
  }
}
