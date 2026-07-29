package com.navneet.executespec.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;
import com.navneet.executespec.harness.repository.GateRepository;
import com.navneet.executespec.harness.repository.PlanGateResumeOutboxRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Transaction boundaries for leased plan-gate continuation delivery. */
@Service
@RequiredArgsConstructor
class PlanGateResumeStore {
  private final PipelineRunRepository runRepository;
  private final GateRepository gateRepository;
  private final PlanGateResumeOutboxRepository outboxRepository;
  private final EntityManager entityManager;
  private final PlanGateResumeIntegrityValidator integrityValidator;

  @Transactional
  Optional<Lease> claimNext(String owner, Instant now, Duration leaseDuration) {
    PlanGateResumeOutbox outbox = outboxRepository.findNextClaimableForUpdate(now).orElse(null);
    if (outbox == null) return Optional.empty();
    UUID token = UUID.randomUUID();
    outbox.setStatus("PROCESSING");
    outbox.setAttemptCount(outbox.getAttemptCount() + 1);
    outbox.setLeaseOwner(owner);
    outbox.setLeaseToken(token);
    outbox.setHeartbeatAt(now);
    outbox.setLeaseExpiresAt(now.plus(leaseDuration));
    outbox.setLastError(null);
    outboxRepository.saveAndFlush(outbox);
    return Optional.of(
        new Lease(
            outbox.getRunId(),
            outbox.getGateKey(),
            outbox.getPayload(),
            outbox.getPayloadSha256(),
            outbox.getHandlerContractVersion(),
            outbox.getAttemptCount(),
            outbox.getMaxAttempts(),
            owner,
            token));
  }

  @Transactional
  boolean heartbeat(Lease lease, Instant now, Duration leaseDuration) {
    return outboxRepository.heartbeat(
            lease.runId(), lease.owner(), lease.token(), now, now.plus(leaseDuration))
        == 1;
  }

  java.util.List<String> exhaustedClaimableRunIds(Instant now) {
    return outboxRepository.findExhaustedClaimableRunIds(now);
  }

  /**
   * Bounded candidates whose durable handoff completed but whose execution owner stopped
   * heartbeating before a worker could make progress. This recovery lane belongs to the plan-gate
   * dispatcher and therefore remains active even when generic crash auto-resume is disabled.
   */
  @Transactional(readOnly = true)
  java.util.List<String> staleCompletedHandoffRunIds(Instant now, Instant staleBefore, int limit) {
    return entityManager
        .createQuery(
            """
            SELECT o.runId
              FROM PlanGateResumeOutbox o
              JOIN PipelineRun r ON r.runId = o.runId
             WHERE o.status = 'COMPLETED'
               AND r.status = 'WORKING'
               AND r.executingInstance LIKE :queuedOwnerPattern
               AND o.nextRetryAt <= :now
               AND (r.executionHeartbeat IS NULL OR r.executionHeartbeat < :staleBefore)
             ORDER BY o.completedAt ASC
            """,
            String.class)
        .setParameter("now", now)
        .setParameter("staleBefore", staleBefore)
        .setParameter(
            "queuedOwnerPattern", "%" + PlanGateResumeDispatcher.QUEUED_HANDOFF_OWNER_MARKER + "%")
        .setMaxResults(Math.max(1, limit))
        .getResultList();
  }

  Optional<ContinuationPayload> completedContinuation(String runId) {
    return outboxRepository
        .findById(runId)
        .map(
            outbox -> {
              if (!"COMPLETED".equals(outbox.getStatus())) {
                throw new PoisonMessageException(
                    "PLAN_GATE_CONTINUATION_NOT_HANDED_OFF:" + outbox.getStatus());
              }
              return new ContinuationPayload(
                  outbox.getPayload(),
                  outbox.getPayloadSha256(),
                  outbox.getHandlerContractVersion());
            });
  }

