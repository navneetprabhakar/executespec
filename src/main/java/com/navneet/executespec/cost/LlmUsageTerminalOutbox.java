package com.navneet.executespec.cost;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Duration;
import java.util.UUID;

import com.navneet.executespec.observability.Metrics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** Cross-JVM terminal-usage materializer backed by the V298 PostgreSQL outbox. */
@Component
@Slf4j
public class LlmUsageTerminalOutbox {

  public enum PersistResult {
    MATERIALIZED,
    DURABLY_QUEUED
  }

  private final LlmUsageTerminalOutboxStore store;
  private final Metrics metrics;
  private final String owner;
  private final int batchSize;
  private final Duration leaseDuration;
  private final int maxAttempts;

  @Autowired
  public LlmUsageTerminalOutbox(
      LlmUsageTerminalOutboxStore store,
      Metrics metrics,
      @Value("${executespec.usage-terminal-outbox.batch-size:20}") int batchSize,
      @Value("${executespec.usage-terminal-outbox.lease-seconds:120}") long leaseSeconds,
      @Value("${executespec.usage-terminal-outbox.max-attempts:8}") int maxAttempts) {
    this.store = store;
    this.metrics = metrics;
    this.batchSize = Math.max(1, Math.min(batchSize, 100));
    this.leaseDuration = Duration.ofSeconds(Math.max(30, Math.min(leaseSeconds, 3600)));
    this.maxAttempts = Math.max(1, Math.min(maxAttempts, 100));
    this.owner = nodeIdentity() + ":" + UUID.randomUUID();
  }

  /** Test/backward-compatible constructor retaining the production default retry ceiling. */
  LlmUsageTerminalOutbox(
      LlmUsageTerminalOutboxStore store, Metrics metrics, int batchSize, long leaseSeconds) {
    this(store, metrics, batchSize, leaseSeconds, 8);
  }

  /** Stage before materialization; returning QUEUED still means the exact payload is DB durable. */
  public PersistResult persist(RunStepUsage row) {
    store.stage(row);
    var lease = store.claimOne(row.getPhysicalAttemptId(), owner, leaseDuration);
    if (lease.isEmpty()) {
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "outbox_queued");
      return PersistResult.DURABLY_QUEUED;
    }
    return materialize(lease.get(), row);
  }

  @Scheduled(
      fixedDelayString = "${executespec.usage-terminal-outbox.poll-interval-millis:30000}",
      initialDelayString = "${executespec.usage-terminal-outbox.initial-delay-millis:30000}")
  public void replayPending() {
    try {
      for (var lease : store.claimDue(owner, batchSize, leaseDuration)) {
        try {
          materialize(lease, store.deserialize(lease));
        } catch (IllegalArgumentException poisonPayload) {
          store.deadLetter(lease, poisonPayload);
          metrics.recordLlmUsagePersistence("RECOVERY", "dead_letter");
          log.error(
              "Terminal usage outbox payload rejected attemptId={} errorClass={}",
              lease.physicalAttemptId(),
              poisonPayload.getClass().getSimpleName());
        }
      }
    } catch (RuntimeException claimFailure) {
      metrics.recordLlmUsagePersistence("RECOVERY", "claim_failed");
      log.error(
          "Unable to claim terminal usage recovery work errorClass={}",
          claimFailure.getClass().getSimpleName());
    }
  }

  private PersistResult materialize(LlmUsageTerminalOutboxStore.Lease lease, RunStepUsage row) {
    try {
      if (!store.materialize(lease, row)) {
        throw new IllegalStateException("terminal usage recovery lease was lost");
      }
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "outbox_materialized");
      return PersistResult.MATERIALIZED;
    } catch (LlmUsageTerminalOutboxStore.TerminalUsageConflictException conflict) {
      store.deadLetter(lease, conflict);
      metrics.recordLlmUsagePersistence("RECOVERY", "dead_letter");
      log.error(
          "Terminal usage evidence conflict retained attemptId={} errorClass={}",
          lease.physicalAttemptId(),
          conflict.getClass().getSimpleName());
      return PersistResult.DURABLY_QUEUED;
    } catch (RuntimeException materializationFailure) {
      if (lease.attempt() >= maxAttempts) {
        store.deadLetter(lease, materializationFailure);
        metrics.recordLlmUsagePersistence("RECOVERY", "dead_letter");
        log.error(
            "Canonical terminal usage exhausted recovery attempts attemptId={} attempts={}"
                + " errorClass={}",
            lease.physicalAttemptId(),
            lease.attempt(),
            materializationFailure.getClass().getSimpleName());
        return PersistResult.DURABLY_QUEUED;
      }
      store.retry(lease, materializationFailure, retryDelay(lease.attempt()));
      metrics.recordLlmUsagePersistence(row.getAttemptStatus(), "outbox_retry");
      log.error(
          "Canonical terminal usage remains durably queued attemptId={} errorClass={}",
          lease.physicalAttemptId(),
          materializationFailure.getClass().getSimpleName());
      return PersistResult.DURABLY_QUEUED;
    }
  }

  private static Duration retryDelay(long attempt) {
    long exponent = Math.min(8, Math.max(0, attempt - 1));
    return Duration.ofSeconds(Math.min(300, 2L << exponent));
  }

  private static String nodeIdentity() {
    try {
      return InetAddress.getLocalHost().getHostName()
          + ":"
          + ManagementFactory.getRuntimeMXBean().getName();
    } catch (Exception ignored) {
      return "unknown-node:" + ManagementFactory.getRuntimeMXBean().getName();
    }
  }
}
