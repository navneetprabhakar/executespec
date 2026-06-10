package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

class RunPersistenceWriterTest {

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
  private final RunCostLogRepository costLogRepo = mock(RunCostLogRepository.class);
  private final SseBroadcaster broadcaster = mock(SseBroadcaster.class);

  private final RunPersistenceWriter writer =
      new RunPersistenceWriter(runRepo, taskRepo, costLogRepo, broadcaster, new ObjectMapper());

  @Test
  void updateStatusDoesNotClobberCancelledRun() {
    PipelineRun run = PipelineRun.builder().runId("run-1").status("CANCELLED").build();
    when(runRepo.findById("run-1")).thenReturn(Optional.of(run));

    writer.updateStatus("run-1", "REVIEWING");

    assertThat(run.getStatus()).isEqualTo("CANCELLED");
    verify(runRepo, never()).save(any());
    verify(broadcaster, never()).emit(eq("run-1"), any(RunEvent.class));
  }

  @Test
  void updateStatusDoesNotClobberPausedRun() {
    PipelineRun run = PipelineRun.builder().runId("run-2").status("PAUSED").build();
    when(runRepo.findById("run-2")).thenReturn(Optional.of(run));

    writer.updateStatus("run-2", "REVIEWING");

    assertThat(run.getStatus()).isEqualTo("PAUSED");
    verify(runRepo, never()).save(any());
    verify(broadcaster, never()).emit(eq("run-2"), any(RunEvent.class));
  }

  @Test
  void updateStatusPersistsAndBroadcastsActiveRun() {
    PipelineRun run = PipelineRun.builder().runId("run-3").status("WORKING").build();
    when(runRepo.findById("run-3")).thenReturn(Optional.of(run));

    writer.updateStatus("run-3", "REVIEWING");

    assertThat(run.getStatus()).isEqualTo("REVIEWING");
    verify(runRepo).save(run);
    verify(broadcaster).emit(eq("run-3"), any(RunEvent.class));
  }
}
