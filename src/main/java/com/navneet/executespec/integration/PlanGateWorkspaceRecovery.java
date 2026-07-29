package com.navneet.executespec.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bounded workspace restoration for plan-gate continuation delivery.
 *
 * <p>This collaborator owns only potentially slow workspace I/O and its heartbeat loops. Durable
 * claim, receipt validation, handoff, retry, and dispatch decisions remain in {@link
 * PlanGateResumeDispatcher} and {@link PlanGateResumeStore}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class PlanGateWorkspaceRecovery {
  private final PlanGateResumeStore store;
  private final AgentProperties agentProperties;
  private final WorkspaceRestoreService workspaceRestoreService;
  private final ScheduledExecutorService renewalExecutor =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "plan-gate-workspace-renewal");
            thread.setDaemon(true);
            return thread;
          });
  private final ExecutorService workspaceRestoreExecutor =
      Executors.newVirtualThreadPerTaskExecutor();

  void restoreWithLease(
      PlanGateResumeStore.Lease lease, Duration leaseDuration, long maxRestoreSeconds) {
    Duration interval =
        Duration.ofSeconds(Math.max(1, Math.min(30, leaseDuration.toSeconds() / 3)));
    runWithLeaseRenewal(
        lease,
        () -> ensureWorkspace(lease.runId()),
        interval,
        maxRenewals(maxRestoreSeconds, interval),
        leaseDuration);
  }

  void restoreQueuedHandoff(
      PlanGateResumeStore.Handoff handoff,
      Duration executionStaleDuration,
      long maxRestoreSeconds) {
    Duration interval =
        Duration.ofSeconds(Math.max(1, Math.min(30, executionStaleDuration.toSeconds() / 3)));
    runWithQueuedHandoffRenewal(
        handoff,
        () -> ensureWorkspace(handoff.run().getRunId()),
        interval,
        maxRenewals(maxRestoreSeconds, interval));
  }

  void runWithQueuedHandoffRenewal(
      PlanGateResumeStore.Handoff handoff,
      Runnable operation,
      Duration renewalInterval,
      int maxRenewals) {
    runWithRenewal(
        handoff.run().getRunId(),
        operation,
        renewalInterval,
        maxRenewals,
        () -> store.heartbeatRegisteredHandoff(handoff, Instant.now()),
        "PLAN_GATE_QUEUED_RESTORE",
        "PLAN_GATE_QUEUED_RESTORE_OWNER_LOST");
  }

  void runWithLeaseRenewal(
      PlanGateResumeStore.Lease lease,
      Runnable operation,
      Duration renewalInterval,
      int maxRenewals,
      Duration leaseDuration) {
    runWithRenewal(
        lease.runId(),
        operation,
        renewalInterval,
        maxRenewals,
        () -> store.heartbeat(lease, Instant.now(), leaseDuration),
        "PLAN_GATE_RESUME_RESTORE",
        "PLAN_GATE_RESUME_RESTORE_LEASE_LOST");
  }

  private void runWithRenewal(
      String runId,
      Runnable operation,
      Duration renewalInterval,
      int maxRenewals,
      Heartbeat heartbeat,
      String failurePrefix,
      String ownershipLostMessage) {
    AtomicBoolean ownershipLost = new AtomicBoolean();
    AtomicInteger renewals = new AtomicInteger();
    long periodMillis = Math.max(1, renewalInterval.toMillis());
    int boundedRenewals = Math.max(1, maxRenewals);
    long timeoutMillis = boundedTimeoutMillis(periodMillis, boundedRenewals);
    Future<?> operationFuture = workspaceRestoreExecutor.submit(operation);
    AtomicReference<ScheduledFuture<?>> renewalRef = new AtomicReference<>();
    ScheduledFuture<?> renewal =
        renewalExecutor.scheduleAtFixedRate(
            () -> {
              if (renewals.incrementAndGet() > boundedRenewals) {
                ScheduledFuture<?> scheduled = renewalRef.get();
                if (scheduled != null) scheduled.cancel(false);
                return;
              }
              try {
                if (!heartbeat.renew()) {
                  ownershipLost.set(true);
                  operationFuture.cancel(true);
                }
              } catch (RuntimeException heartbeatFailure) {
                ownershipLost.set(true);
                operationFuture.cancel(true);
                log.warn(
                    "plan-gate resume={} workspace ownership renewal failed: {}",
                    runId,
                    heartbeatFailure.getMessage());
              }
            },
            periodMillis,
            periodMillis,
            TimeUnit.MILLISECONDS);
    renewalRef.set(renewal);
    try {
      operationFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      operationFuture.cancel(true);
      throw stale(failurePrefix + "_TIMEOUT");
    } catch (CancellationException cancelled) {
      throw stale(ownershipLost.get() ? ownershipLostMessage : failurePrefix + "_CANCELLED");
    } catch (InterruptedException interrupted) {
      operationFuture.cancel(true);
      Thread.currentThread().interrupt();
      throw stale(failurePrefix + "_INTERRUPTED");
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof RuntimeException runtime) throw runtime;
      throw new IllegalStateException("Workspace restore failed", cause);
    } finally {
      renewal.cancel(false);
    }
    if (ownershipLost.get()) throw stale(ownershipLostMessage);
  }

  private void ensureWorkspace(String runId) {
    Path workspace = Path.of(agentProperties.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) workspaceRestoreService.ensureWorkspaceAvailable(runId);
    if (!Files.isDirectory(workspace)) {
      throw new IllegalStateException("PLAN_GATE_RESUME_WORKSPACE_UNAVAILABLE");
    }
  }

  private static int maxRenewals(long maxRestoreSeconds, Duration interval) {
    return Math.max(
        1,
        (int) Math.ceil((double) Math.max(1, maxRestoreSeconds) / (double) interval.toSeconds()));
  }

  private static long boundedTimeoutMillis(long periodMillis, int boundedRenewals) {
    try {
      return Math.multiplyExact(periodMillis, (long) boundedRenewals);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static PlanGateResumeStore.StaleLeaseException stale(String message) {
    return new PlanGateResumeStore.StaleLeaseException(message);
  }

  @PreDestroy
  void shutdown() {
    renewalExecutor.shutdownNow();
    workspaceRestoreExecutor.shutdownNow();
  }

  @FunctionalInterface
  private interface Heartbeat {
    boolean renew();
  }
}
