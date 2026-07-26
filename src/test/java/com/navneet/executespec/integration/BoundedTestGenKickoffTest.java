package com.navneet.executespec.integration;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.Test;

class BoundedTestGenKickoffTest {

  @Test
  void propagatesRouteContextAndRunsOwnedCleanup() throws Exception {
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    CountDownLatch finished = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<String> observed =
        new java.util.concurrent.atomic.AtomicReference<>();
    try {
      RunModelOverrides.bind(java.util.Map.of("deepseek:planning", "deepseek-v4-pro-native"));
      BoundedTestGenKickoff kickoff =
          new BoundedTestGenKickoff(broadcaster, Duration.ofSeconds(1), worker, timer);
      kickoff.kickoff(
          "run-context",
          () ->
              observed.set(
                  RunModelOverrides.lookup(
                      com.navneet.executespec.router.LlmProvider.DEEPSEEK,
                      com.navneet.executespec.router.AgentTier.PLANNING)),
          finished::countDown);

      org.junit.jupiter.api.Assertions.assertTrue(finished.await(1, TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertEquals("deepseek-v4-pro-native", observed.get());
    } finally {
      RunModelOverrides.clear();
      worker.shutdownNow();
      timer.shutdownNow();
    }
  }

  @Test
  void kickoffDoesNotBlockTerminalCaller() throws Exception {
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    try {
      BoundedTestGenKickoff kickoff =
          new BoundedTestGenKickoff(broadcaster, Duration.ofSeconds(1), worker, timer);
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);

      long before = System.nanoTime();
      kickoff.kickoff(
          "run-async",
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);

      // The pipeline deliberately remains blocked; only the kickoff must return promptly.
      org.junit.jupiter.api.Assertions.assertTrue(elapsedMillis < 500);
      org.junit.jupiter.api.Assertions.assertTrue(started.await(1, TimeUnit.SECONDS));
      org.junit.jupiter.api.Assertions.assertTrue(kickoff.isActive("run-async"));
      release.countDown();
      org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
          Duration.ofSeconds(1),
          () -> {
            while (kickoff.isActive("run-async")) {
              Thread.onSpinWait();
            }
          });
      org.junit.jupiter.api.Assertions.assertFalse(kickoff.isActive("run-async"));
    } finally {
      worker.shutdownNow();
      timer.shutdownNow();
    }
  }

  @Test
  void timeoutInterruptsPipelineAndPublishesBoundedFailure() throws Exception {
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    ExecutorService worker = Executors.newSingleThreadExecutor();
    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    try {
      BoundedTestGenKickoff kickoff =
          new BoundedTestGenKickoff(broadcaster, Duration.ofMillis(30), worker, timer);
      CountDownLatch interrupted = new CountDownLatch(1);

      kickoff.kickoff(
          "run-timeout",
          () -> {
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
              interrupted.countDown();
              Thread.currentThread().interrupt();
            }
          });

      org.junit.jupiter.api.Assertions.assertTrue(interrupted.await(1, TimeUnit.SECONDS));
      verify(broadcaster, timeout(1_000))
          .emit(
              eq("run-timeout"),
              argThat(
                  event ->
                      event.type().equals("LOG")
                          && event.payload().contains("exceeded 1 seconds")
                          && event.payload().contains("run finalization is preserved")));
    } finally {
      worker.shutdownNow();
      timer.shutdownNow();
    }
  }
}
