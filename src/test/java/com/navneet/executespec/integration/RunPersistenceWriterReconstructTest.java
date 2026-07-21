package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RR-334 — the re-review path rebuilds an {@link ExecutionResult} from durable task rows and feeds
 * it straight into terminal-status resolution. It must never invent success: a never-executed,
 * still-running, schema-drifted or entirely blocked run that reconstructs as {@code ALL_SUCCESS}
 * becomes a COMPLETE run with publication-eligible validation evidence.
 */
@ExtendWith(MockitoExtension.class)
class RunPersistenceWriterReconstructTest {

  @Mock com.navneet.executespec.state.repository.PipelineRunRepository runRepo;
  @Mock AgentTaskRepository taskRepo;
  @Mock com.navneet.executespec.billing.cost.RunCostLogRepository runCostLogRepo;
  @Mock com.navneet.executespec.sse.SseBroadcaster broadcaster;

  @InjectMocks RunPersistenceWriter writer;

  private static AgentTask task(String status) {
    AgentTask t = new AgentTask();
    t.setStatus(status);
    return t;
  }

  @Test
  void aRunWithNoTaskRowsIsNotASuccess() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of());
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.FAILED);
  }

  @Test
  void nullTaskStatusIsNotProofOfCompletion() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of(task(null)));
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.FAILED);
  }

  @Test
  void stillRunningTasksAreNotProofOfCompletion() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of(task("COMPLETE"), task("WORKING")));
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.PARTIAL_WITH_WARNINGS);
  }

  @Test
  void unknownFutureStatusesAreNotProofOfCompletion() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of(task("SOME_NEW_STATE")));
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.FAILED);
  }

  @Test
  void anEntirelyBlockedRunDeliveredNothingAndIsNotASuccess() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of(task("BLOCKED"), task("SKIPPED")));
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.FAILED);
  }

  @Test
  void everyTaskCompleteIsStillACleanSuccess() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of(task("COMPLETE"), task("COMPLETE")));
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.ALL_SUCCESS);
  }

  @Test
  void completeTasksAlongsideSkippedOnesRemainASuccess() {
    when(taskRepo.findByRunId("r")).thenReturn(List.of(task("COMPLETE"), task("SKIPPED")));
    assertThat(writer.reconstructExecResultFromTasks("r").overall())
        .isEqualTo(ExecutionResult.Overall.ALL_SUCCESS);
  }
}