  @Transactional
  boolean deadExhausted(String runId, Instant now) {
    PipelineRun run = runRepository.findByIdForPlanGateOpen(runId).orElse(null);
    if (run == null) return false;
    GateRecord gate = gateRepository.findFirstByRunIdAndGateType(runId, "PLAN").orElse(null);
    if (gate != null) gateRepository.findByIdForPlanGateOpen(gate.getGateKey()).orElse(null);
    PlanGateResumeOutbox outbox = outboxRepository.findByIdForUpdate(runId).orElse(null);
    if (outbox == null
        || outbox.getAttemptCount() < outbox.getMaxAttempts()
        || !("READY".equals(outbox.getStatus())
                && outbox.getNextRetryAt() != null
                && !outbox.getNextRetryAt().isAfter(now)
            || ("PROCESSING".equals(outbox.getStatus())
                && outbox.getLeaseExpiresAt() != null
                && !outbox.getLeaseExpiresAt().isAfter(now)))) {
      return false;
    }
    if ("CANCELLED".equals(run.getStatus())) {
      markTerminal(outbox, "CANCELLED", "PLAN_GATE_RESUME_CANCELLED", now);
    } else if ("APPROVAL_RESUMING".equals(run.getStatus())) {
      run.setStatus("FAILED");
      run.setCurrentGate(null);
      run.setFailureReason("PLAN_GATE_RESUME_ATTEMPTS_EXHAUSTED");
      run.setCompletedAt(now);
      runRepository.save(run);
      markTerminal(outbox, "DEAD", "PLAN_GATE_RESUME_ATTEMPTS_EXHAUSTED", now);
    } else {
      return false;
    }
    outboxRepository.saveAndFlush(outbox);
    return true;
  }

  /**
   * Leased preflight fence before any workspace restore is attempted. This deliberately performs
   * the same durable identity validation as {@link #handoff(Lease, Instant)} while the lease is
   * still owned, but leaves every row unchanged. The final handoff repeats the validation so a
   * concurrent cancellation or database mutation between restore and dispatch remains fenced.
   */
  @Transactional
  void preflight(Lease lease, Instant now) {
    PipelineRun run =
        runRepository
            .findByIdForPlanGateOpen(lease.runId())
            .orElseThrow(() -> new PoisonMessageException("PLAN_GATE_RESUME_RUN_MISSING"));
    GateRecord gate =
        gateRepository
            .findByIdForPlanGateOpen(lease.gateKey())
            .orElseThrow(() -> new PoisonMessageException("PLAN_GATE_RESUME_GATE_MISSING"));
    PlanGateResumeOutbox outbox =
        outboxRepository
            .findByIdForUpdate(lease.runId())
            .orElseThrow(() -> new PoisonMessageException("PLAN_GATE_RESUME_OUTBOX_MISSING"));
    requireOwned(outbox, lease, now);
    integrityValidator.validate(run, gate, outbox);
    if (!"APPROVAL_RESUMING".equals(run.getStatus())) {
      throw new StaleLeaseException("RUN_NOT_APPROVAL_RESUMING:" + run.getStatus());
    }
  }

  /**
   * Final delivery fence. Lock order mirrors the decision transaction: run, gate, then outbox. The
   * pipeline is allowed to start only after this transaction commits.
   */
  @Transactional
  Handoff handoff(Lease lease, Instant now) {
    return handoff(lease, PlanGateResumeDispatcher.newQueuedHandoffOwner(), now);
  }

