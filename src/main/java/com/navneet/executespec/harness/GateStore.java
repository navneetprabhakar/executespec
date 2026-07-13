package com.navneet.executespec.harness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Write-through gate store for plan-approval and merge-approval gates.
 *
 * <h3>Design: Durable database state with Redis signal/cleanup fallback</h3>
 *
 * <p>The production plan gate is a persisted {@code AWAITING_APPROVAL} state. Approval queues a
 * fresh continuation from the durable run row, so no executor thread waits for user input. Redis is
 * retained only as a short-lived cross-instance signal/cleanup aid; PostgreSQL ({@code
 * gate_record}) is authoritative.
 *
 * <h3>Protocol</h3>
 *
 * <pre>
 * REST endpoint (any instance):
 *   setPlanGate(runId, approved)    →  writes Redis + upserts the durable gate row
 * </pre>
 *
 * <h3>Server restart recovery</h3>
 *
 * <p>After a restart, Redis keys may persist (4-hour TTL), while {@code gate_record} rows persist
 * indefinitely and are used to reconstruct the continuation.
 */
@Component
@Slf4j
public class GateStore {

  public enum PlanGateClaim {
    CLAIMED,
    ALREADY_APPROVED,
    ALREADY_REJECTED
  }

  private static final Duration GATE_TTL = Duration.ofHours(4); // increased from 2h

  private final StringRedisTemplate redis;
  private final GateRepository gateRepository;
  private final Counter redisFailureCounter;

  public GateStore(
      StringRedisTemplate redis, GateRepository gateRepository, MeterRegistry meterRegistry) {
    this.redis = redis;
    this.gateRepository = gateRepository;
    this.redisFailureCounter =
        Counter.builder("executespec.redis.connection_failures")
            .description("Redis connection failures in GateStore")
            .register(meterRegistry);
  }

  // Signal (write-through: in-memory + Redis)

  /**
   * Idempotently record that a plan gate is now open for a run (PENDING in DB). Called by the
   * orchestrator when the run transitions to AWAITING_APPROVAL so that {@link #tryClaimPlanGate}
   * has a row to conditionally update. Safe to call multiple times; a no-op if the row already
   * exists.
   */
  public void openPlanGate(String runId) {
    openPlanGate(runId, null);
  }

  /**
   * Open a durable plan gate and persist the data needed to resume after approval. The continuation
   * payload lives in {@link GateRecord#getComment()} so it survives a JVM restart without adding a
   * second gate-state store.
   */
  public void openPlanGate(String runId, String continuationPayload) {
    String key = planKey(runId);
    try {
      GateRecord record = gateRepository.findById(key).orElse(null);
      if (record == null) {
        record = GateRecord.pending(key, runId, "PLAN");
        record.setComment(continuationPayload);
        gateRepository.save(record);
      } else if ("PENDING".equals(record.getStatus())
          && continuationPayload != null
          && !continuationPayload.equals(record.getComment())) {
        record.setComment(continuationPayload);
        gateRepository.save(record);
      }
    } catch (Exception ex) {
      // Lost a race against another thread inserting the same row — that's fine;
      // duplicate-key just means the row already exists.
      log.debug("[GateStore] openPlanGate upsert race for run={}: {}", runId, ex.getMessage());
    }
  }

  /**
   * Atomically claim the plan-approval gate. Returns {@code true} if the caller won the race (and
   * the gate was signalled on Redis + in-memory future); {@code false} if another caller already
   * resolved the gate.
   *
   * <p>Closes adversarial-test-report-2026-04-18 Finding 4: before this method, two concurrent
   * {@code POST /api/runs/{id}/approve} calls both returned 200 with the gate state written twice.
   * Now the loser cleanly returns 409.
   *
   * <p>Relies on the pipeline thread having called {@link #openPlanGate} when entering
   * AWAITING_APPROVAL. If the row is missing (e.g. old runs that pre-date this change, or manual DB
   * cleanup), falls back to {@link #setPlanGate} idempotency — still safe, just without the
   * loser-409 surface.
   */
  public boolean tryClaimPlanGate(String runId, boolean approved) {
    return claimPlanGate(runId, approved) == PlanGateClaim.CLAIMED;
  }

