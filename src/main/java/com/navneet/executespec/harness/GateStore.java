package com.navneet.executespec.harness;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.*;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Write-through gate store for plan-approval and merge-approval gates.
 *
 * <h3>Design: In-memory + Redis write-through + DB fallback</h3>
 *
 * <p>Uses in-memory {@link CompletableFuture} maps as the <b>primary</b> signaling mechanism
 * (instant, zero-latency notification on the same instance), Redis as a <b>cross-instance
 * fallback</b> for MIG deployments, and PostgreSQL ({@code gate_record}) as a <b>durable
 * fallback</b> when Redis is unavailable.
 *
 * <h3>Protocol</h3>
 *
 * <pre>
 * Pipeline thread (registers before waiting):
 *   awaitPlanGate(runId)   →  registers CompletableFuture, then waits on it
 *                             with periodic Redis polling as cross-instance fallback;
 *                             on Redis failure, falls back to DB read
 *   awaitMergeGate(runId)  →  same pattern
 *
 * REST endpoint (any instance):
 *   setPlanGate(runId, approved)    →  completes in-memory future (instant) + SET Redis + upsert DB
 *   setMergeGate(runId, decision)   →  completes in-memory future (instant) + SET Redis + upsert DB
 * </pre>
 *
 * <h3>Server restart recovery</h3>
 *
 * <p>After a restart, in-memory futures are lost but Redis keys persist (4-hour TTL) and {@code
 * gate_record} rows persist indefinitely. {@link #consumeMergeGateIfPending} first tries Redis,
 * then falls back to DB.
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
  private static final Duration MAX_GATE_WAIT = Duration.ofHours(24);
  private static final long POLL_MS = 500;

  private final StringRedisTemplate redis;
  private final GateRepository gateRepository;
  private final Counter redisFailureCounter;
  private final Counter dbFallbackCounter;

  public GateStore(
      StringRedisTemplate redis, GateRepository gateRepository, MeterRegistry meterRegistry) {
    this.redis = redis;
    this.gateRepository = gateRepository;
    this.redisFailureCounter =
        Counter.builder("executespec.redis.connection_failures")
            .description("Redis connection failures in GateStore")
            .register(meterRegistry);
    this.dbFallbackCounter =
        Counter.builder("executespec.gate.db_fallback")
            .description("Gate resolutions served from DB fallback (Redis unavailable)")
            .register(meterRegistry);
  }

  // In-memory write-through map — instant same-instance resolution.
  // V1 merge + clarification gates retired; V2 only uses the plan gate.
  private final ConcurrentHashMap<String, CompletableFuture<Boolean>> planFutures =
      new ConcurrentHashMap<>();

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
    // We won the race — propagate via Redis + in-memory future.
    try {
      redis.opsForValue().set(key, approved ? "1" : "0", GATE_TTL);
    } catch (Exception ex) {
      log.warn("[GateStore] Redis write failed after claim for run={}: {}", runId, ex.getMessage());
      redisFailureCounter.increment();
    }
    CompletableFuture<Boolean> future = planFutures.remove(runId);
    if (future != null) {
      future.complete(approved);
      log.debug(
          "[GateStore] Plan gate resolved via in-memory future for run {}: {}", runId, approved);
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
    // In-memory (instant same-instance)
    CompletableFuture<Boolean> future = planFutures.remove(runId);
    if (future != null) {
      future.complete(approved);
      log.debug(
          "[GateStore] Plan gate resolved via in-memory future for run {}: {}", runId, approved);
    }
  }

  // Await (in-memory future primary, Redis polling fallback)

  /**
   * Blocks the calling (pipeline) thread until the plan-approval gate is signaled. Registers an
   * in-memory future for instant same-instance notification, with periodic Redis polling as a
   * cross-instance fallback.
   */
  public boolean awaitPlanGate(String runId) throws InterruptedException {
    CompletableFuture<Boolean> future =
        planFutures.computeIfAbsent(runId, k -> new CompletableFuture<>());
    log.debug("[GateStore] Awaiting plan gate for run {} (in-memory + Redis fallback)", runId);

    long deadline = System.currentTimeMillis() + MAX_GATE_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      try {
        // Wait on in-memory future with timeout — instant for same-instance
        boolean approved = future.get(POLL_MS, TimeUnit.MILLISECONDS);
        planFutures.remove(runId);
        redis.delete(planKey(runId)); // Clean up Redis key (written before future was completed)
        log.debug("[GateStore] Plan gate resolved via in-memory for run {}: {}", runId, approved);
        return approved;
      } catch (TimeoutException e) {
        // Check Redis as cross-instance fallback; on Redis failure, try DB
        String val = null;
        try {
          val = redis.opsForValue().getAndDelete(planKey(runId));
        } catch (RedisConnectionFailureException redisEx) {
          log.warn(
              "[GateStore] Redis unavailable during plan gate poll for run {}, checking DB", runId);
          redisFailureCounter.increment();
          // DB fallback
          try {
            GateRecord rec = gateRepository.findById(planKey(runId)).orElse(null);
            if (rec != null && rec.isResolved()) {
              boolean approved = "APPROVED".equals(rec.getStatus());
              planFutures.remove(runId);
              dbFallbackCounter.increment();
              log.info(
                  "[GateStore] Plan gate resolved via DB fallback for run {}: {}", runId, approved);
              return approved;
            }
          } catch (Exception dbEx) {
            log.warn(
                "[GateStore] DB fallback also failed for plan gate run={}: {}",
                runId,
                dbEx.getMessage());
          }
        }
        if (val != null) {
          boolean approved = "1".equals(val);
          planFutures.remove(runId);
          log.debug("[GateStore] Plan gate resolved via Redis for run {}: {}", runId, approved);
          return approved;
        }
      } catch (ExecutionException | CancellationException e) {
        planFutures.remove(runId);
        throw new InterruptedException("Plan gate failed for run " + runId + ": " + e.getMessage());
      }
      if (Thread.currentThread().isInterrupted()) {
        planFutures.remove(runId);
        throw new InterruptedException("Plan gate interrupted for run " + runId);
      }
    }
    planFutures.remove(runId);
    throw new InterruptedException(
        "Plan gate timed out after " + MAX_GATE_WAIT.toHours() + "h for run " + runId);
  }

  // Cleanup (on run cancellation / hard failure)

  /**
   * True if a pipeline thread is currently waiting on this run's plan gate on THIS instance. Used
   * by the approve endpoint to detect the "server restarted, no thread waiting" case and trigger a
   * continuation.
   */
  public boolean isPlanGateWaiting(String runId) {
    return runId != null && planFutures.containsKey(runId);
  }

  /** Removes the plan gate key and in-memory future for a run (idempotent). */
  public void clearPlanGate(String runId) {
    CompletableFuture<Boolean> f = planFutures.remove(runId);
    if (f != null) f.cancel(false);
    redis.delete(planKey(runId));
  }

  private static String planKey(String runId) {
    return "gate:plan:" + runId;
  }
}