  @Transactional
  Handoff handoff(Lease lease, String executionOwner, Instant now) {
    if (executionOwner == null || executionOwner.isBlank()) {
      throw new IllegalArgumentException("Plan-gate execution owner is required");
    }
    if (!PlanGateResumeDispatcher.isQueuedHandoffOwner(executionOwner)) {
      throw new IllegalArgumentException(
          "Plan-gate handoff owner must be an explicit queued owner");
    }
    Instant handoffAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    PipelineRun run =
        runRepository
            .findByIdForPlanGateOpen(lease.runId())
            .orElseThrow(() -> new PoisonMessageException("PLAN_GATE_RESUME_RUN_MISSING"));
    GateRecord gate =
        gateRepository
            .findByIdForPlanGateOpen(lease.gateKey())
            .orElseThrow(() -> new PoisonMessageException("PLAN_GATE_RESUME_GATE_MISSING"));
    PlanGateResumeOutbox outbox =
        outboxRepository
            .findByIdForUpdate(lease.runId())
            .orElseThrow(() -> new PoisonMessageException("PLAN_GATE_RESUME_OUTBOX_MISSING"));
    requireOwned(outbox, lease, now);
    integrityValidator.validate(run, gate, outbox);
    if (!"APPROVAL_RESUMING".equals(run.getStatus())) {
      throw new StaleLeaseException("RUN_NOT_APPROVAL_RESUMING:" + run.getStatus());
    }

    run.setStatus("WORKING");
    run.setCurrentGate(null);
    run.setResumedAt(handoffAt);
    run.setExecutingInstance(executionOwner);
    run.setExecutionHeartbeat(handoffAt);
    runRepository.save(run);
    clearLease(outbox);
    outbox.setStatus("COMPLETED");
    outbox.setCompletedAt(handoffAt);
    outboxRepository.saveAndFlush(outbox);
    return handoff(run, outbox, executionOwner, handoffAt);
  }

  /**
   * Releases a handoff only while the same durable execution owner remains on the run. The caller
   * must first prove that it removed its exact pre-registered FutureTask from the local active-run
   * map; this allows release after that registration refreshed the heartbeat, without releasing a
   * different owner's worker.
   */
  @Transactional
  boolean releaseRejectedHandoff(Handoff handoff, String error, Instant nextRetryAt, Instant now) {
    PipelineRun run = runRepository.findByIdForPlanGateOpen(handoff.run().getRunId()).orElse(null);
    if (run == null) return false;
    GateRecord gate =
        gateRepository.findFirstByRunIdAndGateType(handoff.run().getRunId(), "PLAN").orElse(null);
    if (gate == null) return false;
    gateRepository.findByIdForPlanGateOpen(gate.getGateKey()).orElseThrow();
    PlanGateResumeOutbox outbox =
        outboxRepository.findByIdForUpdate(handoff.run().getRunId()).orElse(null);
    if (!isRecoverableCompletedHandoff(run, gate, outbox)
        || !PlanGateResumeDispatcher.isQueuedHandoffOwner(run.getExecutingInstance())
        || !Objects.equals(run.getExecutingInstance(), handoff.executionOwner())) {
      return false;
    }
    integrityValidator.validate(run, gate, outbox);
    releaseCompletedHandoff(run, outbox, error, nextRetryAt, now);
    return true;
  }

  /**
   * Claims one stale/unowned completed handoff using the run heartbeat as the durable execution
   * fence. The outbox remains COMPLETED because V347 deliberately makes that handoff fact
   * immutable; COMPLETED means durable worker handoff, not successful local queue submission.
   */
  @Transactional
  Optional<Handoff> claimStaleCompletedHandoff(
      String runId, String executionOwner, Instant staleBefore, Instant now) {
    if (executionOwner == null || executionOwner.isBlank()) {
      throw new IllegalArgumentException("Plan-gate execution owner is required");
    }
    if (!PlanGateResumeDispatcher.isQueuedHandoffOwner(executionOwner)) {
      throw new IllegalArgumentException(
          "Plan-gate completed-handoff claim requires an explicit queued owner");
    }
    PipelineRun run = runRepository.findByIdForPlanGateOpen(runId).orElse(null);
    if (run == null) return Optional.empty();
    GateRecord gate = gateRepository.findFirstByRunIdAndGateType(runId, "PLAN").orElse(null);
    if (gate == null) return Optional.empty();
    gateRepository.findByIdForPlanGateOpen(gate.getGateKey()).orElseThrow();
    PlanGateResumeOutbox outbox = outboxRepository.findByIdForUpdate(runId).orElse(null);
    if (!isRecoverableCompletedHandoff(run, gate, outbox)
        || !PlanGateResumeDispatcher.isQueuedHandoffOwner(run.getExecutingInstance())
        || outbox.getNextRetryAt() == null
        || outbox.getNextRetryAt().isAfter(now)
        || (run.getExecutionHeartbeat() != null
            && !run.getExecutionHeartbeat().isBefore(staleBefore))) {
      return Optional.empty();
    }
    integrityValidator.validate(run, gate, outbox);
    if (outbox.getAttemptCount() >= outbox.getMaxAttempts()) {
      run.setStatus("FAILED");
      run.setCurrentGate(null);
      run.setExecutingInstance(null);
      run.setExecutionHeartbeat(null);
      run.setFailureReason("PLAN_GATE_POST_HANDOFF_ATTEMPTS_EXHAUSTED");
      run.setCompletedAt(now);
      runRepository.save(run);
      outbox.setLastError("PLAN_GATE_POST_HANDOFF_ATTEMPTS_EXHAUSTED");
      outboxRepository.saveAndFlush(outbox);
      return Optional.empty();
    }
    Instant claimedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    run.setExecutingInstance(executionOwner);
    run.setExecutionHeartbeat(claimedAt);
    run.setResumedAt(claimedAt);
    runRepository.save(run);
    outbox.setAttemptCount(outbox.getAttemptCount() + 1);
    outbox.setLastError(null);
    outboxRepository.saveAndFlush(outbox);
    return Optional.of(handoff(run, outbox, executionOwner, claimedAt));
  }

