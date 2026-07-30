package com.navneet.executespec.integration;

import java.nio.file.Path;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.harness.ExecutionResult;
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

import org.springframework.beans.BeanUtils;
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

  public CorrectionWorkspaceTransaction.Checkpoint begin(Path workspaceRoot) {
    return workspaceTransaction.begin(workspaceRoot);
  }

  public CorrectionWorkspaceTransaction.Accepted accept(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return workspaceTransaction.accept(checkpoint);
  }

  public String currentGeneration(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return workspaceTransaction.currentGeneration(checkpoint);
  }

  public CorrectionWorkspaceTransaction.DiffEvidence diffEvidence(
      CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    return workspaceTransaction.diffEvidence(checkpoint);
  }

  public void rollback(CorrectionWorkspaceTransaction.Checkpoint checkpoint) {
    workspaceTransaction.rollback(checkpoint);
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
    } catch (RuntimeException rollbackFailure) {
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
            .map(AutomaticCorrectionLifecycle::copy)
            .orElse(null);
    if (previous != null) {
      ValidationReport stale = copy(previous);
      markIndependentEvidenceStale(stale);
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
      validationReportRepository.saveAndFlush(copy(checkpoint.previous()));
      return;
    }
    validationReportRepository
        .findByRunId(checkpoint.runId())
        .ifPresent(validationReportRepository::delete);
    validationReportRepository.flush();
  }

  private static ValidationReport copy(ValidationReport source) {
    ValidationReport target = new ValidationReport();
    BeanUtils.copyProperties(source, target);
    return target;
  }

  static void markIndependentEvidenceStale(ValidationReport report) {
    report.setStatus("STALE");
    report.setWorkspaceGeneration(null);
    report.setFinalBuildPassed(null);
    report.setBuildCommand(null);
    report.setBuildWorkingDirectory(null);
    report.setBuildExitCode(null);
    report.setBuildOutput(null);
    report.setTestsPassed(null);
    report.setTestsTotal(null);
    report.setTestExecutionVerified(null);
    report.setTestEvidenceScope(null);
    report.setTestCommand(null);
    report.setTestWorkingDirectory(null);
    report.setTestExitCode(null);
    report.setTestOutput(null);
    report.setReviewerVerdict(null);
    report.setWorkflowConfigEvidenceJson(null);
    report.setFilesGenerated(-1);
    report.setChecksPassed("");
    report.setIssuesJson("[]");
    report.setReportMarkdown(null);
    report.setAccuracyScore(null);
    report.setAccuracyGrade(null);
    report.setAccuracyJson(null);
    report.setCodeQualityScore(null);
    report.setTestCoverageScore(null);
    report.setArchitectureScore(null);
    report.setSecurityScore(null);
    report.setPerformanceScore(null);
    report.setDesignComplianceScore(null);
    report.setBootVerdict("STALE");
    report.setBootHealthy(null);
    report.setBootReason(null);
    report.setExecutableTestsRun(null);
    report.setExecutableTestsPassed(null);
    report.setExecutableVerificationStatus("STALE");
    report.setExecutableEvidenceJson(null);
    report.setVerifiedScore(null);
    report.setRubricScore(null);
    report.setScoreZeroCauseJson(null);
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
      ArrayNode actions = readActions(task.getContinuationActionsJson());
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
      ArrayNode actions = readActions(task.getContinuationActionsJson());
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

  public TaskState snapshotTask(AgentTask task) {
    return new TaskState(
        task.getStatus(), task.getStartedAt(), task.getCompletedAt(), task.getDurationMs());
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
                taskRepository.save(task);
              });
    } catch (RuntimeException ex) {
      log.warn(
          "automatic correction: failed to restore task state for {}: {}",
          taskKey,
          ex.getMessage());
    }
  }

  public record TaskState(String status, Instant startedAt, Instant completedAt, Long durationMs) {}

  private ArrayNode readActions(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (json == null || json.isBlank()) {
      return objectMapper.createArrayNode();
    }
    JsonNode parsed = objectMapper.readTree(json);
    if (parsed != null && parsed.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      parsed.forEach(copy::add);
      return copy;
    }
    return objectMapper.createArrayNode();
  }
}
