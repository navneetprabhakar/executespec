package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WorkspaceGenerationTerminalReconcilerTest {

  @Test
  void pendingRollbackReceiptCompletesBeforeOperationReconciliation() {
    Fixture fixture = fixture("WORKING");
    WorkspaceGenerationAttempt pending =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId("run-1")
            .taskId("T1")
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .build();
    when(fixture.attempts.findByRunIdAndTaskTransitionStateOrderByUpdatedAtAsc("run-1", "PENDING"))
        .thenReturn(List.of(pending));

    fixture.target.reconcileRun("run-1");

    InOrder order = inOrder(fixture.transitions, fixture.operations);
    order.verify(fixture.transitions).publish(pending.getId());
    order.verify(fixture.operations).reconcileRun("run-1");
    order.verify(fixture.operations).assertRunConverged("run-1");
  }

  @Test
  void acceptedTaskTypedPersistenceFailureFailsClosed() {
    Fixture fixture = fixture("WORKING");
    when(fixture.taskStatuses.persist("run-1", "T1", "COMPLETE", null, null, null))
        .thenReturn(TaskStatusPersistenceService.Result.ROW_NOT_FOUND);

    assertThatThrownBy(() -> fixture.target.reconcileRun("run-1"))
        .hasMessage("WORKSPACE_GENERATION_ACCEPTED_TASK_RECONCILIATION_FAILED:ROW_NOT_FOUND");

    verify(fixture.operations, never()).reconcileRun(any());
  }

  @Test
  void cancelledRunWinsOverAcceptedTaskCompletionReplay() {
    Fixture fixture = fixture("CANCELLED");

    fixture.target.reconcileRun("run-1");

    verify(fixture.taskStatuses, never()).persist(any(), any(), any(), any(), any(), any());
    verify(fixture.operations).reconcileRun("run-1");
  }

  private static Fixture fixture(String runStatus) {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    TaskStatusPersistenceService taskStatuses = mock(TaskStatusPersistenceService.class);
    WorkspaceGenerationTaskTransitionService transitions =
        mock(WorkspaceGenerationTaskTransitionService.class);
    WorkspaceGenerationOperationReconciler operations =
        mock(WorkspaceGenerationOperationReconciler.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    UUID acceptedId = UUID.randomUUID();
    java.time.Instant checkpointAt = java.time.Instant.parse("2026-08-11T12:00:00Z");
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .status(runStatus)
            .acceptedGenerationAttemptId(acceptedId)
            .acceptedWorkspaceGeneration("generation-1")
            .acceptedTaskIndex(0)
            .acceptedCheckpointKind("LOCAL")
            .acceptedCheckpointKey("checkpoint-1")
            .acceptedCheckpointDigest("digest-1")
            .acceptedCheckpointCompletedAt(checkpointAt)
            .build();
    WorkspaceGenerationAttempt accepted =
        WorkspaceGenerationAttempt.builder()
            .id(acceptedId)
            .runId("run-1")
            .taskId("T1")
            .taskIndex(0)
            .state(WorkspaceGenerationState.ACCEPTED)
            .candidateGeneration("generation-1")
            .checkpointKind("LOCAL")
            .checkpointKey("checkpoint-1")
            .checkpointDigest("digest-1")
            .checkpointCompletedAt(checkpointAt)
            .build();
    when(runs.findById("run-1")).thenReturn(Optional.of(run));
    when(attempts.findById(acceptedId)).thenReturn(Optional.of(accepted));
    when(attempts.findByRunIdAndTaskTransitionStateOrderByUpdatedAtAsc("run-1", "PENDING"))
        .thenReturn(List.of());
    when(taskStatuses.persist("run-1", "T1", "COMPLETE", null, null, null))
        .thenReturn(TaskStatusPersistenceService.Result.UPDATED);
    WorkspaceGenerationTerminalReconciler target =
        new WorkspaceGenerationTerminalReconciler(
            attempts, taskStatuses, transitions, operations, runs);
    return new Fixture(attempts, taskStatuses, transitions, operations, target);
  }

  private record Fixture(
      WorkspaceGenerationAttemptRepository attempts,
      TaskStatusPersistenceService taskStatuses,
      WorkspaceGenerationTaskTransitionService transitions,
      WorkspaceGenerationOperationReconciler operations,
      WorkspaceGenerationTerminalReconciler target) {}
}
