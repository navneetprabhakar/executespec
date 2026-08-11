package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.harness.TaskStatusPersistenceService.Result;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Replays task-row transitions that must survive workspace rollback process death. */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceGenerationTaskTransitionService {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final TaskStatusPersistenceService taskStatuses;
  private final PlatformTransactionManager transactionManager;

  @Scheduled(
      fixedDelayString = "${execution.workspace-generation.task-transition-scan-ms:5000}",
      initialDelayString =
          "${execution.workspace-generation.task-transition-initial-delay-ms:5000}")
  void publishPending() {
    for (WorkspaceGenerationAttempt attempt :
        attempts.findByTaskTransitionStateOrderByUpdatedAtAsc("PENDING")) {
      try {
        publish(attempt.getId());
      } catch (RuntimeException failure) {
        log.error(
            "Workspace generation task transition failed run={} task={} attempt={}",
            attempt.getRunId(),
            attempt.getTaskId(),
            attempt.getId(),
            failure);
      }
    }
  }

  public void publish(UUID attemptId) {
    WorkspaceGenerationAttempt attempt =
        attempts
            .findById(attemptId)
            .orElseThrow(
                () -> new IllegalStateException("WORKSPACE_GENERATION_TRANSITION_ATTEMPT_MISSING"));
    if ("COMPLETED".equals(attempt.getTaskTransitionState())) return;
    if (!"PENDING".equals(attempt.getTaskTransitionState())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_TASK_TRANSITION_NOT_PENDING");
    }
    String targetStatus =
        switch (attempt.getTaskTransitionKind()) {
          case "MARK_PARTIAL" -> "PARTIAL";
          case "REOPEN_PENDING" -> "PENDING";
          default ->
              throw new IllegalStateException("WORKSPACE_GENERATION_TRANSITION_KIND_INVALID");
        };
    Result result =
        taskStatuses.publishWorkspaceGenerationTransition(
            attempt.getRunId(),
            attempt.getTaskId(),
            targetStatus,
            attempt.getTaskTransitionExpectedStatus(),
            attempt.getTaskTransitionExpectedRecoveryAttempt(),
            attempt.getFailureReason());
    if (result != Result.UPDATED) {
      String reason = "TASK_TRANSITION_" + result.name();
      requireCas(
          independentTransactions()
              .execute(
                  ignored ->
                      attempts.recordTaskTransitionFailure(
                          attemptId, attempt.getTaskTransitionKind(), reason, Instant.now())),
          "WORKSPACE_GENERATION_TASK_TRANSITION_FAILURE_CAS_FAILED");
      throw new IllegalStateException(reason);
    }
    requireCas(
        independentTransactions()
            .execute(
                ignored ->
                    attempts.completeTaskTransition(
                        attemptId, attempt.getTaskTransitionKind(), Instant.now())),
        "WORKSPACE_GENERATION_TASK_TRANSITION_COMPLETE_CAS_FAILED");
  }

  private TransactionTemplate independentTransactions() {
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);
    transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return transactions;
  }

  private static void requireCas(Integer value, String code) {
    if (value == null || value != 1) throw new IllegalStateException(code);
  }
}
