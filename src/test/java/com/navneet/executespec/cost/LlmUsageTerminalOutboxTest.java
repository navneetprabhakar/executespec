package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.observability.Metrics;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;

class LlmUsageTerminalOutboxTest {

  private final LlmUsageTerminalOutboxStore store =
      org.mockito.Mockito.mock(LlmUsageTerminalOutboxStore.class);
  private final Metrics metrics = org.mockito.Mockito.mock(Metrics.class);
  private final LlmUsageTerminalOutbox outbox = new LlmUsageTerminalOutbox(store, metrics, 20, 120);

  @Test
  void synchronousPathStagesBeforeMaterializing() {
    RunStepUsage row = usage("attempt-1");
    var lease = lease(row);
    when(store.stage(row)).thenReturn(LlmUsageTerminalOutboxStore.StageResult.STAGED);
    when(store.claimOne(eq("attempt-1"), anyString(), eq(Duration.ofSeconds(120))))
        .thenReturn(Optional.of(lease));
    when(store.materialize(lease, row)).thenReturn(true);

    assertThat(outbox.persist(row)).isEqualTo(LlmUsageTerminalOutbox.PersistResult.MATERIALIZED);

    verify(store).stage(row);
    verify(store).materialize(lease, row);
    verify(metrics).recordLlmUsagePersistence("SUCCESS", "outbox_materialized");
  }

  @Test
  void canonicalWriteFailureLeavesExactDatabasePayloadQueued() {
    RunStepUsage row = usage("attempt-2");
    var lease = lease(row);
    when(store.stage(row)).thenReturn(LlmUsageTerminalOutboxStore.StageResult.STAGED);
    when(store.claimOne(eq("attempt-2"), anyString(), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    when(store.materialize(lease, row)).thenThrow(new IllegalStateException("ledger unavailable"));
    when(store.retry(eq(lease), any(), any(Duration.class))).thenReturn(true);

    assertThat(outbox.persist(row)).isEqualTo(LlmUsageTerminalOutbox.PersistResult.DURABLY_QUEUED);

    verify(store).retry(eq(lease), any(IllegalStateException.class), any(Duration.class));
    verify(metrics).recordLlmUsagePersistence("SUCCESS", "outbox_retry");
  }

  @Test
  void repeatedNonInvalidFailureIsDeadLetteredAtConfiguredCeiling() {
    RunStepUsage row = usage("attempt-poison");
    var lease =
        new LlmUsageTerminalOutboxStore.Lease(
            row.getPhysicalAttemptId(),
            "{}",
            "a".repeat(64),
            2,
            "node-a",
            2,
            Instant.now().plusSeconds(120));
    when(store.stage(row)).thenReturn(LlmUsageTerminalOutboxStore.StageResult.STAGED);
    when(store.claimOne(eq("attempt-poison"), anyString(), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    when(store.materialize(lease, row)).thenThrow(new IllegalStateException("repeatable poison"));
    LlmUsageTerminalOutbox bounded = new LlmUsageTerminalOutbox(store, metrics, 20, 120, 2);

    assertThat(bounded.persist(row)).isEqualTo(LlmUsageTerminalOutbox.PersistResult.DURABLY_QUEUED);

    verify(store).deadLetter(eq(lease), any(IllegalStateException.class));
    verify(store, never()).retry(eq(lease), any(), any(Duration.class));
    verify(metrics).recordLlmUsagePersistence("RECOVERY", "dead_letter");
  }

  @Test
  void recoveryLogsNeverExposeExceptionMessagesOrStacks() {
    RunStepUsage row = usage("attempt-secret-log");
    var lease = lease(row);
    when(store.stage(row)).thenReturn(LlmUsageTerminalOutboxStore.StageResult.STAGED);
    when(store.claimOne(eq("attempt-secret-log"), anyString(), any(Duration.class)))
        .thenReturn(Optional.of(lease));
    when(store.materialize(lease, row))
        .thenThrow(new IllegalStateException("SECRET_CANARY_PROVIDER_OR_SQL_PAYLOAD"));
    when(store.retry(eq(lease), any(), any(Duration.class))).thenReturn(true);
    Logger logger = (Logger) LoggerFactory.getLogger(LlmUsageTerminalOutbox.class);
    ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      outbox.persist(row);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(appender.list)
        .allSatisfy(
            event -> {
              assertThat(event.getFormattedMessage())
                  .doesNotContain("SECRET_CANARY_PROVIDER_OR_SQL_PAYLOAD");
              assertThat(event.getThrowableProxy()).isNull();
            });
  }

  @Test
  void anotherJvmCanReplayClaimedDatabasePayload() {
    RunStepUsage row = usage("attempt-3");
    var lease = lease(row);
    when(store.claimDue(anyString(), eq(20), eq(Duration.ofSeconds(120))))
        .thenReturn(List.of(lease));
    when(store.deserialize(lease)).thenReturn(row);
    when(store.materialize(lease, row)).thenReturn(true);

    outbox.replayPending();

    verify(store).materialize(lease, row);
    verify(metrics).recordLlmUsagePersistence("SUCCESS", "outbox_materialized");
  }

  private static LlmUsageTerminalOutboxStore.Lease lease(RunStepUsage row) {
    return new LlmUsageTerminalOutboxStore.Lease(
        row.getPhysicalAttemptId(),
        "{}",
        "a".repeat(64),
        1,
        "node-a",
        1,
        Instant.now().plusSeconds(120));
  }

  static RunStepUsage usage(String attemptId) {
    return RunStepUsage.builder()
        .id(attemptId)
        .runId("run-1")
        .taskId("task-1")
        .agentRole("WorkerAgent")
        .stepId("worker.iteration")
        .llmExecutionContextId(null)
        .correlationId("corr-1")
        .orgId("org-1")
        .userId("user-1")
        .projectId("project-1")
        .feature("WORKER")
        .sourceType("PIPELINE_RUN")
        .sourceId("run-1")
        .keySource("ORG")
        .attemptStatus("SUCCESS")
        .attemptNumber(1)
        .latencyMs(12)
        .provider("deepseek")
        .model("deepseek-v4")
        .modelTier("CAPABLE")
        .logicalCallId("logical-1")
        .physicalAttemptId(attemptId)
        .attemptKind("PRIMARY")
        .attemptOrdinal(1)
        .inputTokens(10)
        .outputTokens(5)
        .cacheWriteTokens(1)
        .cacheReadTokens(2)
        .reasoningTokens(3)
        .costUsd(0.01)
        .callIndex(1)
        .createdAt(Instant.parse("2026-07-18T12:00:00Z"))
        .build();
  }
}
