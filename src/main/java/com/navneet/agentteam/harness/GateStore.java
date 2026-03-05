package com.navneet.agentteam.harness;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed gate store for plan-approval and merge-approval gates.
 *
 * <h3>Why this exists</h3>
 * <p>In a Managed Instance Group (MIG) with 2+ GCE VMs, the user's approval/merge
 * REST call may land on a different instance than the one running the pipeline thread.
 * The old approach (in-memory {@link java.util.concurrent.CompletableFuture} maps) would
 * silently fail in that scenario. This class replaces those maps with Redis keys so the
 * signal reaches the correct pipeline thread regardless of which instance handles the
 * HTTP request.
 *
 * <h3>Protocol</h3>
 * <pre>
 * REST endpoint (any instance):
 *   gateStore.setPlanGate(runId, approved)    →  SET gate:plan:{runId}  "1"/"0"  EX 7200
 *   gateStore.setMergeGate(runId, decision)   →  SET gate:merge:{runId} "MERGE"  EX 7200
 *
 * Pipeline thread (same instance the run started on, polls every 500 ms):
 *   boolean approved  = gateStore.awaitPlanGate(runId)    →  blocks until Redis key exists
 *   MergeDecision dec = gateStore.awaitMergeGate(runId)   →  blocks until Redis key exists
 * </pre>
 *
 * <h3>TTL</h3>
 * Keys have a 2-hour TTL. If a gate is set but the pipeline thread is gone (instance
 * crashed), the key expires harmlessly. The {@code StuckRunMonitor} handles crash recovery
 * independently via the existing {@code performMergeOnRestart} path.
 *
 * <h3>Authentication</h3>
 * Uses Application Default Credentials on GCE (no manual config). For local dev,
 * start Redis: {@code docker run -d -p 6379:6379 redis:7-alpine} and set
 * {@code REDIS_HOST=localhost}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GateStore {

    private static final Duration GATE_TTL = Duration.ofHours(2);
    private static final long    POLL_MS   = 500;

    private final StringRedisTemplate redis;

    // ── Signal (called by REST endpoint on ANY instance) ─────────────────────────

    /**
     * Signals the plan-approval gate for a run.
     * Sets a Redis key that the pipeline thread's polling loop will detect within ~500 ms.
     *
     * @param runId    the pipeline run ID
     * @param approved {@code true} = approved, {@code false} = rejected
     */
    public void setPlanGate(String runId, boolean approved) {
        redis.opsForValue().set(planKey(runId), approved ? "1" : "0", GATE_TTL);
        log.debug("[GateStore] Plan gate set for run {}: {}", runId, approved);
    }

    /**
     * Signals the merge-approval gate for a run.
     * Sets a Redis key that the pipeline thread's polling loop will detect within ~500 ms.
     *
     * @param runId    the pipeline run ID
     * @param decision the merge decision
     */
    public void setMergeGate(String runId, MergeDecision decision) {
        redis.opsForValue().set(mergeKey(runId), decision.name(), GATE_TTL);
        log.debug("[GateStore] Merge gate set for run {}: {}", runId, decision);
    }

    // ── Await (called by pipeline thread — blocks until signal arrives) ───────────

    /**
     * Blocks the calling (pipeline) thread until the plan-approval gate is signaled.
     * Polls Redis every {@value POLL_MS} ms. Respects thread interruption.
     *
     * @param runId the pipeline run ID
     * @return {@code true} if approved, {@code false} if rejected
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public boolean awaitPlanGate(String runId) throws InterruptedException {
        log.debug("[GateStore] Pipeline thread polling plan gate for run {}", runId);
        while (true) {
            String val = redis.opsForValue().getAndDelete(planKey(runId));
            if (val != null) {
                boolean approved = "1".equals(val);
                log.debug("[GateStore] Plan gate resolved for run {}: {}", runId, approved);
                return approved;
            }
            Thread.sleep(POLL_MS);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Plan gate polling interrupted for run " + runId);
            }
        }
    }

    /**
     * Blocks the calling (pipeline) thread until the merge-approval gate is signaled.
     * Polls Redis every {@value POLL_MS} ms. Respects thread interruption.
     *
     * @param runId the pipeline run ID
     * @return the {@link MergeDecision} set by the approving user
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public MergeDecision awaitMergeGate(String runId) throws InterruptedException {
        log.debug("[GateStore] Pipeline thread polling merge gate for run {}", runId);
        while (true) {
            String val = redis.opsForValue().getAndDelete(mergeKey(runId));
            if (val != null) {
                MergeDecision decision = MergeDecision.valueOf(val);
                log.debug("[GateStore] Merge gate resolved for run {}: {}", runId, decision);
                return decision;
            }
            Thread.sleep(POLL_MS);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Merge gate polling interrupted for run " + runId);
            }
        }
    }

    // ── Cleanup (on run cancellation / hard failure) ──────────────────────────────

    /** Removes the plan gate key for a run (idempotent). */
    public void clearPlanGate(String runId) {
        redis.delete(planKey(runId));
    }

    /** Removes the merge gate key for a run (idempotent). */
    public void clearMergeGate(String runId) {
        redis.delete(mergeKey(runId));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String planKey(String runId)  { return "gate:plan:"  + runId; }
    private static String mergeKey(String runId) { return "gate:merge:" + runId; }
}