  /** Refreshes a queued local handoff only while its unique dispatcher owner still holds it. */
  @Transactional
  boolean heartbeatRegisteredHandoff(Handoff handoff, Instant now) {
    if (!PlanGateResumeDispatcher.isQueuedHandoffOwner(handoff.executionOwner())) return false;
    return runRepository.refreshExecutionHeartbeat(
            handoff.run().getRunId(), handoff.executionOwner(), now)
        == 1;
  }

  /**
   * Atomically transfers a queued handoff from its unique dispatcher owner to the runtime host at
   * the first instruction of the accepted Future. A stale Future whose owner was taken over cannot
   * cross this fence and therefore cannot execute the pipeline.
   */
  @Transactional
  boolean startRegisteredHandoff(Handoff handoff, String runtimeOwner, Instant now) {
    if (runtimeOwner == null || runtimeOwner.isBlank()) {
      throw new IllegalArgumentException("Plan-gate runtime owner is required");
    }
    if (PlanGateResumeDispatcher.isQueuedHandoffOwner(runtimeOwner)) {
      throw new IllegalArgumentException("Plan-gate runtime owner cannot be a queued owner");
    }
    PipelineRun run = runRepository.findByIdForPlanGateOpen(handoff.run().getRunId()).orElse(null);
    if (run == null) return false;
    GateRecord gate =
        gateRepository.findFirstByRunIdAndGateType(handoff.run().getRunId(), "PLAN").orElse(null);
    if (gate == null) return false;
    gateRepository.findByIdForPlanGateOpen(gate.getGateKey()).orElseThrow();
    PlanGateResumeOutbox outbox =
        outboxRepository.findByIdForUpdate(handoff.run().getRunId()).orElse(null);
    if (!isRecoverableCompletedHandoff(run, gate, outbox)
        || !PlanGateResumeDispatcher.isQueuedHandoffOwner(run.getExecutingInstance())
        || !PlanGateResumeDispatcher.isQueuedHandoffOwner(handoff.executionOwner())
        || !Objects.equals(run.getExecutingInstance(), handoff.executionOwner())) {
      return false;
    }
    integrityValidator.validate(run, gate, outbox);
    Instant startedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    run.setExecutingInstance(runtimeOwner);
    run.setExecutionHeartbeat(startedAt);
    run.setResumedAt(startedAt);
    runRepository.save(run);
    return true;
  }

