package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class WorkspaceGenerationTerminalReconcilerTest {

  @Test
  void pendingRollbackReceiptCompletesBeforeOperationReconciliation() {
    Fixture fixture = fixture();
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

    InOrder order = inOrder(fixture.transitions, fixture.operations, fixture.authorities);
    order.verify(fixture.transitions).publish(pending.getId());
    order.verify(fixture.operations).reconcileRun("run-1");
    order.verify(fixture.operations).assertRunConverged("run-1");
    order.verify(fixture.authorities).releaseConvergedGeneration("run-1");
  }

  @Test
  void terminalReconcilerNeverDerivesADirectTaskWriteOutsideTheDurableIntent() {
    Fixture fixture = fixture();

    fixture.target.reconcileRun("run-1");

    verify(fixture.operations).reconcileRun("run-1");
  }

  @Test
  void poisonAttemptDoesNotStarveAHealthyRunInTheSameBatch() {
    Fixture fixture = fixture();
    UUID poisonId = UUID.randomUUID();
    WorkspaceGenerationAttempt poison =
        WorkspaceGenerationAttempt.builder()
            .id(poisonId)
            .runId("run-poison")
            .taskId("T-poison")
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .build();
    WorkspaceGenerationAttempt healthy =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId("run-1")
            .taskId("T1")
            .state(WorkspaceGenerationState.ACCEPTED)
            .build();
    when(fixture.attempts.findByTaskTransitionStateOrderByUpdatedAtAsc("PENDING"))
        .thenReturn(List.of(poison));
    when(fixture.attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ACCEPTED))
        .thenReturn(List.of(poison, healthy));
    when(fixture.attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ROLLED_BACK))
        .thenReturn(List.of());
    when(fixture.attempts.findByRunIdAndTaskTransitionStateOrderByUpdatedAtAsc(
            "run-poison", "PENDING"))
        .thenReturn(List.of(poison));
    org.mockito.Mockito.doThrow(new IllegalStateException("poison transition"))
        .when(fixture.transitions)
        .publish(poisonId);

    WorkspaceGenerationTerminalReconciler.BatchResult result =
        fixture.target.reconcileAllKnownTerminalRuns();

    assertThat(result.attemptedTransitions()).isEqualTo(1);
    assertThat(result.attemptedRuns()).isEqualTo(2);
    assertThat(result.oldestPendingAgeSeconds()).isNotNegative();
    assertThat(result.failedAttemptIds()).containsExactly(poisonId.toString());
    assertThat(result.failedRunIds()).containsExactly("run-poison");
    verify(fixture.operations).reconcileRun("run-1");
    verify(fixture.operations).assertRunConverged("run-1");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2})
  void poisonTransitionInFirstMiddleOrLastPositionNeverStarvesHealthyTransitions(
      int poisonPosition) {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationTaskTransitionService transitions =
        mock(WorkspaceGenerationTaskTransitionService.class);
    WorkspaceGenerationOperationReconciler operations =
        mock(WorkspaceGenerationOperationReconciler.class);
    WorkspaceGenerationReconciliationFailureStore failures =
        mock(WorkspaceGenerationReconciliationFailureStore.class);
    WorkspaceGenerationAttempt first = pending("run-first", "T1");
    WorkspaceGenerationAttempt poison = pending("run-poison", "T2");
    WorkspaceGenerationAttempt last = pending("run-last", "T3");
    java.util.ArrayList<WorkspaceGenerationAttempt> ordered =
        new java.util.ArrayList<>(List.of(first, last));
    ordered.add(poisonPosition, poison);
    when(attempts.findByTaskTransitionStateOrderByUpdatedAtAsc("PENDING")).thenReturn(ordered);
    when(attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ACCEPTED))
        .thenReturn(List.of());
    when(attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ROLLED_BACK))
        .thenReturn(List.of());
    org.mockito.Mockito.doThrow(new IllegalStateException("poison transition"))
        .when(transitions)
        .publish(poison.getId());
    when(failures.recordFailure(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(
            new WorkspaceGenerationReconciliationFailureStore.Failure(
                UUID.randomUUID(),
                WorkspaceGenerationReconciliationFailureStore.IdentityKind.ATTEMPT,
                poison.getId().toString(),
                3,
                "TASK_TRANSITION_RECONCILIATION_FAILED",
                java.time.Instant.now().minusSeconds(30),
                java.time.Instant.now(),
                java.time.Instant.now(),
                null));
    WorkspaceGenerationTerminalReconciler target =
        new WorkspaceGenerationTerminalReconciler(
            attempts,
            transitions,
            operations,
            mock(RunWorkspaceAuthorityService.class),
            failures,
            3);

    WorkspaceGenerationTerminalReconciler.BatchResult result =
        target.reconcileAllKnownTerminalRuns();

    verify(transitions).publish(first.getId());
    verify(transitions).publish(last.getId());
    assertThat(result.failedAttemptIds()).containsExactly(poison.getId().toString());
    assertThat(result.deadLetterIdentities()).containsExactly("ATTEMPT:" + poison.getId());
    assertThat(result.oldestPendingAgeSeconds()).isGreaterThanOrEqualTo(30);
  }

  @Test
  void diagnosticStoreFailureCannotTurnAHealthyTransitionIntoPoison() {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationTaskTransitionService transitions =
        mock(WorkspaceGenerationTaskTransitionService.class);
    WorkspaceGenerationOperationReconciler operations =
        mock(WorkspaceGenerationOperationReconciler.class);
    WorkspaceGenerationReconciliationFailureStore failures =
        mock(WorkspaceGenerationReconciliationFailureStore.class);
    WorkspaceGenerationAttempt first = pending("run-first", "T1");
    WorkspaceGenerationAttempt second = pending("run-second", "T2");
    when(attempts.findByTaskTransitionStateOrderByUpdatedAtAsc("PENDING"))
        .thenReturn(List.of(first, second));
    when(attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ACCEPTED))
        .thenReturn(List.of());
    when(attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ROLLED_BACK))
        .thenReturn(List.of());
    org.mockito.Mockito.doThrow(new IllegalStateException("diagnostic store unavailable"))
        .when(failures)
        .recordSuccess(any(), any());
    WorkspaceGenerationTerminalReconciler target =
        new WorkspaceGenerationTerminalReconciler(
            attempts,
            transitions,
            operations,
            mock(RunWorkspaceAuthorityService.class),
            failures,
            3);

    WorkspaceGenerationTerminalReconciler.BatchResult result =
        target.reconcileAllKnownTerminalRuns();

    verify(transitions).publish(first.getId());
    verify(transitions).publish(second.getId());
    assertThat(result.converged()).isTrue();
    assertThat(result.failedAttemptIds()).isEmpty();
    assertThat(result.deadLetterIdentities()).isEmpty();
  }

  private static WorkspaceGenerationAttempt pending(String runId, String taskId) {
    return WorkspaceGenerationAttempt.builder()
        .id(UUID.randomUUID())
        .runId(runId)
        .taskId(taskId)
        .state(WorkspaceGenerationState.ROLLED_BACK)
        .updatedAt(java.time.Instant.now().minusSeconds(30))
        .build();
  }

  private static Fixture fixture() {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationTaskTransitionService transitions =
        mock(WorkspaceGenerationTaskTransitionService.class);
    WorkspaceGenerationOperationReconciler operations =
        mock(WorkspaceGenerationOperationReconciler.class);
    WorkspaceGenerationReconciliationFailureStore failures =
        mock(WorkspaceGenerationReconciliationFailureStore.class);
    RunWorkspaceAuthorityService authorities = mock(RunWorkspaceAuthorityService.class);
    when(attempts.findByRunIdAndTaskTransitionStateOrderByUpdatedAtAsc("run-1", "PENDING"))
        .thenReturn(List.of());
    when(failures.recordFailure(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
        .thenAnswer(
            invocation ->
                new WorkspaceGenerationReconciliationFailureStore.Failure(
                    UUID.randomUUID(),
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    1,
                    invocation.getArgument(2),
                    java.time.Instant.now(),
                    java.time.Instant.now(),
                    null,
                    null));
    WorkspaceGenerationTerminalReconciler target =
        new WorkspaceGenerationTerminalReconciler(
            attempts, transitions, operations, authorities, failures, 3);
    return new Fixture(attempts, transitions, operations, authorities, target);
  }

  private record Fixture(
      WorkspaceGenerationAttemptRepository attempts,
      WorkspaceGenerationTaskTransitionService transitions,
      WorkspaceGenerationOperationReconciler operations,
      RunWorkspaceAuthorityService authorities,
      WorkspaceGenerationTerminalReconciler target) {}
}
