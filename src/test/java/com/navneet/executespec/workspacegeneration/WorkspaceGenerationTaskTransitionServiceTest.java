package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.harness.TaskStatusPersistenceService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class WorkspaceGenerationTaskTransitionServiceTest {

  @Test
  void publishesPartialStatusThenCommitsTheOutboxReceiptIndependently() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    TaskStatusPersistenceService tasks = mock(TaskStatusPersistenceService.class);
    PlatformTransactionManager transactions = transactionManager();
    WorkspaceGenerationAttempt attempt =
        transition(attemptId, "MARK_PARTIAL", "verification failed");
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
    when(tasks.publishWorkspaceGenerationTransition(
            "12345678-run", "T1", "PARTIAL", "WORKING", 2, "verification failed"))
        .thenReturn(TaskStatusPersistenceService.Result.UPDATED);
    when(attempts.completeTaskTransition(eq(attemptId), eq("MARK_PARTIAL"), any(Instant.class)))
        .thenReturn(1);

    new WorkspaceGenerationTaskTransitionService(attempts, tasks, transactions).publish(attemptId);

    verify(attempts).completeTaskTransition(eq(attemptId), eq("MARK_PARTIAL"), any(Instant.class));
    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void failedTaskPublicationRemainsPendingWithDurableFailureEvidence() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    TaskStatusPersistenceService tasks = mock(TaskStatusPersistenceService.class);
    PlatformTransactionManager transactions = transactionManager();
    WorkspaceGenerationAttempt attempt = transition(attemptId, "REOPEN_PENDING", null);
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
    when(tasks.publishWorkspaceGenerationTransition(
            "12345678-run", "T1", "PENDING", "WORKING", 2, null))
        .thenReturn(TaskStatusPersistenceService.Result.ROW_NOT_FOUND);
    when(attempts.recordTaskTransitionFailure(
            eq(attemptId),
            eq("REOPEN_PENDING"),
            eq("TASK_TRANSITION_ROW_NOT_FOUND"),
            any(Instant.class)))
        .thenReturn(1);

    assertThatThrownBy(
            () ->
                new WorkspaceGenerationTaskTransitionService(attempts, tasks, transactions)
                    .publish(attemptId))
        .hasMessage("TASK_TRANSITION_ROW_NOT_FOUND");

    verify(attempts, never()).completeTaskTransition(any(), any(), any());
  }

  @Test
  void completedPublicationIsIdempotent() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    TaskStatusPersistenceService tasks = mock(TaskStatusPersistenceService.class);
    WorkspaceGenerationAttempt attempt = transition(attemptId, "MARK_PARTIAL", null);
    attempt.setTaskTransitionState("COMPLETED");
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));

    new WorkspaceGenerationTaskTransitionService(attempts, tasks, transactionManager())
        .publish(attemptId);

    verifyNoInteractions(tasks);
  }

  @Test
  void publishesAcceptedCompleteThenCommitsTheOutboxReceipt() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    TaskStatusPersistenceService tasks = mock(TaskStatusPersistenceService.class);
    PlatformTransactionManager transactions = transactionManager();
    WorkspaceGenerationAttempt attempt = transition(attemptId, "MARK_COMPLETE", null);
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
    when(tasks.publishWorkspaceGenerationTransition(
            "12345678-run", "T1", "COMPLETE", "WORKING", 2, null))
        .thenReturn(TaskStatusPersistenceService.Result.UPDATED);
    when(attempts.completeTaskTransition(eq(attemptId), eq("MARK_COMPLETE"), any(Instant.class)))
        .thenReturn(1);

    new WorkspaceGenerationTaskTransitionService(attempts, tasks, transactions).publish(attemptId);

    verify(attempts).completeTaskTransition(eq(attemptId), eq("MARK_COMPLETE"), any(Instant.class));
  }

  private static WorkspaceGenerationAttempt transition(UUID id, String kind, String failureReason) {
    return WorkspaceGenerationAttempt.builder()
        .id(id)
        .runId("12345678-run")
        .taskId("T1")
        .failureReason(failureReason)
        .taskTransitionKind(kind)
        .taskTransitionState("PENDING")
        .taskTransitionExpectedStatus("WORKING")
        .taskTransitionExpectedRecoveryAttempt(2)
        .build();
  }

  private static PlatformTransactionManager transactionManager() {
    PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(mock(TransactionStatus.class));
    return manager;
  }
}
