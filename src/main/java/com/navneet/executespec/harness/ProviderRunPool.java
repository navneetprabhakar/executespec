package com.navneet.executespec.harness;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.state.StateStore;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-provider isolated run pool with priority queuing.
 *
 * <p>Enforces a concurrency limit per LLM provider so that one slow or
 * rate-limited provider cannot starve runs on other providers. Uses a
 * {@link PriorityBlockingQueue} to dispatch ENTERPRISE &gt; PRO &gt; FREE runs first.
 *
 * <p>Dispatch threads use virtual threads so the blocking {@code queue.take()}
 * and {@code semaphore.acquire()} calls do not consume platform threads.
 *
 * <p>The pool does NOT manage the global semaphore — that remains in
 * {@link PipelineHarness}. The global slot must be acquired before calling
 * {@link #submit} and released (by the task Runnable itself) after the run
 * finishes execution.
 */
@Slf4j
public class ProviderRunPool {

    private final LlmProvider provider;
    private final Semaphore providerSlots;
    private final PriorityBlockingQueue<ProviderQueueEntry> queue;
    private final StateStore stateStore;
    private final AtomicInteger activeCount = new AtomicInteger(0);
    private final AtomicInteger queuedCount = new AtomicInteger(0);
    private final int queueCapacity;
    private volatile boolean shutdown = false;

    public ProviderRunPool(LlmProvider provider, int concurrency, int queueCapacity, StateStore stateStore) {
        this.provider = provider;
        this.queueCapacity = queueCapacity;
        this.providerSlots = new Semaphore(concurrency);
        this.queue = new PriorityBlockingQueue<>(Math.max(queueCapacity, 1));
        this.stateStore = stateStore;

        // Start dispatch virtual threads (one per concurrency slot)
        for (int i = 0; i < concurrency; i++) {
            Thread.ofVirtual()
                  .name("provider-pool-" + provider.name().toLowerCase() + "-" + i)
                  .start(this::dispatchLoop);
        }

        log.info("ProviderRunPool[{}] started — concurrency={}, queueCapacity={}",
                provider, concurrency, queueCapacity);
    }

    /**
     * Submits a run to this provider's queue.
     *
     * @param runId       the run ID (used for cancellation check)
     * @param userTier    subscription tier (ENTERPRISE/PRO/FREE) for priority ordering
     * @param submittedAt the time the run was submitted (for FIFO within same tier)
     * @param task        the Runnable to execute (must release global run slot when done)
     * @throws ProviderQueueFullException if the queue is at capacity
     */
    public void submit(String runId, String userTier, Instant submittedAt, Runnable task) {
        if (queuedCount.get() >= queueCapacity) {
            throw new ProviderQueueFullException(provider, queueCapacity);
        }
        ProviderQueueEntry entry = new ProviderQueueEntry(
                runId,
                ProviderQueueEntry.tierToPriority(userTier),
                submittedAt,
                task
        );
        queue.offer(entry);
        queuedCount.incrementAndGet();
        log.debug("ProviderRunPool[{}] queued run {} (tier={}, queueDepth={})",
                provider, runId, userTier, queuedCount.get());
    }

    /** Number of runs currently executing in this pool. */
    public int activeRuns() {
        return activeCount.get();
    }

    /** Number of runs waiting in the priority queue. */
    public int queueDepth() {
        return queuedCount.get();
    }

    /**
     * Returns the 1-based queue position of a run, or -1 if not found.
     * Position is approximate (concurrent changes may affect ordering).
     */
    public int queuePositionFor(String runId) {
        int pos = 1;
        for (ProviderQueueEntry entry : queue) {
            if (entry.runId().equals(runId)) return pos;
            pos++;
        }
        return -1;
    }

    /** Configured concurrency (maximum simultaneous active runs). */
    public int concurrency() {
        return providerSlots.availablePermits() + activeCount.get();
    }

    /** Initiates graceful shutdown of dispatch threads. */
    public void shutdown() {
        shutdown = true;
        // Poison pill entries to unblock take()
        for (int i = 0; i < concurrency(); i++) {
            queue.offer(new ProviderQueueEntry(
                    "__shutdown__", Integer.MAX_VALUE, Instant.now(), () -> {}));
        }
        log.info("ProviderRunPool[{}] shutdown initiated", provider);
    }

    private void dispatchLoop() {
        while (!shutdown) {
            try {
                ProviderQueueEntry entry = queue.take();
                if ("__shutdown__".equals(entry.runId())) break;

                queuedCount.decrementAndGet();

                // Skip cancelled runs — check before acquiring provider slot
                if (isCancelled(entry.runId())) {
                    log.info("ProviderRunPool[{}] skipping cancelled run {}", provider, entry.runId());
                    continue;
                }

                providerSlots.acquire();
                activeCount.incrementAndGet();
                log.debug("ProviderRunPool[{}] dispatching run {} (active={})",
                        provider, entry.runId(), activeCount.get());

                try {
                    entry.task().run();
                } catch (Exception e) {
                    log.error("ProviderRunPool[{}] task failed for run {}: {}",
                            provider, entry.runId(), e.getMessage(), e);
                } finally {
                    activeCount.decrementAndGet();
                    providerSlots.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("ProviderRunPool[{}] dispatch thread exiting", provider);
    }

    private boolean isCancelled(String runId) {
        try {
            return stateStore.findRun(runId)
                    .map(r -> "CANCELLED".equals(r.getStatus()))
                    .orElse(false);
        } catch (Exception e) {
            log.debug("Could not check cancellation status for run {}: {}", runId, e.getMessage());
            return false;
        }
    }

    /**
     * Thrown when the provider's queue is at capacity and cannot accept new runs.
     */
    public static class ProviderQueueFullException extends RuntimeException {
        public ProviderQueueFullException(LlmProvider provider, int capacity) {
            super("Provider " + provider + " queue is full (capacity=" + capacity +
                    "). Try again shortly or switch providers.");
        }
    }
}