  @Transactional
  boolean retry(Lease lease, String error, Instant nextRetryAt, Instant now) {
    PipelineRun run = runRepository.findByIdForPlanGateOpen(lease.runId()).orElse(null);
    if (run == null) return false;
    gateRepository.findByIdForPlanGateOpen(lease.gateKey()).orElse(null);
    PlanGateResumeOutbox outbox = outboxRepository.findByIdForUpdate(lease.runId()).orElse(null);
    if (outbox == null || !ownedBy(outbox, lease, now)) return false;
    if ("CANCELLED".equals(run.getStatus())) {
      markTerminal(outbox, "CANCELLED", error, now);
      outboxRepository.saveAndFlush(outbox);
      return true;
    }
    if ("FAILED".equals(run.getStatus())) {
      markTerminal(outbox, "DEAD", error, now);
      outboxRepository.saveAndFlush(outbox);
      return true;
    }
    if (!"APPROVAL_RESUMING".equals(run.getStatus())) return false;
    clearLease(outbox);
    outbox.setStatus("READY");
    outbox.setNextRetryAt(nextRetryAt);
    outbox.setLastError(limit(error));
    outbox.setCompletedAt(null);
    outboxRepository.saveAndFlush(outbox);
    return true;
  }

  @Transactional
  boolean dead(Lease lease, String error, Instant now) {
    PipelineRun run = runRepository.findByIdForPlanGateOpen(lease.runId()).orElse(null);
    if (run == null) return false;
    // Preserve the global lock order even though the gate itself is immutable at this point.
    gateRepository.findByIdForPlanGateOpen(lease.gateKey()).orElse(null);
    PlanGateResumeOutbox outbox = outboxRepository.findByIdForUpdate(lease.runId()).orElse(null);
    if (outbox == null || !ownedBy(outbox, lease, now)) return false;
    if ("CANCELLED".equals(run.getStatus())) {
      markTerminal(outbox, "CANCELLED", error, now);
      outboxRepository.saveAndFlush(outbox);
      return true;
    }
    if ("FAILED".equals(run.getStatus())) {
      markTerminal(outbox, "DEAD", error, now);
      outboxRepository.saveAndFlush(outbox);
      return true;
    }
    if (!"APPROVAL_RESUMING".equals(run.getStatus())) {
      throw new StaleLeaseException("RUN_NOT_DEAD_LETTERABLE:" + run.getStatus());
    }
    run.setStatus("FAILED");
    run.setCurrentGate(null);
    run.setFailureReason(limit(error));
    run.setCompletedAt(now);
    runRepository.save(run);
    markTerminal(outbox, "DEAD", error, now);
    outboxRepository.saveAndFlush(outbox);
    return true;
  }

  /** Cancels after approval but before local delivery, preserving the immutable APPROVE receipt. */
  @Transactional
  boolean cancelApprovedRun(String runId, String reason, Instant now) {
    PipelineRun run = runRepository.findByIdForPlanGateOpen(runId).orElse(null);
    if (run == null) return false;
    GateRecord gate = gateRepository.findFirstByRunIdAndGateType(runId, "PLAN").orElse(null);
    if (gate == null) return false;
    gateRepository.findByIdForPlanGateOpen(gate.getGateKey()).orElseThrow();
    PlanGateResumeOutbox outbox = outboxRepository.findByIdForUpdate(runId).orElse(null);
    if (outbox == null
        || !"APPROVED".equals(gate.getStatus())
        || !"APPROVAL_RESUMING".equals(run.getStatus())
        || !("READY".equals(outbox.getStatus()) || "PROCESSING".equals(outbox.getStatus()))) {
      return false;
    }
    run.setStatus("CANCELLED");
    run.setCurrentGate(null);
    run.setFailureReason(limit(reason));
    run.setCompletedAt(now);
    runRepository.save(run);
    markTerminal(outbox, "CANCELLED", reason, now);
    outboxRepository.saveAndFlush(outbox);
    return true;
  }

  private static void requireOwned(PlanGateResumeOutbox outbox, Lease lease, Instant now) {
    if (!ownedBy(outbox, lease.token())
        || !Objects.equals(outbox.getLeaseOwner(), lease.owner())
        || outbox.getLeaseExpiresAt() == null
        || !outbox.getLeaseExpiresAt().isAfter(now)) {
      throw new StaleLeaseException("PLAN_GATE_RESUME_STALE_LEASE");
    }
  }

