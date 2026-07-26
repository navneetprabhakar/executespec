package com.navneet.executespec.integration;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The V2 run-orchestration thread pool, extracted from {@link RunOrchestrator} (god-class split,
 * increment 4b — audit #43). A dedicated fixed pool sized {@code clamp(4, 16, cores × 1)} so a
 * many-core box can't launch enough concurrent run pipelines to saturate Hikari + the LLM provider
 * rate limits; each run may also fan out parallel task expansion (LARGE planner skeleton-expand × N
 * + EPIC sub-expansions) on it.
 *
 * <p>It retains the original sizing and daemon {@code v2-orchestrator-N} threads. As a
 * maximum-phase {@link SmartLifecycle}, it interrupts active work before Redis/JPA destruction so a
 * deployment stops provider spend and leaves durable WORKING checkpoints for crash recovery. Making
 * it a bean (rather than a field) also lets planning/lifecycle collaborators share the executors
 * without threading the {@link ExecutorService} through every constructor.
 */
@Component
@Slf4j
public class PipelineExecutorPool implements SmartLifecycle {

  /** Keep Spring infrastructure alive within systemd's 90-second TimeoutStopSec. */
  private static final long TOTAL_STOP_WAIT_SECONDS = 60;

  private static ExecutorService fixedDaemonPool(String namePrefix, int size) {
    return Executors.newFixedThreadPool(
        size,
        new ThreadFactory() {
          private int n = 0;

          @Override
          public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + (++n));
            t.setDaemon(true);
            return t;
          }
        });
  }

  private final ExecutorService pool =
      fixedDaemonPool(
          "v2-orchestrator-", com.navneet.executespec.util.PoolSizing.coreMultiple(1, 4, 16));

  /**
   * RR-442 — level-1 plan-expansion pool, deliberately SEPARATE from {@link #pool}.
   *
   * <p>A LARGE/LEGACY run runs its planning phase on an orchestration thread and then blocks up to
   * 20 minutes waiting for every skeleton task to be expanded. When those children were submitted
   * back to the same fixed 4–16-thread orchestration pool, four concurrent LARGE runs on a
   * minimum-sized pool occupied every worker while each waited for children that could never be
   * scheduled — a hard deadlock that only unwound at the timeout, after credits were charged and
   * provider spend had happened, while starving unrelated lifecycle work on the same executor.
   *
   * <p>The invariant now enforced by construction: <b>no thread ever blocks on work submitted to
   * the executor it is itself running on.</b>
   */
  private final ExecutorService expansionPool =
      fixedDaemonPool(
          "v2-plan-expand-", com.navneet.executespec.util.PoolSizing.coreMultiple(1, 4, 16));

  /**
   * RR-442 — level-2 (EPIC sub-expansion) pool. An EPIC expander runs on {@link #expansionPool} and
   * blocks awaiting its own children, so those grandchildren need a third, distinct executor for
   * the same reason. Plan expansion recurses exactly two levels (skeleton → EPIC children), so two
   * expansion pools are sufficient; any deeper recursion must add its own tier rather than reuse
   * one of these.
   */
  private final ExecutorService nestedExpansionPool =
      fixedDaemonPool(
          "v2-plan-expand-nested-", com.navneet.executespec.util.PoolSizing.coreMultiple(1, 4, 16));

  private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
  private volatile boolean running = true;

  /** Submit a fire-and-forget pipeline task; the returned {@link Future} is the cancel handle. */
  public Future<?> submit(Runnable task) {
    return pool.submit(task);
  }

  /** Submit a value-returning task. */
  public <T> Future<T> submit(Callable<T> task) {
    return pool.submit(task);
  }

  /**
   * The raw executor — for callers that fan out their own bounded parallelism on the shared pool
   * (e.g. {@code CompletableFuture.runAsync(..., executor())} in the LARGE-pipeline expansion).
   */
  public ExecutorService executor() {
    return pool;
  }

  /**
   * RR-442 — the executor for level-1 plan expansion. Callers waiting on these futures must be
   * running on {@link #executor()}, never on this pool.
   */
  public ExecutorService expansionExecutor() {
    return expansionPool;
  }

  /**
   * RR-442 — the executor for level-2 (EPIC child) expansion. Callers waiting on these futures must
   * be running on {@link #expansionExecutor()}, never on this pool.
   */
  public ExecutorService nestedExpansionExecutor() {
    return nestedExpansionPool;
  }

  /** True once lifecycle shutdown begins; run owners use this to preserve resumable state. */
  public boolean isShuttingDown() {
    return shuttingDown.get();
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public boolean isRunning() {
    return running && !shuttingDown.get();
  }

  @Override
  public boolean isAutoStartup() {
    return true;
  }

  /**
   * Stop before persistence/Redis infrastructure. A maximum lifecycle phase is stopped first by
   * Spring, while those dependencies are still alive. Active pipeline threads are interrupted
   * immediately so they stop provider spend, unwind without terminalizing the run, and leave their
   * durable WORKING checkpoint for {@code StartupRunResumer} in the replacement process.
   */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void stop(Runnable callback) {
    try {
      stop();
    } finally {
      callback.run();
    }
  }

  @Override
  public void stop() {
    if (!shuttingDown.compareAndSet(false, true)) return;
    running = false;
    log.info("PipelineExecutorPool stopping before infrastructure shutdown");
    // Signal every tier before awaiting any one of them. Waiting first allowed workers in another
    // tier to keep making provider calls while the application was already closing.
    nestedExpansionPool.shutdownNow();
    expansionPool.shutdownNow();
    pool.shutdownNow();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_STOP_WAIT_SECONDS);
    awaitStopped(nestedExpansionPool, "nested-expansion", deadline);
    awaitStopped(expansionPool, "expansion", deadline);
    awaitStopped(pool, "orchestration", deadline);
  }

  /** PreDestroy is an idempotent fallback for contexts that bypass lifecycle stop callbacks. */
  @PreDestroy
  public void shutdownPool() {
    stop();
  }

  private static void awaitStopped(ExecutorService executor, String label, long deadlineNanos) {
    try {
      long remaining = deadlineNanos - System.nanoTime();
      if (remaining <= 0 || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
        log.warn(
            "PipelineExecutorPool {} pool did not terminate within the {}s total stop window",
            label,
            TOTAL_STOP_WAIT_SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
