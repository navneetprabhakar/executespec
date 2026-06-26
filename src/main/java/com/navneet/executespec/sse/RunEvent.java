package com.navneet.executespec.sse;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.navneet.executespec.cost.RunCostTracker;

public record RunEvent(
    String type, // RUN_STATUS | TASK_STATUS | LOG | GATE_OPENED | GATE_RESOLVED
    // COST_UPDATE | QUEUED | COMPLETE
    String runId,
    String taskId,
    String payload,
    Instant timestamp,
    int schemaVersion,
    Map<String, Object> data) {
  public RunEvent(String type, String runId, String taskId, String payload, Instant timestamp) {
    this(type, runId, taskId, payload, timestamp, 1, Map.of());
  }

  public static RunEvent runStatus(String runId, String status) {
    return new RunEvent("RUN_STATUS", runId, null, status, Instant.now());
  }

  public static RunEvent taskStatus(String runId, String taskId, String status) {
    return new RunEvent("TASK_STATUS", runId, taskId, status, Instant.now());
  }

  public static RunEvent log(String runId, String taskId, String line) {
    return new RunEvent("LOG", runId, taskId, line, Instant.now());
  }

  /**
   * Machine-readable task goal-doneness decision. The payload remains a compact human summary for
   * older log consumers; structured clients should read {@code data}.
   */
  public static RunEvent goalDecision(
      String runId, String taskId, String summary, Map<String, Object> data) {
    return new RunEvent(
        "GOAL_DECISION",
        runId,
        taskId,
        summary,
        Instant.now(),
        1,
        data == null ? Map.of() : Map.copyOf(data));
  }

  /**
   * Machine-readable continuation / step-up / step-down / provider-change advice emitted after goal
   * evaluation. This is advisory unless a later executor component explicitly acts on it.
   */
  public static RunEvent continuationDecision(
      String runId, String taskId, String summary, Map<String, Object> data) {
    return new RunEvent(
        "CONTINUATION_DECISION",
        runId,
        taskId,
        summary,
        Instant.now(),
        1,
        data == null ? Map.of() : Map.copyOf(data));
  }

  /**
   * PR-C (2026-05-13) — emitted by {@link com.navneet.executespec.tool.WriteFileTool} and {@link
   * com.navneet.executespec.tool.EditFileTool} after every successful write. Payload is the
   * workspace-relative path so the v2/v3 RunDetail file-tree can update the moment a tool call
   * lands rather than waiting for the 5-second poll.
   *
   * <p>Cheap to emit (one Redis publish per write); listeners debounce client-side so a burst of 30
   * edits in a hot iteration only fires one re-fetch. taskId is non-null when the write happened
   * inside a worker task; null on out-of-band writes (e.g. the initial workspace scaffold).
   */
  public static RunEvent fileWritten(String runId, String taskId, String relativePath) {
    return new RunEvent("FILE_WRITTEN", runId, taskId, relativePath, Instant.now());
  }

  /**
   * Local-hands/remote-brain (RL-2026-05-043): the server-side agent asks the connected client to
   * run a tool against its local working tree. Payload is a JSON {@code ToolCallFrame} ({@code
   * {callId, runId, taskId, name, args}}); the client executes it and POSTs the result envelope to
   * {@code /api/runs/{id}/tool-results}. Only emitted for runs bound to a remote tool session; the
   * default server-executed pipeline never produces this event.
   */
  public static RunEvent toolCall(String runId, String taskId, String frameJson) {
    return new RunEvent("TOOL_CALL", runId, taskId, frameJson, Instant.now());
  }

  public static RunEvent toolCall(
      String runId,
      String taskId,
      String frameJson,
      String callId,
      String toolName,
      String argsJson) {
    return toolCall(runId, taskId, frameJson, callId, toolName, argsJson, null);
  }

  public static RunEvent toolCall(
      String runId,
      String taskId,
      String frameJson,
      String callId,
      String toolName,
      String argsJson,
      String permissionKind) {
    return withData(
        "TOOL_CALL",
        runId,
        taskId,
        frameJson,
        "callId",
        callId,
        "toolName",
        toolName,
        "argsJson",
        argsJson,
        "permissionKind",
        permissionKind);
  }

  /**
   * 2026-05-07 — emitted by {@link com.navneet.executespec.lint.LintPass} after the deterministic
   * auto-fixers run between executor and reviewer. Payload is a one-line summary (e.g. {@code
   * "prettier+spotless-maven · 14 file(s) changed in 3.2s"}). LogPane on the v3 RunSidePanel
   * renders it as a milestone banner.
   */
  public static RunEvent lintAutofixApplied(String runId, String summary) {
    return new RunEvent("LINT_AUTOFIX_APPLIED", runId, null, summary, Instant.now());
  }

  /**
   * 2026-05-07 — emitted when a {@code pull_request_review_comment} webhook arrives for a PR opened
   * by ExecuteSpec. Payload is {@code "<author>|<file>:<line>|<bodyPreview>"} so the v3 RunDetail
   * page can show it in the action row + Chat tab without a refetch round-trip.
   */
  public static RunEvent prCommentReceived(String runId, String summary) {
    return new RunEvent("PR_COMMENT_RECEIVED", runId, null, summary, Instant.now());
  }

  public static RunEvent gateOpened(String runId, String gateType) {
    return new RunEvent("GATE_OPENED", runId, null, gateType, Instant.now());
  }

  /** Gate opened with credits info for plan approval UI. */
  public static RunEvent gateOpenedWithCredits(
      String runId, String gateType, int creditsCharged, double totalEstimatedCost) {
    String payload =
        gateType + "|" + creditsCharged + "|" + String.format("%.2f", totalEstimatedCost);
    return new RunEvent("GATE_OPENED", runId, null, payload, Instant.now());
  }

  public static RunEvent gateResolved(String runId, boolean approved, String comment) {
    return new RunEvent(
        "GATE_RESOLVED", runId, null, approved ? "APPROVED" : "REJECTED", Instant.now());
  }

  public static RunEvent costUpdate(String runId, double cost) {
    return new RunEvent("COST_UPDATE", runId, null, String.valueOf(cost), Instant.now());
  }

  /** Live per-call token usage: payload encoded as "in=X,out=Y,total=Z". */
  public static RunEvent tokenUsage(String runId, int inputTokens, int outputTokens) {
    String payload =
        "in=" + inputTokens + ",out=" + outputTokens + ",total=" + (inputTokens + outputTokens);
    return new RunEvent("TOKEN_USAGE", runId, null, payload, Instant.now());
  }

  public static RunEvent tokenUsage(
      String runId,
      String taskId,
      String provider,
      String model,
      RunCostTracker.Snapshot snapshot) {
    int inputTokens = snapshot == null ? 0 : snapshot.inputTokens();
    int outputTokens = snapshot == null ? 0 : snapshot.outputTokens();
    String payload =
        "in=" + inputTokens + ",out=" + outputTokens + ",total=" + (inputTokens + outputTokens);
    return withData(
        "TOKEN_USAGE",
        runId,
        taskId,
        payload,
        "inputTokens",
        inputTokens,
        "outputTokens",
        outputTokens,
        "totalTokens",
        inputTokens + outputTokens,
        "cacheReadTokens",
        snapshot == null ? 0 : snapshot.cacheReadTokens(),
        "cacheWriteTokens",
        snapshot == null ? 0 : snapshot.cacheWriteTokens(),
        "reasoningTokens",
        snapshot == null ? 0 : snapshot.reasoningTokens(),
        "calls",
        snapshot == null ? 0 : snapshot.calls(),
        "costUsd",
        snapshot == null ? 0.0 : snapshot.costUsd(),
        "provider",
        provider,
        "model",
        model);
  }

  public static RunEvent queued(String runId, int position) {
    return new RunEvent("QUEUED", runId, null, String.valueOf(position), Instant.now());
  }

  public static RunEvent complete(String runId, String status) {
    return new RunEvent("COMPLETE", runId, null, status, Instant.now());
  }

  /**
   * v2.5.10 — complete event that carries an explicit statusReason so the UI can decorate PARTIAL /
   * COMPLETE badges with a tooltip (e.g. "PARTIAL because FINAL_BUILD_FAILED"). Payload format:
   * "{status}|{reason}". When reason is null, payload is "{status}" (identical to the 2-arg
   * overload).
   */
  public static RunEvent complete(String runId, String status, String reason) {
    String payload = (reason == null || reason.isBlank()) ? status : status + "|" + reason;
    return new RunEvent("COMPLETE", runId, null, payload, Instant.now());
  }

  public static RunEvent clarificationNeeded(String runId, String prompt) {
    return new RunEvent("CLARIFICATION_NEEDED", runId, null, prompt, Instant.now());
  }

  public static RunEvent clarificationResolved(String runId) {
    return new RunEvent("CLARIFICATION_RESOLVED", runId, null, null, Instant.now());
  }

  public static RunEvent contextExtracted(String runId, String summary) {
    return new RunEvent("CONTEXT_EXTRACTED", runId, null, summary, Instant.now());
  }

  public static RunEvent recoveryAttempted(
      String runId, String taskId, String classification, String action, int locksReleased) {
    String payload = classification + "|" + action + "|locks=" + locksReleased;
    return new RunEvent("RECOVERY_ATTEMPTED", runId, taskId, payload, Instant.now());
  }

  public static RunEvent runRestarted(String originalRunId, String newRunId) {
    return new RunEvent("RUN_RESTARTED", originalRunId, null, newRunId, Instant.now());
  }

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

  // Deploy Engine events (Path A/B/C — user's GCP project)

  public static RunEvent engineDeployStarted(String runId, String version, String path) {
    return new RunEvent("ENGINE_DEPLOY_STARTED", runId, null, version + "|" + path, Instant.now());
  }

  public static RunEvent enginePhase(String runId, String phase, String message) {
    return new RunEvent("ENGINE_PHASE", runId, null, phase + "|" + message, Instant.now());
  }

  public static RunEvent engineBuilding(String runId, String version) {
    return new RunEvent("ENGINE_BUILDING", runId, null, version, Instant.now());
  }

  public static RunEvent engineDeploying(String runId, String version) {
    return new RunEvent("ENGINE_DEPLOYING", runId, null, version, Instant.now());
  }

  public static RunEvent engineDeployLive(String runId, String version, String serviceUrl) {
    return new RunEvent(
        "ENGINE_DEPLOY_LIVE", runId, null, version + "|" + serviceUrl, Instant.now());
  }

  public static RunEvent engineDeployFailed(String runId, String version, String error) {
    return new RunEvent("ENGINE_DEPLOY_FAILED", runId, null, version + "|" + error, Instant.now());
  }

  // Tier 8: Compile + API spec + preview events

  public static RunEvent apiSpecReady(String runId) {
    return new RunEvent("API_SPEC_READY", runId, null, "openapi.yaml", Instant.now());
  }

  public static RunEvent previewReady(String runId, String serveDir) {
    return new RunEvent(
        "PREVIEW_READY", runId, null, serveDir != null ? serveDir : "", Instant.now());
  }

  // Validation + Model Router events

  public static RunEvent validationStarted(String runId, String taskId, String file) {
    return new RunEvent("VALIDATION_STARTED", runId, taskId, file, Instant.now());
  }

  public static RunEvent validationPassed(String runId, String taskId) {
    return new RunEvent("VALIDATION_PASSED", runId, taskId, null, Instant.now());
  }

  public static RunEvent validationFailed(String runId, String taskId, String summary) {
    return new RunEvent("VALIDATION_FAILED", runId, taskId, summary, Instant.now());
  }

  public static RunEvent modelEscalated(
      String runId, String taskId, String fromModel, String toModel) {
    return new RunEvent("MODEL_ESCALATED", runId, taskId, fromModel + "|" + toModel, Instant.now());
  }

  public static RunEvent compactionTriggered(String runId, String taskId) {
    return new RunEvent("COMPACTION_TRIGGERED", runId, taskId, null, Instant.now());
  }

  public static RunEvent reviewStarted(String runId, String model) {
    return new RunEvent("REVIEW_STARTED", runId, null, model, Instant.now());
  }

  public static RunEvent reviewIssueFound(
      String runId, String issueId, String severity, String description) {
    return new RunEvent(
        "REVIEW_ISSUE_FOUND",
        runId,
        null,
        issueId + "|" + severity + "|" + description,
        Instant.now());
  }

  public static RunEvent reviewAutoFixStarted(String runId, String issueId) {
    return new RunEvent("REVIEW_AUTO_FIX_STARTED", runId, null, issueId, Instant.now());
  }

  public static RunEvent reviewAutoFixResult(String runId, String issueId, boolean success) {
    return new RunEvent(
        "REVIEW_AUTO_FIX_RESULT",
        runId,
        null,
        issueId + "|" + (success ? "PASS" : "FAIL"),
        Instant.now());
  }

  public static RunEvent reviewComplete(String runId, String verdict, String summary) {
    return new RunEvent("REVIEW_COMPLETE", runId, null, verdict + "|" + summary, Instant.now());
  }

  public static RunEvent testGenStarted(String runId) {
    return new RunEvent("TEST_GEN_STARTED", runId, null, null, Instant.now());
  }

  public static RunEvent testPlanningStarted(String runId) {
    return new RunEvent("TEST_PLANNING_STARTED", runId, null, null, Instant.now());
  }

  public static RunEvent testPlanningCompleted(String runId, int taskCount) {
    return new RunEvent(
        "TEST_PLANNING_COMPLETED", runId, null, String.valueOf(taskCount), Instant.now());
  }

  public static RunEvent testGenerationStarted(String runId, int taskCount) {
    return new RunEvent(
        "TEST_GENERATION_STARTED", runId, null, String.valueOf(taskCount), Instant.now());
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

  public static RunEvent validationReportReady(String runId) {
    return new RunEvent("VALIDATION_REPORT_READY", runId, null, null, Instant.now());
  }

  public static RunEvent testReportReady(String runId, String reportJson) {
    return new RunEvent("TEST_REPORT_READY", runId, null, reportJson, Instant.now());
  }

  public static RunEvent testPipelineFailed(String runId, String error) {
    return new RunEvent("TEST_PIPELINE_FAILED", runId, null, error, Instant.now());
  }

  /**
   * Emitted when a run's accumulated cost exceeds its per-run budget. Payload format:
   * tier|budget|message
   */
  public static RunEvent taskDecomposed(String runId, String parentTaskId, int subtaskCount) {
    return new RunEvent(
        "TASK_DECOMPOSED", runId, parentTaskId, String.valueOf(subtaskCount), Instant.now());
  }

  public static RunEvent budgetWarning(
      String runId, String tier, double budgetUsd, int percentUsed, double currentCost) {
    String message =
        "Cost warning: $"
            + String.format("%.2f", currentCost)
            + " of $"
            + String.format("%.0f", budgetUsd)
            + " budget used ("
            + percentUsed
            + "%).";
    if (percentUsed >= 90) {
      message += " Run will stop soon if cost limit is reached.";
    }
    return new RunEvent(
        "BUDGET_WARNING",
        runId,
        null,
        tier
            + "|"
            + String.format("%.2f", budgetUsd)
            + "|"
            + percentUsed
            + "|"
            + String.format("%.2f", currentCost)
            + "|"
            + message,
        Instant.now());
  }

  public static RunEvent batchProgress(String runId, int completed, int total) {
    return new RunEvent("BATCH_PROGRESS", runId, null, completed + "/" + total, Instant.now());
  }

  public static RunEvent batchComplete(String runId) {
    return new RunEvent("BATCH_COMPLETE", runId, null, "Batch processing finished", Instant.now());
  }

  public static RunEvent batchFailed(String runId, String error) {
    return new RunEvent("BATCH_FAILED", runId, null, error, Instant.now());
  }

  public static RunEvent budgetExceeded(String runId, String tier, double budgetUsd) {
    // R4 (2026-05-01 follow-up Task 7) — V165 retired MAX from the
    // ladder; the user-facing string still named it. Updated to the
    // post-V165 ladder (FREE → PRO grandfathered → TEAM → ENTERPRISE).
    String message =
        switch (tier != null ? tier.toUpperCase() : "FREE") {
          case "PRO" ->
              "Run stopped — $"
                  + String.format("%.0f", budgetUsd)
                  + " cost limit reached. Add your own API keys (BYOK) for unlimited runs.";
          default ->
              "Run stopped — $"
                  + String.format("%.0f", budgetUsd)
                  + " cost limit reached. Upgrade to Team for unlimited BYOK runs.";
        };
    return new RunEvent(
        "BUDGET_EXCEEDED",
        runId,
        null,
        tier + "|" + String.format("%.2f", budgetUsd) + "|" + message,
        Instant.now());
  }

  private static RunEvent withData(
      String type, String runId, String taskId, String payload, Object... pairs) {
    return new RunEvent(type, runId, taskId, payload, Instant.now(), 1, data(pairs));
  }

  private static Map<String, Object> data(Object... pairs) {
    if (pairs == null || pairs.length == 0) return Map.of();
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      Object key = pairs[i];
      if (key != null && pairs[i + 1] != null) out.put(String.valueOf(key), pairs[i + 1]);
    }
    return Map.copyOf(out);
  }
}
