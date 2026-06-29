package com.navneet.executespec.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunTokenAttributorTest {

  private static final String RUN_ID = "run-1";
  private static final String UUID_RUN_ID = "12345678-aaaa-bbbb-cccc-123456789abc";

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
  private final RunCostTracker costTracker = mock(RunCostTracker.class);
  private final RunTokenAttributor attributor =
      new RunTokenAttributor(runRepo, taskRepo, costTracker);

  @Test
  void attributePhaseAdditionalAddsToExistingReviewerColumnsAndRunTotalDeltaOnly() {
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .reviewerInputTokens(100)
            .reviewerOutputTokens(20)
            .build();
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

    attributor.attributePhaseAdditional(
        RUN_ID, RunTokenAttributor.Phase.REVIEWER, 30, 7, "ANTHROPIC", "claude-sonnet-4-6");

    ArgumentCaptor<PipelineRun> saved = ArgumentCaptor.forClass(PipelineRun.class);
    verify(runRepo).save(saved.capture());
    verify(runRepo).addTokens(RUN_ID, 30, 7);
    assertThat(saved.getValue().getReviewerInputTokens()).isEqualTo(130);
    assertThat(saved.getValue().getReviewerOutputTokens()).isEqualTo(27);
    assertThat(saved.getValue().getReviewerProvider()).isEqualTo("ANTHROPIC");
    assertThat(saved.getValue().getReviewerActualModel()).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void attributeWorkerAdditionalTaskUpdatesOnlyTargetTaskWithTaskSnapshot() {
    AgentTask task = AgentTask.builder().taskId("12345678_T2").runId(UUID_RUN_ID).build();
    when(taskRepo.findById("12345678_T2")).thenReturn(Optional.of(task));
    when(costTracker.snapshotTask(UUID_RUN_ID, "T2"))
        .thenReturn(new RunCostTracker.Snapshot(42, 9, 0, 0, 0.0, 1));

    attributor.attributeWorkerAdditionalTask(
        UUID_RUN_ID, "T2", 10, 3, "ANTHROPIC", "claude-sonnet-4-6");

    ArgumentCaptor<AgentTask> saved = ArgumentCaptor.forClass(AgentTask.class);
    verify(runRepo).addTokens(UUID_RUN_ID, 10, 3);
    verify(taskRepo).save(saved.capture());
    assertThat(saved.getValue().getTaskId()).isEqualTo("12345678_T2");
    assertThat(saved.getValue().getInputTokens()).isEqualTo(42);
    assertThat(saved.getValue().getOutputTokens()).isEqualTo(9);
    assertThat(saved.getValue().getProvider()).isEqualTo("ANTHROPIC");
    assertThat(saved.getValue().getModel()).isEqualTo("claude-sonnet-4-6");
  }
}
