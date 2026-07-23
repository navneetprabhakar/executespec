package com.navneet.executespec.integration;

import java.time.Duration;
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
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    var routeContext = com.navneet.executespec.router.RunModelOverrides.capture();
    AtomicReference<ScheduledFuture<?>> timeoutTaskRef = new AtomicReference<>();
    Future<?> future =
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
                            }
                            ScheduledFuture<?> timeoutTask = timeoutTaskRef.get();
                            if (timeoutTask != null) {
                              timeoutTask.cancel(false);
                            }
                          }
                        })));

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
