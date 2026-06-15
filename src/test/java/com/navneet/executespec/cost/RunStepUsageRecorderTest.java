package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunStepUsageRecorderTest {

  private final RunStepUsageRepository repository =
      org.mockito.Mockito.mock(RunStepUsageRepository.class);
  private final RunStepUsageRecorder recorder = new RunStepUsageRecorder(repository);

  @Test
  @DisplayName("records role/step/retry attribution with per-call usage")
  void recordsAttribution() {
    RunCostTracker.CallUsage usage =
        new RunCostTracker.CallUsage("deepseek", "deepseek-v4", 11, 7, 3, 5, 0.0123, 4, 2);
    when(repository.save(any(RunStepUsage.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    recorder.record(
        "run-1",
        "task-1",
        "WorkerAgent",
        "worker.iteration-3",
        "WorkerAgent:worker.iteration-3#attempt-1",
        "",
        null,
        usage);

    ArgumentCaptor<RunStepUsage> captor = ArgumentCaptor.forClass(RunStepUsage.class);
    verify(repository).save(captor.capture());
    RunStepUsage row = captor.getValue();
    assertThat(row.getRunId()).isEqualTo("run-1");
    assertThat(row.getTaskId()).isEqualTo("task-1");
    assertThat(row.getAgentRole()).isEqualTo("WorkerAgent");
    assertThat(row.getStepId()).isEqualTo("worker.iteration-3");
    assertThat(row.getRetryOf()).isEqualTo("WorkerAgent:worker.iteration-3#attempt-1");
    assertThat(row.getProvider()).isEqualTo("deepseek");
    assertThat(row.getModel()).isEqualTo("deepseek-v4");
    assertThat(row.getInputTokens()).isEqualTo(11);
    assertThat(row.getOutputTokens()).isEqualTo(7);
    assertThat(row.getCacheWriteTokens()).isEqualTo(3);
    assertThat(row.getCacheReadTokens()).isEqualTo(5);
    assertThat(row.getReasoningTokens()).isEqualTo(2);
    assertThat(row.getCostUsd()).isEqualTo(0.0123);
    assertThat(row.getCallIndex()).isEqualTo(4);
    assertThat(row.getCreatedAt()).isNotNull();
    assertThat(row.getId()).isNotBlank();
  }

  @Test
  @DisplayName("skips empty run ids and null usage")
  void skipsIncompleteInput() {
    recorder.record("", "task-1", "WorkerAgent", "worker.iteration-1", null, "openai", "gpt", null);
    recorder.record(
        null, "task-1", "WorkerAgent", "worker.iteration-1", null, "openai", "gpt", null);

    verify(repository, never()).save(any());
  }
}
