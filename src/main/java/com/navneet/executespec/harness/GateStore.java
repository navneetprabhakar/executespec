package com.navneet.executespec.harness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Write-through gate store for plan-approval and merge-approval gates.
 *
 * <h3>Design: In-memory + Redis write-through</h3>
 * <p>Uses in-memory {@link CompletableFuture} maps as the <b>primary</b> signaling
 * mechanism (instant, zero-latency notification on the same instance) and Redis as a
 * <b>cross-instance fallback</b> for MIG deployments where the REST call may land on a
 * different VM than the one running the pipeline thread.
 *
 * <h3>Protocol</h3>
 * <pre>
 * Pipeline thread (registers before waiting):
 *   awaitPlanGate(runId)   →  registers CompletableFuture, then waits on it
 *                             with periodic Redis polling as cross-instance fallback
 *   awaitMergeGate(runId)  →  same pattern
 *
 * REST endpoint (any instance):
 *   setPlanGate(runId, approved)    →  completes in-memory future (instant) + SET Redis
 *   setMergeGate(runId, decision)   →  completes in-memory future (instant) + SET Redis
 * </pre>
 *
 * <h3>Server restart recovery</h3>
 * <p>After a restart, in-memory futures are lost but Redis keys persist (2-hour TTL).
 * {@link #consumeMergeGateIfPending} atomically checks and consumes orphaned Redis keys,
 * used by the gate-loss fallback in {@code PipelineHarness.resolveMergeGate()}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GateStore {

    private static final Duration GATE_TTL = Duration.ofHours(2);
    private static final long    POLL_MS   = 500;

    private final StringRedisTemplate redis;

    // In-memory write-through maps — instant same-instance resolution
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>>        planFutures  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<MergeDecision>>  mergeFutures = new ConcurrentHashMap<>();

    // ── Signal (write-through: in-memory + Redis) ────────────────────────────────

    /**
     * Signals the plan-approval gate for a run.
     * Completes the in-memory future (instant for same-instance) and writes to Redis
     * (cross-instance fallback for MIG).
     */
    public void setPlanGate(String runId, boolean approved) {
        // Redis first (cross-instance), then in-memory (same-instance instant)
        redis.opsForValue().set(planKey(runId), approved ? "1" : "0", GATE_TTL);
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
        // Redis first (cross-instance), then in-memory (same-instance instant)
        redis.opsForValue().set(mergeKey(runId), decision.name(), GATE_TTL);
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

        while (true) {
            try {
                // Wait on in-memory future with timeout — instant for same-instance
                boolean approved = future.get(POLL_MS, TimeUnit.MILLISECONDS);
                planFutures.remove(runId);
                redis.delete(planKey(runId)); // Clean up Redis key (written before future was completed)
                log.debug("[GateStore] Plan gate resolved via in-memory for run {}: {}", runId, approved);
                return approved;
            } catch (TimeoutException e) {
                // Check Redis as cross-instance fallback
                String val = redis.opsForValue().getAndDelete(planKey(runId));
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
    }

    /**
     * Blocks the calling (pipeline) thread until the merge-approval gate is signaled.
     * Registers an in-memory future for instant same-instance notification, with periodic
     * Redis polling as a cross-instance fallback.
     */
    public MergeDecision awaitMergeGate(String runId) throws InterruptedException {
        CompletableFuture<MergeDecision> future = mergeFutures.computeIfAbsent(runId, k -> new CompletableFuture<>());
        log.debug("[GateStore] Awaiting merge gate for run {} (in-memory + Redis fallback)", runId);

        while (true) {
            try {
                MergeDecision decision = future.get(POLL_MS, TimeUnit.MILLISECONDS);
                mergeFutures.remove(runId);
                redis.delete(mergeKey(runId)); // Clean up Redis key (written before future was completed)
                log.debug("[GateStore] Merge gate resolved via in-memory for run {}: {}", runId, decision);
                return decision;
            } catch (TimeoutException e) {
                String val = redis.opsForValue().getAndDelete(mergeKey(runId));
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
        String val = redis.opsForValue().getAndDelete(mergeKey(runId));
        if (val != null) {
            log.info("[GateStore] Consumed orphaned merge gate for run {}: {}", runId, val);
            return MergeDecision.valueOf(val);
        }
        return null;
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

    private static String planKey(String runId)  { return "gate:plan:"  + runId; }
    private static String mergeKey(String runId) { return "gate:merge:" + runId; }
}
