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

  private final ExecutorService pool =
      Executors.newFixedThreadPool(
          com.navneet.executespec.util.PoolSizing.coreMultiple(1, 4, 16),
          new ThreadFactory() {
            private int n = 0;

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "v2-orchestrator-" + (++n));
              t.setDaemon(true);
              return t;
            }
          });

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
   * v2.5.12 — graceful shutdown. Without this, SIGTERM on the JVM abandons in-flight runs
   * mid-planning/execution after credits were already charged at plan approval. 30 s grace window
   * matches Kubernetes default {@code terminationGracePeriodSeconds}.
   */
  @PreDestroy
  public void shutdownPool() {
    log.info("PipelineExecutorPool shutting down");
    pool.shutdown();
    try {
      if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("PipelineExecutorPool did not terminate in 30s; forcing shutdownNow");
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }
}
