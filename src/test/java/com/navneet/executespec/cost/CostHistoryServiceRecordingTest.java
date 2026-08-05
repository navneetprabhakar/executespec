package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CostHistoryServiceRecordingTest {

  private final RunCostHistoryRepository history = mock(RunCostHistoryRepository.class);
  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final RunStepUsageRepository usage = mock(RunStepUsageRepository.class);
  private final CostHistoryService service = new CostHistoryService(history, runs, usage);

  @Test
  void completeRunRecordsActualPlanTaskCount() {
    PipelineRun run = terminal("COMPLETE");
    run.setPlanJson(
        "{\"tasks\":[{\"id\":\"T1\",\"complexity\":\"LOW\"},"
            + "{\"id\":\"T2\",\"complexity\":\"HIGH\"},{\"id\":\"T3\"}]}");
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(usage.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(
            java.util.List.of(
                RunStepUsage.builder()
                    .attemptStatus("SUCCESS")
                    .provider("deepseek")
                    .model("deepseek-v4-pro-native")
                    .build()));

    service.recordOnTerminal("run-1");

    ArgumentCaptor<RunCostHistory> saved = ArgumentCaptor.forClass(RunCostHistory.class);
    verify(history).save(saved.capture());
    assertThat(saved.getValue().getTaskCount()).isEqualTo(3);
    assertThat(saved.getValue().getTaskComplexityFingerprint()).isEqualTo("LOW:1,MEDIUM:1,HIGH:1");
    assertThat(saved.getValue().getStatus()).isEqualTo("COMPLETE");
    assertThat(saved.getValue().getSchemaVersion()).isEqualTo((short) 2);
    assertThat(saved.getValue().getRouteFingerprint()).isEqualTo("DEEPSEEK:deepseek-v4-pro-native");
  }

  @Test
  void completedRunWithoutSuccessfulPhysicalRouteDoesNotEnterHistory() {
    PipelineRun run = terminal("COMPLETE");
    run.setPlanJson("{\"tasks\":[{\"id\":\"T1\",\"complexity\":\"LOW\"}]}");
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(usage.findByRunIdOrderByCreatedAtAsc("run-1")).thenReturn(java.util.List.of());

    service.recordOnTerminal("run-1");

    verify(history, never()).save(any());
  }

  @Test
  void completedRunWithoutReconstructableTaskShapeDoesNotEnterHistory() {
    PipelineRun run = terminal("COMPLETE");
    run.setPlanJson("not-json");
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(usage.findByRunIdOrderByCreatedAtAsc("run-1"))
        .thenReturn(
            java.util.List.of(
                RunStepUsage.builder()
                    .attemptStatus("SUCCESS")
                    .provider("deepseek")
                    .model("deepseek-v4-pro-native")
                    .build()));

    service.recordOnTerminal("run-1");

    verify(history, never()).save(any());
  }

  @Test
  void failedAndPartialRunsDoNotEnterCompletedJourneyHistory() {
    when(runs.findById("failed")).thenReturn(Optional.of(terminal("FAILED")));
    when(runs.findById("partial")).thenReturn(Optional.of(terminal("PARTIAL")));

    service.recordOnTerminal("failed");
    service.recordOnTerminal("partial");

    verify(history, never()).save(any());
  }

  private static PipelineRun terminal(String status) {
    return PipelineRun.builder()
        .runId("run-1")
        .status(status)
        .actualCostUsd(0.02)
        .completedAt(Instant.parse("2026-08-03T00:00:00Z"))
        .build();
  }
}