  /**
   * Atomically resolve a plan gate while distinguishing an idempotent repeat from a conflicting
   * decision. Database failure is fail-closed: execution must never start from a non-atomic
   * best-effort gate write.
   */
  public PlanGateClaim claimPlanGate(String runId, boolean approved) {
    String key = planKey(runId);
    int updated;
    try {
      updated =
          gateRepository.tryResolveIfPending(
              key, approved ? "APPROVED" : "REJECTED", Instant.now());
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Unable to atomically resolve plan gate for run " + runId, ex);
    }
    if (updated == 0) {
      boolean missing = gateRepository.findById(key).isEmpty();
      if (missing) {
        // No pending row — either opening-gate was skipped (legacy run) or race
        // with an INSERT. Insert-then-claim, matching the pre-existing idempotent
        // behaviour rather than blocking the caller.
        openPlanGate(runId);
        try {
          updated =
              gateRepository.tryResolveIfPending(
                  key, approved ? "APPROVED" : "REJECTED", Instant.now());
        } catch (Exception ex) {
          throw new IllegalStateException(
              "Unable to atomically resolve newly opened plan gate for run " + runId, ex);
        }
        if (updated == 0) return resolvedClaim(key);
      } else {
        return resolvedClaim(key);
      }
    }
    // We won the race — publish a short-lived signal for compatibility observers.
    try {
      redis.opsForValue().set(key, approved ? "1" : "0", GATE_TTL);
    } catch (Exception ex) {
      log.warn("[GateStore] Redis write failed after claim for run={}: {}", runId, ex.getMessage());
      redisFailureCounter.increment();
    }
    return PlanGateClaim.CLAIMED;
  }

  private PlanGateClaim resolvedClaim(String key) {
    GateRecord existing = gateRepository.findById(key).orElse(null);
    if (existing != null && "APPROVED".equals(existing.getStatus())) {
      return PlanGateClaim.ALREADY_APPROVED;
    }
    if (existing != null && "REJECTED".equals(existing.getStatus())) {
      return PlanGateClaim.ALREADY_REJECTED;
    }
    throw new IllegalStateException("Plan gate is not pending or durably resolved: " + key);
  }

  public Optional<GateRecord> findPlanGate(String runId) {
    if (runId == null || runId.isBlank()) return Optional.empty();
    return gateRepository.findById(planKey(runId));
  }

  /**
   * Signals the plan-approval gate for a run. Completes the in-memory future (instant for
   * same-instance) and writes to Redis (cross-instance fallback for MIG).
   */
  public void setPlanGate(String runId, boolean approved) {
    // Redis (cross-instance signal)
    try {
      redis.opsForValue().set(planKey(runId), approved ? "1" : "0", GATE_TTL);
    } catch (Exception ex) {
      log.warn(
          "[GateStore] Redis write failed for plan gate run={}, falling back to DB only: {}",
          runId,
          ex.getMessage());
      redisFailureCounter.increment();
    }
    // DB (durable fallback — upsert resolution)
    try {
      String key = planKey(runId);
      GateRecord rec = gateRepository.findById(key).orElse(GateRecord.pending(key, runId, "PLAN"));
      rec.setStatus(approved ? "APPROVED" : "REJECTED");
      rec.setResolvedAt(Instant.now());
      gateRepository.save(rec);
    } catch (Exception ex) {
      log.warn("[GateStore] DB write failed for plan gate run={}: {}", runId, ex.getMessage());
    }
  }

  /** Removes the transient plan gate signal for a run (idempotent). */
  public void clearPlanGate(String runId) {
    redis.delete(planKey(runId));
  }

  private static String planKey(String runId) {
    return "gate:plan:" + runId;
  }
}
