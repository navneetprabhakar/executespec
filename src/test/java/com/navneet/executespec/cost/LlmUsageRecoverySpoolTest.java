package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class LlmUsageRecoverySpoolTest {

  @TempDir Path temporaryDirectory;

  @Test
  void atomicallySpoolsAndReplaysExactTerminalFact() throws Exception {
    LlmUsageTerminalOutbox terminalOutbox = org.mockito.Mockito.mock(LlmUsageTerminalOutbox.class);
    Metrics metrics = org.mockito.Mockito.mock(Metrics.class);
    when(terminalOutbox.persist(any(RunStepUsage.class)))
        .thenReturn(LlmUsageTerminalOutbox.PersistResult.MATERIALIZED);
    LlmUsageRecoverySpool spool =
        new LlmUsageRecoverySpool(
            terminalOutbox,
            new ObjectMapper().findAndRegisterModules(),
            metrics,
            temporaryDirectory.toString());
    RunStepUsage row = usage();

    assertThat(spool.enqueue(row)).isTrue();
    assertThat(jsonFileCount()).isEqualTo(1);

    // Reconstruct the component to model a JVM restart: recovery must rely only on the durable
    // filesystem record, not in-memory queue state from the writer instance.
    LlmUsageRecoverySpool restarted =
        new LlmUsageRecoverySpool(
            terminalOutbox,
            new ObjectMapper().findAndRegisterModules(),
            metrics,
            temporaryDirectory.toString());
    restarted.replayPending();

    ArgumentCaptor<RunStepUsage> captor = ArgumentCaptor.forClass(RunStepUsage.class);
    verify(terminalOutbox).persist(captor.capture());
    assertThat(captor.getValue()).usingRecursiveComparison().isEqualTo(row);
    assertThat(jsonFileCount()).isZero();
    verify(metrics).recordLlmUsagePersistence("SUCCESS", "spooled");
    verify(metrics).recordLlmUsagePersistence("SUCCESS", "replayed");
  }

  @Test
  void failedReplayRemainsDurableForNextSweep() throws Exception {
    LlmUsageTerminalOutbox terminalOutbox = org.mockito.Mockito.mock(LlmUsageTerminalOutbox.class);
    Metrics metrics = org.mockito.Mockito.mock(Metrics.class);
    when(terminalOutbox.persist(any(RunStepUsage.class)))
        .thenThrow(new IllegalStateException("database still unavailable"));
    LlmUsageRecoverySpool spool =
        new LlmUsageRecoverySpool(
            terminalOutbox,
            new ObjectMapper().findAndRegisterModules(),
            metrics,
            temporaryDirectory.toString());

    assertThat(spool.enqueue(usage())).isTrue();
    spool.replayPending();

    assertThat(jsonFileCount()).isEqualTo(1);
    verify(metrics).recordLlmUsagePersistence("RECOVERY", "retry_failed");
  }

  private long jsonFileCount() throws Exception {
    try (var paths = Files.list(temporaryDirectory)) {
      return paths.filter(path -> path.getFileName().toString().endsWith(".json")).count();
    }
  }

  private static RunStepUsage usage() {
    return RunStepUsage.builder()
        .id("logical-1:deepseek:1")
        .runId("run-1")
        .taskId("task-1")
        .agentRole("WorkerAgent")
        .stepId("worker.iteration")
        .llmExecutionContextId("ctx-1")
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
        .physicalAttemptId("logical-1:deepseek:1")
        .attemptKind("PRIMARY")
        .attemptOrdinal(1)
        .inputTokens(10)
        .outputTokens(5)
        .reasoningTokens(2)
        .costUsd(0.01)
        .callIndex(1)
        .createdAt(Instant.parse("2026-07-17T15:00:00Z"))
        .build();
  }
}
