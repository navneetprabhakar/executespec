package com.navneet.executespec.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs optional HTTP test generation outside the terminal run-finalization thread.
 *
 * <p>Test generation performs provider calls and may retry them. It therefore must not hold the run
 * pipeline open indefinitely. A bounded interrupt is used as a safety valve; the test generator
 * remains responsible for publishing its normal test events when it exits normally.
 */
@Component
@Slf4j
public class BoundedTestGenKickoff {

  private final SseBroadcaster broadcaster;
  private final Duration timeout;
  private final ExecutorService workerExecutor;
  private final ScheduledExecutorService timeoutExecutor;
  private final Map<String, Instant> activeRuns = new ConcurrentHashMap<>();

  @Autowired
  public BoundedTestGenKickoff(
      SseBroadcaster broadcaster,
      @Value("${test-generation.timeout-seconds:600}") long timeoutSeconds) {
    this(
        broadcaster,
        Duration.ofSeconds(Math.max(1L, timeoutSeconds)),
        Executors.newVirtualThreadPerTaskExecutor(),
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("testgen-timeout-", 0).factory()));
  }

  BoundedTestGenKickoff(
      SseBroadcaster broadcaster,
      Duration timeout,
      ExecutorService workerExecutor,
      ScheduledExecutorService timeoutExecutor) {
    this.broadcaster = broadcaster;
    this.timeout = timeout;
    this.workerExecutor = workerExecutor;
    this.timeoutExecutor = timeoutExecutor;
  }

  /** Starts test generation and returns immediately to run finalization. */
  public void kickoff(String runId, Runnable pipeline) {
    kickoff(runId, pipeline, () -> {});
  }

  /** Starts test generation and owns run-scoped cleanup after completion or interruption. */
  public void kickoff(String runId, Runnable pipeline, Runnable cleanup) {
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("test generation requires a run id");
    }
    Instant retentionDeadline = Instant.now().plus(timeout);
    if (activeRuns.putIfAbsent(runId, retentionDeadline) != null) {
      throw new IllegalStateException("test generation is already active for run " + runId);
    }
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    var routeContext = com.navneet.executespec.router.RunModelOverrides.capture();
    AtomicReference<ScheduledFuture<?>> timeoutTaskRef = new AtomicReference<>();
    Future<?> future;
    try {
      future =
          workerExecutor.submit(
              snapshot.wrap(
                  () ->
                      com.navneet.executespec.router.RunModelOverrides.runWith(
                          routeContext,
                          () -> {
                            try {
                              pipeline.run();
                            } catch (Throwable t) {
                              log.error(
                                  "[TestGen] bounded kickoff failed for run {}: {}",
                                  runId,
                                  t.getMessage(),
                                  t);
                            } finally {
                              try {
                                cleanup.run();
                              } catch (Throwable cleanupFailure) {
                                log.warn(
                                    "[TestGen] cleanup failed for run {}: {}",
                                    runId,
                                    cleanupFailure.getMessage());
                              } finally {
                                // The terminal sandbox listener may release only after this
                                // optional pipeline can no longer dispatch another command.
                                activeRuns.remove(runId);
                              }
                              ScheduledFuture<?> timeoutTask = timeoutTaskRef.get();
                              if (timeoutTask != null) {
                                timeoutTask.cancel(false);
                              }
                            }
                          })));
    } catch (RuntimeException rejected) {
      activeRuns.remove(runId);
      throw rejected;
    }

    ScheduledFuture<?> timeoutTask =
        timeoutExecutor.schedule(
            () -> {
              if (!future.isDone()) {
                future.cancel(true);
                String message =
                    "Test generation exceeded "
                        + timeoutSeconds(timeout)
                        + " seconds and was interrupted; run finalization is preserved";
                log.warn("[TestGen] run={} {}", runId, message);
                broadcaster.emit(runId, RunEvent.log(runId, null, "[TestGen] " + message));
              }
            },
            timeout.toMillis(),
            TimeUnit.MILLISECONDS);
    timeoutTaskRef.set(timeoutTask);
    // A very fast pipeline can finish before the scheduled task is published above. Cancel it
    // after publication as well; this avoids retaining one timer per completed run until the full
    // timeout elapses.
    if (future.isDone()) {
      timeoutTask.cancel(false);
    }
  }

  /**
   * Whether bounded post-run validation may still issue a sandbox command for this run.
   *
   * <p>This is a retention fence, not an unbounded lifetime extension: the marker expires at the
   * same deadline even if an upstream client ignores interruption, and normal completion removes it
   * earlier in {@code finally}.
   */
  public boolean isActive(String runId) {
    if (runId == null) {
      return false;
    }
    Instant deadline = activeRuns.get(runId);
    if (deadline == null) {
      return false;
    }
    if (!Instant.now().isBefore(deadline)) {
      activeRuns.remove(runId, deadline);
      return false;
    }
    return true;
  }

  private static long timeoutSeconds(Duration timeout) {
    long millis = timeout.toMillis();
    return Math.max(1L, (millis + 999L) / 1_000L);
  }

  @PreDestroy
  void shutdown() {
    workerExecutor.shutdownNow();
    timeoutExecutor.shutdownNow();
  }
}
