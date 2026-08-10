package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.GcsTaskCheckpointService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.state.repository.PipelineRunMutationRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

class WorkspaceGenerationLifecycleBoundaryTest {

  @Test
  void taskLocalIgnoredDependenciesSurviveVerificationButArePurgedBeforeCheckpoint() {
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    String candidate = "a".repeat(64);
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    CorrectionWorkspaceTransaction workspaces = mock(CorrectionWorkspaceTransaction.class);
    WorkspaceIgnoredArtifactGuard ignored = mock(WorkspaceIgnoredArtifactGuard.class);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.ownerToken()).thenReturn(workspaceToken);
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = checkpoint("run-boundary");
    WorkspaceGenerationLifecycle lifecycle = lifecycle(attempts, workspaces, ignored);
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(attemptId, checkpoint, lease);
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .state(WorkspaceGenerationState.VERIFIED)
            .candidateGeneration(candidate)
            .ignoredArtifactBaselineJson("{}")
            .build();
    when(attempts.countCurrentDispatchAuthority(attemptId, workspaceToken)).thenReturn(1);
    when(attempts.findById(attemptId)).thenReturn(java.util.Optional.of(attempt));
    when(workspaces.currentGeneration(eq(checkpoint), any(Runnable.class))).thenReturn(candidate);
    when(attempts.transition(
            eq(attemptId),
            eq(workspaceToken),
            eq(WorkspaceGenerationState.VERIFIED),
            eq(WorkspaceGenerationState.CHECKPOINTING),
            any(Instant.class)))
        .thenReturn(1);

    lifecycle.beginCheckpoint(prepared);

    InOrder order = inOrder(ignored, workspaces, attempts);
    order.verify(ignored).cleanAttemptArtifacts(eq(checkpoint), eq("{}"), any(Runnable.class));
    order.verify(workspaces).currentGeneration(eq(checkpoint), any(Runnable.class));
    order
        .verify(attempts)
        .transition(
            eq(attemptId),
            eq(workspaceToken),
            eq(WorkspaceGenerationState.VERIFIED),
            eq(WorkspaceGenerationState.CHECKPOINTING),
            any(Instant.class));
  }

  @Test
  void enteringVerificationDoesNotDeleteTaskLocalIgnoredDependencies() {
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    String candidate = "b".repeat(64);
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    CorrectionWorkspaceTransaction workspaces = mock(CorrectionWorkspaceTransaction.class);
    WorkspaceIgnoredArtifactGuard ignored = mock(WorkspaceIgnoredArtifactGuard.class);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.ownerToken()).thenReturn(workspaceToken);
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = checkpoint("run-verifying");
    WorkspaceGenerationLifecycle lifecycle = lifecycle(attempts, workspaces, ignored);
    WorkspaceGenerationLifecycle.Prepared prepared =
        new WorkspaceGenerationLifecycle.Prepared(attemptId, checkpoint, lease);
    when(attempts.countCurrentDispatchAuthority(attemptId, workspaceToken)).thenReturn(1);
    when(workspaces.currentGeneration(eq(checkpoint), any(Runnable.class))).thenReturn(candidate);
    when(attempts.beginVerification(
            eq(attemptId),
            eq(workspaceToken),
            eq(candidate),
            eq(WorkspaceGenerationState.MUTATING),
            eq(WorkspaceGenerationState.VERIFYING),
            any(Instant.class)))
        .thenReturn(1);

    assertThat(lifecycle.beginVerification(prepared)).isEqualTo(candidate);

    verify(ignored, never()).cleanAttemptArtifacts(any(), any(), any());
  }

  private static WorkspaceGenerationLifecycle lifecycle(
      WorkspaceGenerationAttemptRepository attempts,
      CorrectionWorkspaceTransaction workspaces,
      WorkspaceIgnoredArtifactGuard ignored) {
    return new WorkspaceGenerationLifecycle(
        attempts,
        mock(WorkspaceGenerationVerificationReceiptRepository.class),
        mock(PipelineRunRepository.class),
        mock(PipelineRunMutationRepository.class),
        mock(RunWorkspaceAuthorityService.class),
        workspaces,
        new ObjectMapper(),
        mock(GcsTaskCheckpointService.class),
        mock(WorkspaceGenerationRecoveryService.class),
        ignored,
        mock(PlatformTransactionManager.class));
  }

  private static CorrectionWorkspaceTransaction.Checkpoint checkpoint(String directory) {
    Path workspace = Path.of("/tmp", directory);
    return new CorrectionWorkspaceTransaction.Checkpoint(
        workspace,
        List.of(
            new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                workspace, "1".repeat(40), "2".repeat(40))));
  }
}
