package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationConvergenceServiceTest {

  @Test
  void acceptedDurableStateReconcilesSuccessWithoutRollback() {
    Fixture fixture = fixture(WorkspaceGenerationState.ACCEPTED);

    assertThat(
            fixture.target.convergeAfterFailure(
                fixture.prepared, "TASK_GENERATION_EXCEPTION", "late coordinator failure"))
        .isEqualTo(WorkspaceGenerationConvergenceService.Disposition.ACCEPTED_AND_RECONCILED);

    verify(fixture.terminals).reconcileRun("run-1");
    verify(fixture.rollback, never()).rollback(any(), any(), any(), any(), any(), any());
  }

  @Test
  void mutableDurableStateRollsBackWithHeldBuildLeaseThenReconciles() {
    Fixture fixture = fixture(WorkspaceGenerationState.CHECKPOINTING);
    WorkspaceGenerationAttempt rolledBack =
        attempt(fixture.prepared.attemptId(), WorkspaceGenerationState.ROLLED_BACK);
    when(fixture.attempts.findById(fixture.prepared.attemptId()))
        .thenReturn(Optional.of(fixture.attempt), Optional.of(rolledBack));

    assertThat(
            fixture.target.convergeAfterFailure(
                fixture.prepared, "TASK_GENERATION_EXCEPTION", "frontier CAS lost"))
        .isEqualTo(WorkspaceGenerationConvergenceService.Disposition.ROLLED_BACK_AND_RECONCILED);

    verify(fixture.rollback)
        .rollback(
            eq(fixture.prepared.attemptId()),
            eq(fixture.workspaceToken),
            eq(fixture.prepared.checkpoint()),
            any(Runnable.class),
            eq("TASK_GENERATION_EXCEPTION"),
            eq("frontier CAS lost"));
    verify(fixture.terminals).reconcileRolledBack(rolledBack);
  }

  @Test
  void recoveryFailedStateDefersUntilBuildLeaseIsReleased() {
    Fixture fixture = fixture(WorkspaceGenerationState.RECOVERY_FAILED);

    assertThatThrownBy(
            () ->
                fixture.target.convergeAfterFailure(
                    fixture.prepared, "TASK_GENERATION_EXCEPTION", "rollback proof failed"))
        .hasMessage("WORKSPACE_GENERATION_RECOVERY_REQUIRED");

    verify(fixture.rollback, never()).rollback(any(), any(), any(), any(), any(), any());
    verify(fixture.terminals, never()).reconcileRun(any());
  }

  private static Fixture fixture(WorkspaceGenerationState state) {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationRollbackService rollback = mock(WorkspaceGenerationRollbackService.class);
    WorkspaceGenerationTerminalReconciler terminals =
        mock(WorkspaceGenerationTerminalReconciler.class);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    UUID workspaceToken = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    when(lease.ownerToken()).thenReturn(workspaceToken);
    Path root = Path.of("/tmp/run-1");
    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction.Checkpoint(
            root,
            List.of(
                new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                    root, "1".repeat(40), "2".repeat(40))));
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(attemptId, checkpoint, lease);
    WorkspaceGenerationAttempt attempt = attempt(attemptId, state);
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
    return new Fixture(
        attempts,
        rollback,
        terminals,
        new WorkspaceGenerationConvergenceService(attempts, rollback, terminals),
        prepared,
        attempt,
        workspaceToken);
  }

  private static WorkspaceGenerationAttempt attempt(UUID id, WorkspaceGenerationState state) {
    return WorkspaceGenerationAttempt.builder()
        .id(id)
        .runId("run-1")
        .taskId("T1")
        .state(state)
        .build();
  }

  private record Fixture(
      WorkspaceGenerationAttemptRepository attempts,
      WorkspaceGenerationRollbackService rollback,
      WorkspaceGenerationTerminalReconciler terminals,
      WorkspaceGenerationConvergenceService target,
      WorkspaceGenerationLifecycle.Prepared prepared,
      WorkspaceGenerationAttempt attempt,
      UUID workspaceToken) {}
}
