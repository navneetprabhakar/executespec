package com.navneet.executespec.sandboxroute;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.extern.slf4j.Slf4j;

/** Dedicated liveness clock: slow janitor/billing jobs must not starve verification ownership. */
@Component
@Slf4j
final class VerificationLeaseHeartbeat {
  private final SandboxCommandRoutingService routing;
  private final long intervalMs;
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon(true).name("verification-lease-heartbeat").factory());

  VerificationLeaseHeartbeat(
      SandboxCommandRoutingService routing,
      @Value("${executespec.sandbox.verification-heartbeat-interval-ms:10000}") long intervalMs) {
    if (intervalMs < 1 || intervalMs > 30_000) {
      timer.shutdownNow();
      throw new IllegalArgumentException("Verification heartbeat interval must be 1..30000 ms");
    }
    this.routing = routing;
    this.intervalMs = intervalMs;
  }

  @PostConstruct
  void start() {
    timer.scheduleWithFixedDelay(
        ContextSnapshotFactory.builder()
            .build()
            .captureAll()
            .wrap(
                () -> {
                  try {
                    routing.renewVerificationSessions();
                  } catch (RuntimeException failure) {
                    log.error(
                        "Verification lease heartbeat failed; later ticks will retry", failure);
                  }
                }),
        intervalMs,
        intervalMs,
        TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void close() {
    timer.shutdownNow();
    try {
      if (!timer.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Verification heartbeat did not quiesce before shutdown deadline");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while stopping verification heartbeat", interrupted);
    }
  }
}
