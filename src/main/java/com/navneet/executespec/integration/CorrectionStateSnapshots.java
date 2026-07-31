package com.navneet.executespec.integration;

import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;

import org.springframework.beans.BeanUtils;

/** Byte-for-byte state snapshots used when an automatic correction must be rolled back. */
final class CorrectionStateSnapshots {

  private CorrectionStateSnapshots() {}

  static ValidationReport copyValidationReport(ValidationReport source) {
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

  static AutomaticCorrectionLifecycle.TaskState snapshotTask(AgentTask task) {
    return new AutomaticCorrectionLifecycle.TaskState(
        task.getStatus(),
        task.getStartedAt(),
        task.getCompletedAt(),
        task.getDurationMs(),
        task.getAssignedModel(),
        task.getLastLlmFailure(),
        task.getLastActivityAt(),
        task.getStuckDetectedAt(),
        task.getRecoveryAttempt(),
        task.getWorkerReportJson(),
        task.getCompletionNotes(),
        task.getProvider(),
        task.getModel(),
        task.getEffectiveProvider(),
        task.getEffectiveModel(),
        task.getEffectiveRouteAttribution(),
        task.getRoutingTier(),
        task.getRoutingTaskCategory(),
        task.getRoutingSource(),
        task.getCurrentProvider(),
        task.getCurrentModel(),
        task.getReadmeUpdated(),
        task.getFailureDiagnosticJson(),
        task.getGoalDonenessJson(),
        task.getContinuationDecisionJson());
  }

  static void restoreTaskEvidence(AgentTask task, AutomaticCorrectionLifecycle.TaskState state) {
    task.setAssignedModel(state.assignedModel());
    task.setLastLlmFailure(state.lastLlmFailure());
    task.setLastActivityAt(state.lastActivityAt());
    task.setStuckDetectedAt(state.stuckDetectedAt());
    task.setRecoveryAttempt(state.recoveryAttempt());
    task.setWorkerReportJson(state.workerReportJson());
    task.setCompletionNotes(state.completionNotes());
    task.setProvider(state.provider());
    task.setModel(state.model());
    task.setEffectiveProvider(state.effectiveProvider());
    task.setEffectiveModel(state.effectiveModel());
    task.setEffectiveRouteAttribution(state.effectiveRouteAttribution());
    task.setRoutingTier(state.routingTier());
    task.setRoutingTaskCategory(state.routingTaskCategory());
    task.setRoutingSource(state.routingSource());
    task.setCurrentProvider(state.currentProvider());
    task.setCurrentModel(state.currentModel());
    task.setReadmeUpdated(state.readmeUpdated());
    task.setFailureDiagnosticJson(state.failureDiagnosticJson());
    task.setGoalDonenessJson(state.goalDonenessJson());
    task.setContinuationDecisionJson(state.continuationDecisionJson());
  }
}
