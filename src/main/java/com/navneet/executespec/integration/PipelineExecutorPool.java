package com.navneet.executespec.integration;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * The V2 run-orchestration thread pool, extracted from {@link RunOrchestrator} (god-class split,
 * increment 4b — audit #43). A dedicated fixed pool sized {@code clamp(4, 16, cores × 1)} so a
 * many-core box can't launch enough concurrent run pipelines to saturate Hikari + the LLM provider
 * rate limits; each run may also fan out parallel task expansion (LARGE planner skeleton-expand × N
 * + EPIC sub-expansions) on it.
 *
 * <p>Behaviour is byte-identical to the {@code private final ExecutorService pool} field it
 * replaced — same sizing, the same daemon {@code v2-orchestrator-N} threads, and the same 30s
 * {@code @PreDestroy} graceful shutdown (SIGTERM must not abandon in-flight runs after credits were
 * charged at plan approval). Making it a bean (rather than a field) lets the planning / lifecycle
 * methods that fan out on it move out of the god-class into their own collaborators without
 * threading the {@link ExecutorService} through every constructor.
 */
@Component
@Slf4j
public class PipelineExecutorPool {

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

  /**
   * v2.5.12 — graceful shutdown. Without this, SIGTERM on the JVM abandons in-flight runs
   * mid-planning/execution after credits were already charged at plan approval. 30 s grace window
   * matches Kubernetes default {@code terminationGracePeriodSeconds}.
   */
  @PreDestroy
  public void shutdownPool() {
    log.info("PipelineExecutorPool shutting down");
    // Shut the expansion tiers down first: an orchestration thread may still be waiting on them.
    shutdownGracefully(nestedExpansionPool, "nested-expansion");
    shutdownGracefully(expansionPool, "expansion");
    shutdownGracefully(pool, "orchestration");
  }

  private static void shutdownGracefully(ExecutorService executor, String label) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn(
            "PipelineExecutorPool {} pool did not terminate in 30s; forcing shutdownNow", label);
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }
}
