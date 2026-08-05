package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.lint.LintPass;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the durable state transitions around an automatic correction attempt.
 *
 * <p>The orchestrator decides whether and how to execute a correction; this collaborator owns its
 * reversible workspace checkpoint and the task action ledger. Keeping those mechanics together
 * prevents execution flow from accidentally accepting a workspace without recording its
 * content-addressed before/after generations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomaticCorrectionLifecycle {

  private final CorrectionWorkspaceTransaction workspaceTransaction;
  private final AgentTaskRepository taskRepository;
  private final ObjectMapper objectMapper;
  private final FeatureFlagService featureFlagService;
  private final LintPass lintPass;
  private final SseBroadcaster broadcaster;
  private final WorkflowConfigEvidenceInspector workflowConfigEvidenceInspector;
  private final ValidationReportRepository validationReportRepository;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  private final DurableCorrectionLifecycle durableCorrectionLifecycle;
  private final java.util.concurrent.ConcurrentMap<
          CorrectionWorkspaceTransaction.Checkpoint, DurableCorrectionLifecycle.Prepared>
      durableAttempts = new java.util.concurrent.ConcurrentHashMap<>();

  public CorrectionWorkspaceTransaction.Checkpoint begin(Path workspaceRoot) {
    return workspaceTransaction.begin(workspaceRoot);
  }

  public CorrectionWorkspaceTransaction.Checkpoint begin(
      String runId, String taskId, Path workspaceRoot) {
    DurableCorrectionLifecycle.Prepared prepared =
        durableCorrectionLifecycle.prepare(runId, taskId, workspaceRoot);
    durableCorrectionLifecycle.markMutating(prepared);
    durableAttempts.put(prepared.checkpoint(), prepared);
    return prepared.checkpoint();
  }

  public CorrectionWorkspaceTransaction.Accepted accept(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = requirePrepared(checkpoint);
    try {
      prepared.authority().assertOwned();
      CorrectionWorkspaceTransaction.Accepted accepted = workspaceTransaction.accept(checkpoint);
      durableCorrectionLifecycle.accept(prepared, accepted.afterGeneration());
      durableAttempts.remove(checkpoint, prepared);
      return accepted;
    } catch (RuntimeException failure) {
      prepared.authority().close();
      throw failure;
    }
  }

  /** Computes the candidate generations without resolving the durable attempt. */
  public CorrectionWorkspaceTransaction.Accepted previewAcceptance(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = assertPreparedAuthority(checkpoint);
    try {
      return workspaceTransaction.accept(checkpoint);
    } catch (RuntimeException failure) {
      prepared.authority().close();
      throw failure;
    }
  }

  /** Runs a correction worker with the exact durable lease bound to every tool-dispatch thread. */
  public <T> T withToolFence(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint, java.util.function.Supplier<T> work) {
    DurableCorrectionLifecycle.Prepared prepared = requirePrepared(checkpoint);
    return RunWorkspaceAuthorityService.withCorrectionToolLease(prepared.authority(), work);
  }

  public ExecutionResult executeWithToolFence(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      SequentialExecutor executor,
      ExecutionRequest request,
      SequentialExecutor.ResumeRequest resume) {
    return withToolFence(checkpoint, () -> executor.execute(request, resume));
  }

  public String currentGeneration(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = assertPreparedAuthority(checkpoint);
    return workspaceTransaction.currentGeneration(
        prepared.checkpoint(), prepared.authority()::assertOwned);
  }

  public CorrectionWorkspaceTransaction.DiffEvidence diffEvidence(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = assertPreparedAuthority(checkpoint);
    return workspaceTransaction.diffEvidence(
        prepared.checkpoint(), prepared.authority()::assertOwned);
  }

  public void rollback(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = assertPreparedAuthority(checkpoint);
    try {
      workspaceTransaction.rollback(checkpoint, prepared.authority()::assertOwned);
    } catch (RuntimeException failure) {
      prepared.authority().close();
      throw failure;
    }
  }

  public void assertAuthority(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    assertPreparedAuthority(checkpoint);
  }

  private DurableCorrectionLifecycle.Prepared assertPreparedAuthority(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = requirePrepared(checkpoint);
    try {
      prepared.authority().assertOwned();
      return prepared;
    } catch (RuntimeException failure) {
      prepared.authority().close();
      throw failure;
    }
  }

  private DurableCorrectionLifecycle.Prepared requirePrepared(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = durableAttempts.get(checkpoint);
    if (prepared == null) {
      throw new IllegalStateException("CORRECTION_DURABLE_ATTEMPT_MISSING");
    }
    return prepared;
  }

  public WorkflowConfigEvidence inspectWorkflowConfig(Path workspaceRoot) {
    return workflowConfigEvidenceInspector.inspect(workspaceRoot);
  }

  public ReviewAssessment assessReadOnlyReview(
      String runId,
      String targetTaskId,
      Path workspaceRoot,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      String preReviewerGeneration,
      ExecutionResult execution,
      ReviewerResult review) {
    String postReviewerGeneration = currentGeneration(checkpoint);
    if (!preReviewerGeneration.equals(postReviewerGeneration)) {
      throw new IllegalStateException(
          "CORRECTION_REVIEWER_MUTATED_WORKSPACE: read-only reviewer changed the correction"
              + " generation");
    }
    CorrectionResolutionEvidence.ContractValidation contracts =
        brownfieldHttpContractGuard.hasBaseline(runId)
            ? CorrectionResolutionEvidence.contracts(
                brownfieldHttpContractGuard.assertCompatible(runId))
            : CorrectionResolutionEvidence.ContractValidation.notApplicable();
    CorrectionWorkspaceTransaction.DiffEvidence diff = diffEvidence(checkpoint);
    WorkflowConfigEvidence workflow = inspectWorkflowConfig(workspaceRoot);
    CorrectionAcceptancePolicy.Decision decision =
        CorrectionAcceptancePolicy.evaluate(
            targetTaskId,
            execution,
            review,
            workflow,
            checkpoint.generation(),
            postReviewerGeneration);
    return new ReviewAssessment(
        postReviewerGeneration,
        diff,
        decision,
        CorrectionResolutionEvidence.attemptSnapshot(
            postReviewerGeneration, execution, review, workflow, contracts));
  }

  public void rollbackFailedAttempt(
      String taskKey,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      EvidenceCheckpoint evidenceCheckpoint,
      CorrectionWorkspaceTransaction.DiffEvidence knownDiff,
      Exception failure) {
    try {
      CorrectionWorkspaceTransaction.DiffEvidence diff = knownDiff;
      if (diff == null) {
        try {
          diff = diffEvidence(checkpoint);
        } catch (RuntimeException diffFailure) {
          failure.addSuppressed(diffFailure);
        }
      }
      String failedGeneration;
      try {
        failedGeneration = currentGeneration(checkpoint);
      } catch (RuntimeException generationFailure) {
        failure.addSuppressed(generationFailure);
        failedGeneration = checkpoint.generation();
      }
      CorrectionResolutionEvidence.ValidationDelta validationDelta =
          captureValidationDelta(
              evidenceCheckpoint,
              CorrectionResolutionEvidence.failedAttemptSnapshot(
                  failedGeneration,
                  failure.getMessage(),
                  inspectContracts(
                      evidenceCheckpoint == null ? null : evidenceCheckpoint.runId())));
      rollback(checkpoint);
      restoreEvidence(evidenceCheckpoint);
      recordResolution(
          taskKey,
          "ROLLED_BACK",
          "CORRECTION_EXECUTION_FAILED",
          failure.getMessage(),
          checkpoint.generation(),
          checkpoint.generation(),
          diff,
          validationDelta,
          true);
      markDurableRolledBack(checkpoint);
    } catch (RuntimeException rollbackFailure) {
      releaseForRecovery(checkpoint);
      rollbackFailure.addSuppressed(failure);
      throw new IllegalStateException(
          "Automatic correction failed and its workspace checkpoint could not be restored",
          rollbackFailure);
    }
  }

  public void rollbackRejectedAttempt(
      String taskKey,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      EvidenceCheckpoint evidenceCheckpoint,
      CorrectionWorkspaceTransaction.DiffEvidence diff,
      TaskState previous,
      CorrectionAcceptancePolicy.Decision rejection,
      CorrectionResolutionEvidence.ValidationSnapshot attemptValidation) {
    try {
      CorrectionResolutionEvidence.ValidationDelta validationDelta =
          captureValidationDelta(evidenceCheckpoint, attemptValidation);
      rollback(checkpoint);
      restoreEvidence(evidenceCheckpoint);
      restoreTask(taskKey, previous);
      recordResolution(
          taskKey,
          "REJECTED",
          rejection.code(),
          rejection.reason(),
          checkpoint.generation(),
          checkpoint.generation(),
          diff,
          validationDelta,
          true);
      markDurableRolledBack(checkpoint);
    } catch (RuntimeException failure) {
      releaseForRecovery(checkpoint);
      throw failure;
    }
  }

  public boolean rollbackIfEvidenceRegressed(
      String runId,
      String taskId,
      String taskKey,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      EvidenceCheckpoint evidenceCheckpoint,
      CorrectionWorkspaceTransaction.DiffEvidence diff,
      TaskState previous) {
    CorrectionResolutionEvidence.ValidationDelta delta = captureValidationDelta(evidenceCheckpoint);
    CorrectionAcceptancePolicy.Decision decision =
        CorrectionAcceptancePolicy.evaluateRegression(delta);
    if (decision.accepted()) return false;
    rollbackRejectedAttempt(
        taskKey,
        checkpoint,
        evidenceCheckpoint,
        diff,
        previous,
        decision,
        delta == null ? null : delta.after());
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            taskId,
            "Automatic correction rejected after comparative validation: " + decision.reason()));
    return true;
  }

  /**
   * Marks all previously published validation facts stale before a correction worker can mutate the
   * workspace. The detached copy is the durable rollback checkpoint if the correction is not
   * promoted.
   */
  public EvidenceCheckpoint invalidateEvidence(String runId) {
    ValidationReport previous =
        validationReportRepository
            .findByRunId(runId)
            .map(CorrectionStateSnapshots::copyValidationReport)
            .orElse(null);
    if (previous != null) {
      ValidationReport stale = CorrectionStateSnapshots.copyValidationReport(previous);
      CorrectionStateSnapshots.markIndependentEvidenceStale(stale);
      validationReportRepository.saveAndFlush(stale);
    }
    return new EvidenceCheckpoint(runId, previous, inspectContracts(runId));
  }

  /** Restores the exact validation row that existed before the correction attempt. */
  public void restoreEvidence(EvidenceCheckpoint checkpoint) {
    if (checkpoint == null || checkpoint.runId() == null) {
      return;
    }
    if (checkpoint.previous() != null) {
      validationReportRepository.saveAndFlush(
          CorrectionStateSnapshots.copyValidationReport(checkpoint.previous()));
      return;
    }
    validationReportRepository
        .findByRunId(checkpoint.runId())
        .ifPresent(validationReportRepository::delete);
    validationReportRepository.flush();
  }

  static void markIndependentEvidenceStale(ValidationReport report) {
    CorrectionStateSnapshots.markIndependentEvidenceStale(report);
  }

  public record EvidenceCheckpoint(
      String runId,
      ValidationReport previous,
      CorrectionResolutionEvidence.ContractValidation contractsBefore) {
    public EvidenceCheckpoint(String runId, ValidationReport previous) {
      this(runId, previous, CorrectionResolutionEvidence.ContractValidation.unknown());
    }
  }

  /** Captures regenerated attempt evidence before rollback restores the prior report. */
  public CorrectionResolutionEvidence.ValidationDelta captureValidationDelta(
      EvidenceCheckpoint checkpoint) {
    return captureValidationDelta(checkpoint, null);
  }

  public CorrectionResolutionEvidence.ValidationDelta captureValidationDelta(
      EvidenceCheckpoint checkpoint,
      CorrectionResolutionEvidence.ValidationSnapshot fallbackAfter) {
    if (checkpoint == null || checkpoint.runId() == null) return null;
    CorrectionResolutionEvidence.ValidationSnapshot before =
        CorrectionResolutionEvidence.snapshot(
            objectMapper, checkpoint.previous(), checkpoint.contractsBefore());
    ValidationReport current =
        validationReportRepository.findByRunId(checkpoint.runId()).orElse(null);
    CorrectionResolutionEvidence.ValidationSnapshot after =
        current == null || "STALE".equalsIgnoreCase(current.getStatus())
            ? fallbackAfter
            : CorrectionResolutionEvidence.snapshot(
                objectMapper, current, inspectContracts(checkpoint.runId()));
    return after == null ? null : CorrectionResolutionEvidence.delta(before, after);
  }

  private CorrectionResolutionEvidence.ContractValidation inspectContracts(String runId) {
    if (runId == null || runId.isBlank()) {
      return CorrectionResolutionEvidence.ContractValidation.unknown();
    }
    if (!brownfieldHttpContractGuard.hasBaseline(runId)) {
      return CorrectionResolutionEvidence.ContractValidation.notApplicable();
    }
    try {
      return CorrectionResolutionEvidence.contracts(
          brownfieldHttpContractGuard.inspectCompatibility(runId));
    } catch (RuntimeException unavailable) {
      log.warn(
          "automatic correction: could not snapshot protected contracts for run {}: {}",
          runId,
          unavailable.getMessage());
      return CorrectionResolutionEvidence.ContractValidation.unknown();
    }
  }

  public record ReviewAssessment(
      String workspaceGeneration,
      CorrectionWorkspaceTransaction.DiffEvidence diff,
      CorrectionAcceptancePolicy.Decision decision,
      CorrectionResolutionEvidence.ValidationSnapshot attemptValidation) {}

  static boolean sameRoute(
      ModelProviderScore candidate, String dispatchedProvider, String dispatchedModel) {
    return candidate != null
        && candidate
            .provider()
            .equalsIgnoreCase(dispatchedProvider == null ? "" : dispatchedProvider)
        && candidate.model().equalsIgnoreCase(dispatchedModel == null ? "" : dispatchedModel);
  }

  /** Runs the optional formatting pass inside the still-reversible correction transaction. */
  public void runLintAutofix(String runId, Path workspaceRoot) {
    if (!featureFlagService.isEnabled("lint.autofix.enabled")) {
      return;
    }
    try {
      LintPass.LintReport lintReport = lintPass.run(runId, workspaceRoot);
      if (lintReport.changedAnything()) {
        log.info("Automatic correction run={} lint auto-fix: {}", runId, lintReport.summary());
        broadcaster.emit(runId, RunEvent.lintAutofixApplied(runId, lintReport.summary()));
      } else if (!lintReport.skipped()) {
        log.debug("Automatic correction run={} lint auto-fix: no changes", runId);
      }
    } catch (Exception e) {
      log.warn(
          "Automatic correction run={} lint auto-fix failed (continuing): {}",
          runId,
          e.getMessage());
    }
  }

  public void recordExecuted(
      AgentTask task,
      String decision,
      String provider,
      String model,
      String reason,
      String limitName,
      int usedLimit,
      int maxLimit,
      String source) {
    if (task == null || decision == null) {
      return;
    }
    try {
      ArrayNode actions =
          CorrectionActionJson.read(objectMapper, task.getContinuationActionsJson());
      ObjectNode action = actions.addObject();
      action.put("status", "EXECUTED");
      action.put("decision", decision);
      action.put("provider", provider);
      action.put("model", model);
      action.put("reason", reason == null ? "" : reason);
      action.put("limitName", limitName);
      action.put("usedLimit", usedLimit);
      action.put("maxLimit", maxLimit);
      action.put("source", source);
      action.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepository.saveAndFlush(task);
    } catch (Exception ex) {
      log.warn(
          "automatic correction: failed to append action history for task {}: {}",
          task.getTaskId(),
          ex.getMessage(),
          ex);
    }
  }

  public void recordResolution(
      String taskKey,
      String status,
      String code,
      String reason,
      String beforeGeneration,
      String afterGeneration) {
    recordResolution(
        taskKey, status, code, reason, beforeGeneration, afterGeneration, null, null, false);
  }

  public void recordResolution(
      String taskKey,
      String status,
      String code,
      String reason,
      String beforeGeneration,
      String afterGeneration,
      CorrectionWorkspaceTransaction.DiffEvidence diffEvidence,
      CorrectionResolutionEvidence.ValidationDelta validationDelta,
      boolean evidenceInvalidated) {
    if (taskKey == null || taskKey.isBlank()) {
      return;
    }
    try {
      AgentTask task = taskRepository.findById(taskKey).orElse(null);
      if (task == null) {
        return;
      }
      ArrayNode actions =
          CorrectionActionJson.read(objectMapper, task.getContinuationActionsJson());
      CorrectionResolutionEvidence.append(
          objectMapper,
          actions,
          status,
          code,
          reason,
          beforeGeneration,
          afterGeneration,
          diffEvidence,
          validationDelta,
          evidenceInvalidated);
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepository.saveAndFlush(task);
    } catch (Exception persistenceFailure) {
      throw new IllegalStateException(
          "Could not persist the automatic correction resolution", persistenceFailure);
    }
  }

  public static TaskState snapshotTask(AgentTask task) {
    return CorrectionStateSnapshots.snapshotTask(task);
  }

  public void restoreTask(String taskKey, TaskState state) {
    if (taskKey == null || taskKey.isBlank()) {
      return;
    }
    try {
      taskRepository
          .findById(taskKey)
          .ifPresent(
              task -> {
                task.setStatus(state.status());
                task.setStartedAt(state.startedAt());
                task.setCompletedAt(state.completedAt());
                task.setDurationMs(state.durationMs());
                CorrectionStateSnapshots.restoreTaskEvidence(task, state);
                taskRepository.save(task);
              });
    } catch (RuntimeException ex) {
      log.warn(
          "automatic correction: failed to restore task state for {}: {}",
          taskKey,
          ex.getMessage());
    }
  }

  public record TaskState(
      String status,
      Instant startedAt,
      Instant completedAt,
      Long durationMs,
      String assignedModel,
      String lastLlmFailure,
      Instant lastActivityAt,
      Instant stuckDetectedAt,
      Integer recoveryAttempt,
      String workerReportJson,
      String completionNotes,
      String provider,
      String model,
      String effectiveProvider,
      String effectiveModel,
      String effectiveRouteAttribution,
      String routingTier,
      String routingTaskCategory,
      String routingSource,
      String currentProvider,
      String currentModel,
      Boolean readmeUpdated,
      String failureDiagnosticJson,
      String goalDonenessJson,
      String continuationDecisionJson) {}

  private void markDurableRolledBack(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = durableAttempts.get(checkpoint);
    if (prepared != null) {
      durableCorrectionLifecycle.rolledBack(prepared);
      durableAttempts.remove(checkpoint, prepared);
    }
  }

  private void releaseForRecovery(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    DurableCorrectionLifecycle.Prepared prepared = durableAttempts.remove(checkpoint);
    if (prepared != null) prepared.authority().close();
  }
}
