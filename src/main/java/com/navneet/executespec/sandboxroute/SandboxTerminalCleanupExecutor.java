package com.navneet.executespec.sandboxroute;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Explicit bounded executor for event-driven sandbox teardown.
 *
 * <p>Terminal event publication stays synchronous and lightweight: it only admits a cleanup task
 * into this two-worker, bounded queue. Docker teardown never occupies the pipeline-finalization
 * thread, and queue rejection is safe because the durable scheduled sweep retries the same
 * idempotent cleanup from the database.
 */
@Component
@Slf4j
public class SandboxTerminalCleanupExecutor {

  private static final int WORKERS = 2;
  private static final int QUEUE_CAPACITY = 256;

  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          WORKERS,
          WORKERS,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(QUEUE_CAPACITY),
          Thread.ofVirtual().name("sandbox-terminal-cleanup-", 0).factory(),
          new ThreadPoolExecutor.AbortPolicy());

  /** Returns false when the bounded queue is full; the durable sweep remains the retry path. */
  public boolean submit(String runId, Runnable cleanup) {
    try {
      executor.execute(cleanup);
      return true;
    } catch (RejectedExecutionException rejected) {
      log.warn(
          "sandbox terminal cleanup queue is full for run {}; durable sweep will retry", runId);
      return false;
    }
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }
}
