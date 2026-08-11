package com.navneet.executespec.workspacegeneration;

import java.util.List;

import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Converges terminal generation, task-transition, task-row, and LLM-operation state. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationTerminalReconciler {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final TaskStatusPersistenceService taskStatuses;
  private final WorkspaceGenerationTaskTransitionService taskTransitions;
  private final WorkspaceGenerationOperationReconciler operations;
  private final PipelineRunRepository runs;

  public void reconcileRun(String runId) {
    attempts.findByRunIdAndTaskTransitionStateOrderByUpdatedAtAsc(runId, "PENDING").stream()
        .map(WorkspaceGenerationAttempt::getId)
        .forEach(taskTransitions::publish);
    reconcileAcceptedTask(runId);
    operations.reconcileRun(runId);
    assertConverged(runId);
  }

  public void reconcileAllKnownTerminalRuns() {
    attempts.findByTaskTransitionStateOrderByUpdatedAtAsc("PENDING").stream()
        .map(WorkspaceGenerationAttempt::getId)
        .forEach(taskTransitions::publish);
    java.util.stream.Stream.concat(
            attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ACCEPTED).stream(),
            attempts.findByStateOrderByCreatedAtAsc(WorkspaceGenerationState.ROLLED_BACK).stream())
        .map(WorkspaceGenerationAttempt::getRunId)
        .distinct()
        .forEach(this::reconcileRun);
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

  private void reconcileAcceptedTask(String runId) {
    PipelineRun run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_RUN_MISSING"));
    if (run.getAcceptedGenerationAttemptId() == null
        || List.of("CANCELLED", "FAILED", "ABORTED", "REJECTED").contains(run.getStatus())) return;
    WorkspaceGenerationAttempt accepted =
        attempts
            .findById(run.getAcceptedGenerationAttemptId())
            .orElseThrow(
                () -> new IllegalStateException("WORKSPACE_GENERATION_ACCEPTANCE_MISSING"));
    if (accepted.getState() != WorkspaceGenerationState.ACCEPTED
        || !runId.equals(accepted.getRunId())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_ACCEPTANCE_INVALID");
    }
    WorkspaceGenerationAcceptanceProof.assertExact(
        runId, run.getAcceptedWorkspaceGeneration(), run, accepted);
    TaskStatusPersistenceService.Result result =
        taskStatuses.persist(runId, accepted.getTaskId(), "COMPLETE", null, null, null);
    if (result != TaskStatusPersistenceService.Result.UPDATED) {
      throw new IllegalStateException(
          "WORKSPACE_GENERATION_ACCEPTED_TASK_RECONCILIATION_FAILED:" + result);
    }
  }
}
