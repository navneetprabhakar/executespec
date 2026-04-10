package com.navneet.executespec.harness;

import com.navneet.executespec.harness.entity.GateRecord;
import com.navneet.executespec.harness.repository.GateRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Write-through gate store for plan-approval and merge-approval gates.
 *
 * <h3>Design: In-memory + Redis write-through + DB fallback</h3>
 * <p>Uses in-memory {@link CompletableFuture} maps as the <b>primary</b> signaling
 * mechanism (instant, zero-latency notification on the same instance), Redis as a
 * <b>cross-instance fallback</b> for MIG deployments, and PostgreSQL ({@code gate_record})
 * as a <b>durable fallback</b> when Redis is unavailable.
 *
 * <h3>Protocol</h3>
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
 * <p>After a restart, in-memory futures are lost but Redis keys persist (4-hour TTL) and
 * {@code gate_record} rows persist indefinitely. {@link #consumeMergeGateIfPending} first
 * tries Redis, then falls back to DB.
 */
@Component
@Slf4j
public class GateStore {

    private static final Duration GATE_TTL       = Duration.ofHours(4);   // increased from 2h
    private static final Duration MAX_GATE_WAIT  = Duration.ofHours(24);
    private static final long    POLL_MS         = 500;

    private final StringRedisTemplate redis;
    private final GateRepository gateRepository;
    private final Counter redisFailureCounter;
    private final Counter dbFallbackCounter;

    public GateStore(StringRedisTemplate redis,
                     GateRepository gateRepository,
                     MeterRegistry meterRegistry) {
        this.redis = redis;
        this.gateRepository = gateRepository;
        this.redisFailureCounter = Counter.builder("executespec.redis.connection_failures")
                .description("Redis connection failures in GateStore")
                .register(meterRegistry);
        this.dbFallbackCounter = Counter.builder("executespec.gate.db_fallback")
                .description("Gate resolutions served from DB fallback (Redis unavailable)")
                .register(meterRegistry);
    }

    // In-memory write-through maps — instant same-instance resolution
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>>        planFutures  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<MergeDecision>>  mergeFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>>         clarificationFutures = new ConcurrentHashMap<>();

    // ── Signal (write-through: in-memory + Redis) ────────────────────────────────

    /**
     * Signals the plan-approval gate for a run.
     * Completes the in-memory future (instant for same-instance) and writes to Redis
     * (cross-instance fallback for MIG).
     */
    public void setPlanGate(String runId, boolean approved) {
        // Redis (cross-instance signal)
        try {
            redis.opsForValue().set(planKey(runId), approved ? "1" : "0", GATE_TTL);
        } catch (Exception ex) {
            log.warn("[GateStore] Redis write failed for plan gate run={}, falling back to DB only: {}", runId, ex.getMessage());
            redisFailureCounter.increment();
        }
        // DB (durable fallback — upsert resolution)
        try {
            String key = planKey(runId);
            GateRecord rec = gateRepository.findById(key)
                    .orElse(GateRecord.pending(key, runId, "PLAN"));
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
            log.debug("[GateStore] Plan gate resolved via in-memory future for run {}: {}", runId, approved);
        }
    }

    /**
     * Signals the merge-approval gate for a run.
     * Completes the in-memory future (instant for same-instance) and writes to Redis
     * (cross-instance fallback for MIG).
     */
    public void setMergeGate(String runId, MergeDecision decision) {
        // Redis (cross-instance signal)
        try {
            redis.opsForValue().set(mergeKey(runId), decision.name(), GATE_TTL);
        } catch (Exception ex) {
            log.warn("[GateStore] Redis write failed for merge gate run={}: {}", runId, ex.getMessage());
            redisFailureCounter.increment();
        }
        // DB (durable fallback)
        try {
            String key = mergeKey(runId);
            GateRecord rec = gateRepository.findById(key)
                    .orElse(GateRecord.pending(key, runId, "MERGE"));
            rec.setStatus(decision.name());
            rec.setResolvedAt(Instant.now());
            gateRepository.save(rec);
        } catch (Exception ex) {
            log.warn("[GateStore] DB write failed for merge gate run={}: {}", runId, ex.getMessage());
        }
        // In-memory (instant same-instance)
        CompletableFuture<MergeDecision> future = mergeFutures.remove(runId);
        if (future != null) {
            future.complete(decision);
            log.debug("[GateStore] Merge gate resolved via in-memory future for run {}: {}", runId, decision);
        }
    }

    // ── Await (in-memory future primary, Redis polling fallback) ─────────────────

    /**
     * Blocks the calling (pipeline) thread until the plan-approval gate is signaled.
     * Registers an in-memory future for instant same-instance notification, with periodic
     * Redis polling as a cross-instance fallback.
     */
    public boolean awaitPlanGate(String runId) throws InterruptedException {
        CompletableFuture<Boolean> future = planFutures.computeIfAbsent(runId, k -> new CompletableFuture<>());
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
                    log.warn("[GateStore] Redis unavailable during plan gate poll for run {}, checking DB", runId);
                    redisFailureCounter.increment();
                    // DB fallback
                    try {
                        GateRecord rec = gateRepository.findById(planKey(runId)).orElse(null);
                        if (rec != null && rec.isResolved()) {
                            boolean approved = "APPROVED".equals(rec.getStatus());
                            planFutures.remove(runId);
                            dbFallbackCounter.increment();
                            log.info("[GateStore] Plan gate resolved via DB fallback for run {}: {}", runId, approved);
                            return approved;
                        }
                    } catch (Exception dbEx) {
                        log.warn("[GateStore] DB fallback also failed for plan gate run={}: {}", runId, dbEx.getMessage());
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
        throw new InterruptedException("Plan gate timed out after " + MAX_GATE_WAIT.toHours() + "h for run " + runId);
    }

    /**
     * Blocks the calling (pipeline) thread until the merge-approval gate is signaled.
     * Registers an in-memory future for instant same-instance notification, with periodic
     * Redis polling as a cross-instance fallback.
     */
    public MergeDecision awaitMergeGate(String runId) throws InterruptedException {
        CompletableFuture<MergeDecision> future = mergeFutures.computeIfAbsent(runId, k -> new CompletableFuture<>());
        log.debug("[GateStore] Awaiting merge gate for run {} (in-memory + Redis fallback)", runId);

        long deadline = System.currentTimeMillis() + MAX_GATE_WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                MergeDecision decision = future.get(POLL_MS, TimeUnit.MILLISECONDS);
                mergeFutures.remove(runId);
                redis.delete(mergeKey(runId)); // Clean up Redis key (written before future was completed)
                log.debug("[GateStore] Merge gate resolved via in-memory for run {}: {}", runId, decision);
                return decision;
            } catch (TimeoutException e) {
                String val = null;
                try {
                    val = redis.opsForValue().getAndDelete(mergeKey(runId));
                } catch (RedisConnectionFailureException redisEx) {
                    log.warn("[GateStore] Redis unavailable during merge gate poll for run {}, checking DB", runId);
                    redisFailureCounter.increment();
                    try {
                        GateRecord rec = gateRepository.findById(mergeKey(runId)).orElse(null);
                        if (rec != null && rec.isResolved()) {
                            MergeDecision decision = MergeDecision.valueOf(rec.getStatus());
                            mergeFutures.remove(runId);
                            dbFallbackCounter.increment();
                            log.info("[GateStore] Merge gate resolved via DB fallback for run {}: {}", runId, decision);
                            return decision;
                        }
                    } catch (Exception dbEx) {
                        log.warn("[GateStore] DB fallback also failed for merge gate run={}: {}", runId, dbEx.getMessage());
                    }
                }
                if (val != null) {
                    MergeDecision decision = MergeDecision.valueOf(val);
                    mergeFutures.remove(runId);
                    log.debug("[GateStore] Merge gate resolved via Redis for run {}: {}", runId, decision);
                    return decision;
                }
            } catch (ExecutionException | CancellationException e) {
                mergeFutures.remove(runId);
                throw new InterruptedException("Merge gate failed for run " + runId + ": " + e.getMessage());
            }
            if (Thread.currentThread().isInterrupted()) {
                mergeFutures.remove(runId);
                throw new InterruptedException("Merge gate interrupted for run " + runId);
            }
        }
        mergeFutures.remove(runId);
        throw new InterruptedException("Merge gate timed out after " + MAX_GATE_WAIT.toHours() + "h for run " + runId);
    }

    // ── Fallback (gate-loss detection after server restart) ──────────────────────

    /**
     * Atomically checks and consumes a merge gate key if it is still pending in Redis.
     * Used by the gate-loss fallback in {@code PipelineHarness.resolveMergeGate()}:
     * after a server restart, in-memory futures are lost and no thread is polling Redis.
     * This method detects and consumes the orphaned key so a recovery thread can handle it.
     *
     * @return the orphaned {@link MergeDecision}, or {@code null} if no key was pending
     */
    public MergeDecision consumeMergeGateIfPending(String runId) {
        try {
            String val = redis.opsForValue().getAndDelete(mergeKey(runId));
            if (val != null) {
                log.info("[GateStore] Consumed orphaned merge gate for run {}: {}", runId, val);
                return MergeDecision.valueOf(val);
            }
        } catch (RedisConnectionFailureException ex) {
            log.warn("[GateStore] Redis unavailable in consumeMergeGateIfPending for run {}, checking DB", runId);
            redisFailureCounter.increment();
            try {
                GateRecord rec = gateRepository.findById(mergeKey(runId)).orElse(null);
                if (rec != null && rec.isResolved()) {
                    dbFallbackCounter.increment();
                    log.info("[GateStore] Consumed orphaned merge gate via DB for run {}: {}", runId, rec.getStatus());
                    return MergeDecision.valueOf(rec.getStatus());
                }
            } catch (Exception dbEx) {
                log.warn("[GateStore] DB fallback failed in consumeMergeGateIfPending run={}: {}", runId, dbEx.getMessage());
            }
        }
        return null;
    }

    // ── Context Clarification gate ─────────────────────────────────────────────

    /**
     * Signals the context-clarification gate for a run.
     * The response string contains the user's clarification text (e.g., a JIRA key or Confluence URL).
     */
    public void setClarificationGate(String runId, String response) {
        try {
            redis.opsForValue().set(clarificationKey(runId), response != null ? response : "", GATE_TTL);
        } catch (Exception ex) {
            log.warn("[GateStore] Redis write failed for clarification gate run={}: {}", runId, ex.getMessage());
            redisFailureCounter.increment();
        }
        // DB persistence (clarification text stored as status)
        try {
            String key = clarificationKey(runId);
            GateRecord rec = gateRepository.findById(key)
                    .orElse(GateRecord.pending(key, runId, "CLARIFICATION"));
            rec.setStatus(response != null && !response.isBlank() ? "RESOLVED" : "SKIPPED");
            rec.setComment(response);
            rec.setResolvedAt(Instant.now());
            gateRepository.save(rec);
        } catch (Exception ex) {
            log.warn("[GateStore] DB write failed for clarification gate run={}: {}", runId, ex.getMessage());
        }
        CompletableFuture<String> future = clarificationFutures.remove(runId);
        if (future != null) {
            future.complete(response);
            log.debug("[GateStore] Clarification gate resolved via in-memory future for run {}", runId);
        }
    }

    /**
     * Blocks the calling (pipeline) thread until the clarification gate is signaled.
     * Returns the user's clarification response string (may be empty if user skipped).
     */
    public String awaitClarificationGate(String runId) throws InterruptedException {
        CompletableFuture<String> future = clarificationFutures.computeIfAbsent(runId, k -> new CompletableFuture<>());
        log.debug("[GateStore] Awaiting clarification gate for run {} (in-memory + Redis fallback)", runId);

        long deadline = System.currentTimeMillis() + MAX_GATE_WAIT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                String response = future.get(POLL_MS, TimeUnit.MILLISECONDS);
                clarificationFutures.remove(runId);
                redis.delete(clarificationKey(runId));
                log.debug("[GateStore] Clarification gate resolved via in-memory for run {}", runId);
                return response;
            } catch (TimeoutException e) {
                String val = redis.opsForValue().getAndDelete(clarificationKey(runId));
                if (val != null) {
                    clarificationFutures.remove(runId);
                    log.debug("[GateStore] Clarification gate resolved via Redis for run {}", runId);
                    return val;
                }
            } catch (ExecutionException | CancellationException e) {
                clarificationFutures.remove(runId);
                throw new InterruptedException("Clarification gate failed for run " + runId + ": " + e.getMessage());
            }
            if (Thread.currentThread().isInterrupted()) {
                clarificationFutures.remove(runId);
                throw new InterruptedException("Clarification gate interrupted for run " + runId);
            }
        }
        clarificationFutures.remove(runId);
        throw new InterruptedException("Clarification gate timed out after " + MAX_GATE_WAIT.toHours() + "h for run " + runId);
    }

    /** Removes the clarification gate key and in-memory future for a run (idempotent). */
    public void clearClarificationGate(String runId) {
        CompletableFuture<String> f = clarificationFutures.remove(runId);
        if (f != null) f.cancel(false);
        redis.delete(clarificationKey(runId));
    }

    // ── Cleanup (on run cancellation / hard failure) ─────────────────────────────

    /** Removes the plan gate key and in-memory future for a run (idempotent). */
    public void clearPlanGate(String runId) {
        CompletableFuture<Boolean> f = planFutures.remove(runId);
        if (f != null) f.cancel(false);
        redis.delete(planKey(runId));
    }

    /** Removes the merge gate key and in-memory future for a run (idempotent). */
    public void clearMergeGate(String runId) {
        CompletableFuture<MergeDecision> f = mergeFutures.remove(runId);
        if (f != null) f.cancel(false);
        redis.delete(mergeKey(runId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String planKey(String runId)           { return "gate:plan:"          + runId; }
    private static String mergeKey(String runId)          { return "gate:merge:"         + runId; }
    private static String clarificationKey(String runId)  { return "gate:clarification:" + runId; }
}
