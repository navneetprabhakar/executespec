package com.navneet.executespec.workspacegeneration;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/** Converges terminal generation, task-transition, task-row, and LLM-operation state. */
@Service
@Slf4j
public class WorkspaceGenerationTerminalReconciler {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final WorkspaceGenerationTaskTransitionService taskTransitions;
  private final WorkspaceGenerationOperationReconciler operations;
  private final RunWorkspaceAuthorityService workspaceAuthorities;
  private final WorkspaceGenerationReconciliationFailureStore failures;
  private final int deadLetterThreshold;

  public WorkspaceGenerationTerminalReconciler(
      WorkspaceGenerationAttemptRepository attempts,
      WorkspaceGenerationTaskTransitionService taskTransitions,
      WorkspaceGenerationOperationReconciler operations,
      RunWorkspaceAuthorityService workspaceAuthorities,
      WorkspaceGenerationReconciliationFailureStore failures,
      @Value("${execution.workspace-generation.reconciliation-dead-letter-threshold:3}")
          int deadLetterThreshold) {
    this.attempts = attempts;
    this.taskTransitions = taskTransitions;
    this.operations = operations;
    this.workspaceAuthorities = workspaceAuthorities;
    this.failures = failures;
    this.deadLetterThreshold = deadLetterThreshold;
  }

  public void reconcileRun(String runId) {
    attempts.findByRunIdAndTaskTransitionStateOrderByUpdatedAtAsc(runId, "PENDING").stream()
        .map(WorkspaceGenerationAttempt::getId)
        .forEach(taskTransitions::publish);
    operations.reconcileRun(runId);
    assertConverged(runId);
    workspaceAuthorities.releaseConvergedGeneration(runId);
  }

  /**
   * Best-effort global sweep with poison-record isolation.
   *
   * <p>{@link #reconcileRun(String)} remains strict and fail-closed. The scheduler-facing batch
   * must not let one contradictory attempt starve unrelated runs on every scan.
   */
  public BatchResult reconcileAllKnownTerminalRuns() {
    List<String> failedAttempts = new java.util.ArrayList<>();
    List<String> failedRuns = new java.util.ArrayList<>();
    List<String> deadLetters = new java.util.ArrayList<>();
    List<WorkspaceGenerationAttempt> pending =
        attempts.findByTaskTransitionStateOrderByUpdatedAtAsc("PENDING");
    for (WorkspaceGenerationAttempt attempt : pending) {
      try {
        taskTransitions.publish(attempt.getId());
        recordDiagnosticSuccess(
            WorkspaceGenerationReconciliationFailureStore.IdentityKind.ATTEMPT,
            attempt.getId().toString());
      } catch (RuntimeException failure) {
        failedAttempts.add(attempt.getId().toString());
        var diagnostic =
            recordDiagnosticFailure(
                WorkspaceGenerationReconciliationFailureStore.IdentityKind.ATTEMPT,
                attempt.getId().toString(),
                "TASK_TRANSITION_RECONCILIATION_FAILED");
        if (diagnostic
            .map(WorkspaceGenerationReconciliationFailureStore.Failure::deadLetterAt)
            .isPresent()) {
          deadLetters.add("ATTEMPT:" + attempt.getId());
        }
        log.error(
            "Workspace generation terminal task transition remains unresolved run={} task={}"
                + " attempt={}",
            attempt.getRunId(),
            attempt.getTaskId(),
            attempt.getId(),
            failure);
      }
    }

    LinkedHashSet<String> runIds = new LinkedHashSet<>();
    attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ACCEPTED).stream()
        .map(WorkspaceGenerationAttempt::getRunId)
        .forEach(runIds::add);
    attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ROLLED_BACK).stream()
        .map(WorkspaceGenerationAttempt::getRunId)
        .forEach(runIds::add);
    for (String runId : runIds) {
      try {
        reconcileRun(runId);
        recordDiagnosticSuccess(
            WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN, runId);
      } catch (RuntimeException failure) {
        failedRuns.add(runId);
        var diagnostic =
            recordDiagnosticFailure(
                WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN,
                runId,
                "RUN_TERMINAL_RECONCILIATION_FAILED");
        if (diagnostic
            .map(WorkspaceGenerationReconciliationFailureStore.Failure::deadLetterAt)
            .isPresent()) {
          deadLetters.add("RUN:" + runId);
        }
        log.error("Workspace generation terminal reconciliation failed run={}", runId, failure);
      }
    }
    return new BatchResult(
        pending.size(),
        runIds.size(),
        oldestPendingAgeSeconds(pending),
        List.copyOf(failedAttempts),
        List.copyOf(failedRuns),
        List.copyOf(deadLetters));
  }

  public record BatchResult(
      int attemptedTransitions,
      int attemptedRuns,
      long oldestPendingAgeSeconds,
      List<String> failedAttemptIds,
      List<String> failedRunIds,
      List<String> deadLetterIdentities) {
    public boolean converged() {
      return failedAttemptIds.isEmpty() && failedRunIds.isEmpty();
    }
  }

  private static long oldestPendingAgeSeconds(List<WorkspaceGenerationAttempt> pending) {
    Instant oldest =
        pending.stream()
            .map(WorkspaceGenerationAttempt::getUpdatedAt)
            .filter(java.util.Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(Instant.now());
    return Math.max(0, Duration.between(oldest, Instant.now()).toSeconds());
  }

  private void recordDiagnosticSuccess(
      WorkspaceGenerationReconciliationFailureStore.IdentityKind kind, String identity) {
    try {
      failures.recordSuccess(kind, identity);
    } catch (RuntimeException diagnosticFailure) {
      log.error(
          "Workspace generation reconciliation success diagnostic unavailable kind={} identity={}",
          kind,
          identity,
          diagnosticFailure);
    }
  }

  private java.util.Optional<WorkspaceGenerationReconciliationFailureStore.Failure>
      recordDiagnosticFailure(
          WorkspaceGenerationReconciliationFailureStore.IdentityKind kind,
          String identity,
          String safeErrorCode) {
    try {
      return java.util.Optional.of(
          failures.recordFailure(kind, identity, safeErrorCode, deadLetterThreshold));
    } catch (RuntimeException diagnosticFailure) {
      log.error(
          "Workspace generation reconciliation failure diagnostic unavailable kind={} identity={}",
          kind,
          identity,
          diagnosticFailure);
      return java.util.Optional.empty();
    }
  }

  public void reconcileRolledBack(WorkspaceGenerationAttempt attempt) {
    taskTransitions.publish(attempt.getId());
    operations.reconcile(attempt.getId());
    assertConverged(attempt.getRunId());
  }

  public void assertConverged(String runId) {
    if (attempts.existsByRunIdAndTaskTransitionState(runId, "PENDING")) {
      throw new IllegalStateException("WORKSPACE_GENERATION_TASK_TRANSITION_PENDING");
    }
    operations.assertRunConverged(runId);
  }
}
