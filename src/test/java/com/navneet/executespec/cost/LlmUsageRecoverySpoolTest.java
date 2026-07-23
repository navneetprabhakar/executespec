package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.observability.Metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;

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

  @Test
  void spoolUsesOwnerOnlyModesAndInvalidPayloadMovesToDeadLetter() throws Exception {
    LlmUsageTerminalOutbox terminalOutbox = org.mockito.Mockito.mock(LlmUsageTerminalOutbox.class);
    Metrics metrics = org.mockito.Mockito.mock(Metrics.class);
    LlmUsageRecoverySpool spool =
        new LlmUsageRecoverySpool(
            terminalOutbox,
            new ObjectMapper().findAndRegisterModules(),
            metrics,
            temporaryDirectory.toString());

    assertThat(spool.enqueue(usage())).isTrue();
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      assertThat(Files.getPosixFilePermissions(temporaryDirectory))
          .isEqualTo(PosixFilePermissions.fromString("rwx------"));
      Path payload;
      try (var files = Files.list(temporaryDirectory)) {
        payload = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
      }
      assertThat(Files.getPosixFilePermissions(payload))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
      Files.writeString(payload, "{invalid-json", java.nio.charset.StandardCharsets.UTF_8);
    } else {
      Path payload;
      try (var files = Files.list(temporaryDirectory)) {
        payload = files.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
      }
      Files.writeString(payload, "{invalid-json", java.nio.charset.StandardCharsets.UTF_8);
    }

    spool.replayPending();

    assertThat(jsonFileCount()).isZero();
    try (var files = Files.list(temporaryDirectory)) {
      assertThat(files.filter(path -> path.toString().endsWith(".dead")).count()).isEqualTo(1);
    }
    verify(metrics).recordLlmUsagePersistence("RECOVERY", "dead_letter");
  }

  @Test
  void spoolLogsDoNotExposeAbsoluteWorkspacePathsOrExceptionStacks() {
    LlmUsageTerminalOutbox terminalOutbox = org.mockito.Mockito.mock(LlmUsageTerminalOutbox.class);
    Metrics metrics = org.mockito.Mockito.mock(Metrics.class);
    LlmUsageRecoverySpool spool =
        new LlmUsageRecoverySpool(
            terminalOutbox,
            new ObjectMapper().findAndRegisterModules(),
            metrics,
            temporaryDirectory.toString());
    Logger logger = (Logger) LoggerFactory.getLogger(LlmUsageRecoverySpool.class);
    ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThat(spool.enqueue(usage())).isTrue();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(appender.list)
        .allSatisfy(
            event -> {
              assertThat(event.getFormattedMessage())
                  .doesNotContain(temporaryDirectory.toString(), "usage-recovery/");
              assertThat(event.getThrowableProxy()).isNull();
            });
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
