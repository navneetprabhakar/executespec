package com.navneet.executespec.sse;

import java.time.Instant;

public record RunEvent(
        String type,      // RUN_STATUS | TASK_STATUS | LOG | GATE_OPENED | GATE_RESOLVED
                          // COST_UPDATE | QUEUED | COMPLETE
        String runId,
        String taskId,
        String payload,
        Instant timestamp
) {
    public static RunEvent runStatus(String runId, String status) {
        return new RunEvent("RUN_STATUS", runId, null, status, Instant.now());
    }

    public static RunEvent taskStatus(String runId, String taskId, String status) {
        return new RunEvent("TASK_STATUS", runId, taskId, status, Instant.now());
    }

    public static RunEvent log(String runId, String taskId, String line) {
        return new RunEvent("LOG", runId, taskId, line, Instant.now());
    }

    public static RunEvent gateOpened(String runId, String gateType) {
        return new RunEvent("GATE_OPENED", runId, null, gateType, Instant.now());
    }

    public static RunEvent gateResolved(String runId, boolean approved, String comment) {
        return new RunEvent("GATE_RESOLVED", runId, null,
                approved ? "APPROVED" : "REJECTED", Instant.now());
    }

    public static RunEvent costUpdate(String runId, double cost) {
        return new RunEvent("COST_UPDATE", runId, null, String.valueOf(cost), Instant.now());
    }

    public static RunEvent queued(String runId, int position) {
        return new RunEvent("QUEUED", runId, null, String.valueOf(position), Instant.now());
    }

    public static RunEvent complete(String runId, String status) {
        return new RunEvent("COMPLETE", runId, null, status, Instant.now());
    }

    // ── Context clarification events ──────────────────────────────────────────

    public static RunEvent clarificationNeeded(String runId, String prompt) {
        return new RunEvent("CLARIFICATION_NEEDED", runId, null, prompt, Instant.now());
    }

    public static RunEvent clarificationResolved(String runId) {
        return new RunEvent("CLARIFICATION_RESOLVED", runId, null, null, Instant.now());
    }

    public static RunEvent contextExtracted(String runId, String summary) {
        return new RunEvent("CONTEXT_EXTRACTED", runId, null, summary, Instant.now());
    }

    // ── Tier 5: Recovery events ───────────────────────────────────────────────

    public static RunEvent recoveryAttempted(String runId, String taskId,
                                             String classification, String action,
                                             int locksReleased) {
        String payload = classification + "|" + action + "|locks=" + locksReleased;
        return new RunEvent("RECOVERY_ATTEMPTED", runId, taskId, payload, Instant.now());
    }

    public static RunEvent runRestarted(String originalRunId, String newRunId) {
        return new RunEvent("RUN_RESTARTED", originalRunId, null, newRunId, Instant.now());
    }

    // ── Tier 6: Deploy events ─────────────────────────────────────────────────

    public static RunEvent deployStarted(String runId, String version, String strategy) {
        return new RunEvent("DEPLOY_STARTED", runId, null, version + "|" + strategy, Instant.now());
    }

    public static RunEvent deployBuilding(String runId, String version) {
        return new RunEvent("DEPLOY_BUILDING", runId, null, version, Instant.now());
    }

    public static RunEvent deployPushing(String runId, String version) {
        return new RunEvent("DEPLOY_PUSHING", runId, null, version, Instant.now());
    }

    public static RunEvent deployLive(String runId, String version, String serviceUrl) {
        return new RunEvent("DEPLOY_LIVE", runId, null, version + "|" + serviceUrl, Instant.now());
    }

    public static RunEvent deployFailed(String runId, String version, String error) {
        return new RunEvent("DEPLOY_FAILED", runId, null, version + "|" + error, Instant.now());
    }

    public static RunEvent deployDeleted(String runId, String version, String reason) {
        return new RunEvent("DEPLOY_DELETED", runId, null, version + "|" + reason, Instant.now());
    }

    // ── Deploy Engine events (Path A/B/C — user's GCP project) ─────────────────

    public static RunEvent engineDeployStarted(String runId, String version, String path) {
        return new RunEvent("ENGINE_DEPLOY_STARTED", runId, null,
                version + "|" + path, Instant.now());
    }

    public static RunEvent enginePhase(String runId, String phase, String message) {
        return new RunEvent("ENGINE_PHASE", runId, null,
                phase + "|" + message, Instant.now());
    }

    public static RunEvent engineBuilding(String runId, String version) {
        return new RunEvent("ENGINE_BUILDING", runId, null, version, Instant.now());
    }

    public static RunEvent engineDeploying(String runId, String version) {
        return new RunEvent("ENGINE_DEPLOYING", runId, null, version, Instant.now());
    }

    public static RunEvent engineDeployLive(String runId, String version, String serviceUrl) {
        return new RunEvent("ENGINE_DEPLOY_LIVE", runId, null,
                version + "|" + serviceUrl, Instant.now());
    }

    public static RunEvent engineDeployFailed(String runId, String version, String error) {
        return new RunEvent("ENGINE_DEPLOY_FAILED", runId, null,
                version + "|" + error, Instant.now());
    }

    // ── Tier 8: Compile + API spec + preview events ───────────────────────────

    public static RunEvent apiSpecReady(String runId) {
        return new RunEvent("API_SPEC_READY", runId, null, "openapi.yaml", Instant.now());
    }

    public static RunEvent previewReady(String runId, String serveDir) {
        return new RunEvent("PREVIEW_READY", runId, null,
            serveDir != null ? serveDir : "", Instant.now());
    }

    // ── Validation + Model Router events ──────────────────────────────────────

    public static RunEvent validationStarted(String runId, String taskId, String file) {
        return new RunEvent("VALIDATION_STARTED", runId, taskId, file, Instant.now());
    }

    public static RunEvent validationPassed(String runId, String taskId) {
        return new RunEvent("VALIDATION_PASSED", runId, taskId, null, Instant.now());
    }

    public static RunEvent validationFailed(String runId, String taskId, String summary) {
        return new RunEvent("VALIDATION_FAILED", runId, taskId, summary, Instant.now());
    }

    public static RunEvent modelEscalated(String runId, String taskId, String fromModel, String toModel) {
        return new RunEvent("MODEL_ESCALATED", runId, taskId, fromModel + "|" + toModel, Instant.now());
    }

    // ── Compaction events ──────────────────────────────────────────────────

    public static RunEvent compactionTriggered(String runId, String taskId) {
        return new RunEvent("COMPACTION_TRIGGERED", runId, taskId, null, Instant.now());
    }

    // ── Review V2 events ─────────────────────────────────────────────────────

    public static RunEvent reviewStarted(String runId, String model) {
        return new RunEvent("REVIEW_STARTED", runId, null, model, Instant.now());
    }

    public static RunEvent reviewIssueFound(String runId, String issueId, String severity, String description) {
        return new RunEvent("REVIEW_ISSUE_FOUND", runId, null,
                issueId + "|" + severity + "|" + description, Instant.now());
    }

    public static RunEvent reviewAutoFixStarted(String runId, String issueId) {
        return new RunEvent("REVIEW_AUTO_FIX_STARTED", runId, null, issueId, Instant.now());
    }

    public static RunEvent reviewAutoFixResult(String runId, String issueId, boolean success) {
        return new RunEvent("REVIEW_AUTO_FIX_RESULT", runId, null,
                issueId + "|" + (success ? "PASS" : "FAIL"), Instant.now());
    }

    public static RunEvent reviewComplete(String runId, String verdict, String summary) {
        return new RunEvent("REVIEW_COMPLETE", runId, null, verdict + "|" + summary, Instant.now());
    }

    // ── Test Generation Pipeline events ─────────────────────────────────────

    public static RunEvent testGenStarted(String runId) {
        return new RunEvent("TEST_GEN_STARTED", runId, null, null, Instant.now());
    }

    public static RunEvent testPlanningStarted(String runId) {
        return new RunEvent("TEST_PLANNING_STARTED", runId, null, null, Instant.now());
    }

    public static RunEvent testPlanningCompleted(String runId, int taskCount) {
        return new RunEvent("TEST_PLANNING_COMPLETED", runId, null, String.valueOf(taskCount), Instant.now());
    }

    public static RunEvent testGenerationStarted(String runId, int taskCount) {
        return new RunEvent("TEST_GENERATION_STARTED", runId, null, String.valueOf(taskCount), Instant.now());
    }

    public static RunEvent testCompilationStarted(String runId) {
        return new RunEvent("TEST_COMPILATION_STARTED", runId, null, null, Instant.now());
    }

    public static RunEvent testCompilationPassed(String runId, String message) {
        return new RunEvent("TEST_COMPILATION_PASSED", runId, null, message, Instant.now());
    }

    public static RunEvent testCompilationFailed(String runId, String message) {
        return new RunEvent("TEST_COMPILATION_FAILED", runId, null, message, Instant.now());
    }

    public static RunEvent testExecutionStarted(String runId) {
        return new RunEvent("TEST_EXECUTION_STARTED", runId, null, null, Instant.now());
    }

    public static RunEvent testExecutionSkipped(String runId, String reason) {
        return new RunEvent("TEST_EXECUTION_SKIPPED", runId, null, reason, Instant.now());
    }

    public static RunEvent testReportReady(String runId, String reportJson) {
        return new RunEvent("TEST_REPORT_READY", runId, null, reportJson, Instant.now());
    }

    public static RunEvent testPipelineFailed(String runId, String error) {
        return new RunEvent("TEST_PIPELINE_FAILED", runId, null, error, Instant.now());
    }
}