  private static boolean ownedBy(PlanGateResumeOutbox outbox, UUID token) {
    return "PROCESSING".equals(outbox.getStatus()) && Objects.equals(outbox.getLeaseToken(), token);
  }

  private static boolean ownedBy(PlanGateResumeOutbox outbox, Lease lease, Instant now) {
    return ownedBy(outbox, lease.token())
        && Objects.equals(outbox.getLeaseOwner(), lease.owner())
        && outbox.getLeaseExpiresAt() != null
        && outbox.getLeaseExpiresAt().isAfter(now);
  }

  private static void markTerminal(
      PlanGateResumeOutbox outbox, String status, String error, Instant now) {
    clearLease(outbox);
    outbox.setStatus(status);
    outbox.setLastError(limit(error));
    outbox.setCompletedAt(now);
  }

  private static boolean isRecoverableCompletedHandoff(
      PipelineRun run, GateRecord gate, PlanGateResumeOutbox outbox) {
    return outbox != null
        && "APPROVED".equals(gate.getStatus())
        && "APPROVE".equals(gate.getDecisionKind())
        && "WORKING".equals(run.getStatus())
        && "COMPLETED".equals(outbox.getStatus());
  }

  private void releaseCompletedHandoff(
      PipelineRun run,
      PlanGateResumeOutbox outbox,
      String error,
      Instant nextRetryAt,
      Instant now) {
    // Preserve the explicit queued-lane marker while no local Future is registered. A null owner
    // would make generic StartupRunResumer mistake this backoff window for a runtime-started crash
    // and bypass the outbox attempt budget. The next dispatcher claim replaces this marker with
    // its own process-unique queued owner.
    run.setExecutionHeartbeat(null);
    run.setResumedAt(now);
    runRepository.save(run);
    outbox.setNextRetryAt(nextRetryAt);
    outbox.setLastError(limit(error));
    outboxRepository.saveAndFlush(outbox);
  }

  private static Handoff handoff(
      PipelineRun run,
      PlanGateResumeOutbox outbox,
      String executionOwner,
      Instant executionHeartbeat) {
    return new Handoff(
        run,
        outbox.getGateKey(),
        outbox.getPayload(),
        outbox.getPayloadSha256(),
        outbox.getHandlerContractVersion(),
        outbox.getAttemptCount(),
        outbox.getMaxAttempts(),
        executionOwner,
        executionHeartbeat);
  }

  private static void clearLease(PlanGateResumeOutbox outbox) {
    outbox.setLeaseOwner(null);
    outbox.setLeaseToken(null);
    outbox.setLeaseExpiresAt(null);
    outbox.setHeartbeatAt(null);
  }

  private static String limit(String error) {
    if (error == null) return null;
    return error.length() <= 2000 ? error : error.substring(0, 2000);
  }

  record Lease(
      String runId,
      String gateKey,
      String payload,
      String payloadSha256,
      int handlerContractVersion,
      int attempt,
      int maxAttempts,
      String owner,
      UUID token) {}

  record Handoff(
      PipelineRun run,
      String gateKey,
      String continuationPayload,
      String payloadSha256,
      int handlerContractVersion,
      int attempt,
      int maxAttempts,
      String executionOwner,
      Instant executionHeartbeat) {
    Lease validationLease() {
      return new Lease(
          run.getRunId(),
          gateKey,
          continuationPayload,
          payloadSha256,
          handlerContractVersion,
          attempt,
          maxAttempts,
          executionOwner,
          null);
    }
  }

  record ContinuationPayload(String payload, String payloadSha256, int handlerContractVersion) {}

  static final class PoisonMessageException extends RuntimeException {
    PoisonMessageException(String message) {
      super(message);
    }
  }

  static final class StaleLeaseException extends RuntimeException {
    StaleLeaseException(String message) {
      super(message);
    }
  }
}
