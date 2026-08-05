package com.navneet.executespec.api;

import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.integration.PlannerFailureDiagnostic;
import com.navneet.executespec.state.entity.AgentTask;

import lombok.extern.slf4j.Slf4j;

/**
 * Pure display-policy helpers for the run read model, extracted from {@link RunMapper} so the
 * mapper stays below the architecture line ceiling. Everything here is stateless and
 * side-effect-free: it translates persisted run/task facts into customer-safe projections without
 * loading state or mutating anything.
 */
@Slf4j
final class RunDisplayPolicy {

  static final int UNPROVEN_COMPLETION_SCORE_CAP = 84;

  private RunDisplayPolicy() {}

  /**
   * A persisted COMPLETE row means the worker stopped at this task boundary; it does not override
   * the task's later evidence-backed goal decision. Keep the persistence status unchanged for
   * crash-resume bookkeeping, but fail closed in the customer-facing DTO whenever goal proof is
   * present and not DONE. Legacy COMPLETE rows without goal proof remain backward compatible.
   */
  static String displayTaskStatus(String persistedStatus, JsonNode goalDoneness) {
    if (!"COMPLETE".equals(persistedStatus) || goalDoneness == null) {
      return persistedStatus;
    }
    String goalState = goalDoneness.path("state").asText("");
    return goalState.isBlank()
            || "DONE".equals(goalState)
            || downstreamValidationDeferred(goalDoneness)
        ? persistedStatus
        : "PARTIAL";
  }

  /**
   * RR-1552 pattern for run-detail task internals: the {@code continuationDecision.policy} subtree
   * carries operator-grade ranking/candidate evidence (rankingPolicy, rankingReasons,
   * topEligibleCandidates, provider availability). For non-privileged viewers it is replaced with
   * an explicit withheld marker — {@code {"included": false, "restrictedReason":
   * "ORG_BILLING_ROLE_REQUIRED"}} — never silently removed, so clients can render "withheld" rather
   * than "no policy". Customer-relevant fields (decision, goal state, recommended next step, proof
   * text) stay visible at the top level and on {@code goalDoneness}.
   */
  static final String CONTINUATION_POLICY_RESTRICTED_REASON = "ORG_BILLING_ROLE_REQUIRED";

  static JsonNode withheldOperatorContinuationPolicy(
      JsonNode continuationDecision, boolean includeOperatorContinuationPolicy) {
    if (includeOperatorContinuationPolicy
        || continuationDecision == null
        || !continuationDecision.isObject()
        || !continuationDecision.has("policy")) {
      return continuationDecision;
    }
    ObjectNode redacted = ((ObjectNode) continuationDecision).deepCopy();
    ObjectNode withheld = redacted.putObject("policy");
    withheld.put("included", false);
    withheld.put("restrictedReason", CONTINUATION_POLICY_RESTRICTED_REASON);
    return redacted;
  }

  /** Display statuses on which the V300 refund ledger is a meaningful question. */
  static boolean isRefundApplicableDisplayStatus(String displayStatus) {
    return displayStatus != null
        && Set.of("FAILED", "ERROR", "PARTIAL", "CANCELLED", "ABORTED", "REJECTED")
            .contains(displayStatus);
  }

  static boolean isTerminalSuccessDisplay(String displayStatus) {
    return "COMPLETE".equals(displayStatus) || "COMPLETED".equals(displayStatus);
  }

  static Integer displayVerifiedScore(Integer persistedScore, String displayStatus) {
    if (persistedScore == null || "COMPLETE".equals(displayStatus)) return persistedScore;
    return Math.min(persistedScore, UNPROVEN_COMPLETION_SCORE_CAP);
  }

  static boolean downstreamValidationDeferred(JsonNode goalDoneness) {
    JsonNode evidence = goalDoneness == null ? null : goalDoneness.path("evidence");
    if (evidence == null || !evidence.isArray()) return false;
    for (JsonNode item : evidence) {
      if ("downstreamValidationDeferred=true".equals(item.asText())) return true;
    }
    return false;
  }

  static String displayRunStatusReason(
      String persistedReason,
      String persistedFailure,
      String persistedStatus,
      String displayStatus) {
    if ("COMPLETE".equals(persistedStatus) && "PARTIAL".equals(displayStatus)) {
      return "TASK_PROOF_INCOMPLETE";
    }
    String projected =
        PlannerFailureDiagnostic.projectStatusReason(persistedReason, persistedFailure);
    if (projected != null && !projected.isBlank()) return projected;
    // FAILED/ERROR rows historically persisted only a coded failureReason, leaving statusReason
    // null while terminalOutcome carried a real code. Fall back to the stable code prefix only —
    // never the raw detail after the first colon, and never free prose that fails the code shape.
    if ("FAILED".equals(persistedStatus) || "ERROR".equals(persistedStatus)) {
      return stableFailureCodePrefix(persistedFailure);
    }
    return projected;
  }

  /**
   * Bounded stable-code prefix of a persisted failure reason, or null when it is not code-shaped.
   */
  private static final Pattern STABLE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

  static String stableFailureCodePrefix(String persistedFailure) {
    if (persistedFailure == null || persistedFailure.isBlank()) return null;
    int colon = persistedFailure.indexOf(':');
    String prefix = (colon < 0 ? persistedFailure : persistedFailure.substring(0, colon)).trim();
    return STABLE_FAILURE_CODE.matcher(prefix).matches() ? prefix : null;
  }

  /**
   * A structured failure value starts with a stable uppercase code token, optionally followed by a
   * bracketed sub-code (e.g. {@code PUBLICATION_BLOCKED[EVIDENCE_STALE]}) and detail after a colon
   * or whitespace. Free prose — including worker success summaries — does not match.
   */
  private static final Pattern STRUCTURED_FAILURE_REASON =
      Pattern.compile(
          "^[A-Z][A-Z0-9_]{2,63}(\\[[A-Z0-9_]{1,64}\\])?(?:$|[:\\s].*)", Pattern.DOTALL);

  static String displayRunFailureReason(
      String persistedFailure, String persistedStatus, String displayStatus) {
    if (isTerminalSuccessDisplay(displayStatus)) {
      // Terminal-success projections must not present success-summary prose as a failure reason.
      // Structured codes (including the bounded PUBLICATION_BLOCKED[<code>] marker consumed by
      // the terminal-outcome contract) are preserved; everything else is suppressed. The durable
      // pipeline_run.failure_reason row is untouched. FAILED/PARTIAL keep the raw value.
      if (persistedFailure == null
          || persistedFailure.isBlank()
          || !STRUCTURED_FAILURE_REASON.matcher(persistedFailure.trim()).matches()) {
        return null;
      }
      return persistedFailure;
    }
    if (persistedFailure != null && !persistedFailure.isBlank()) return persistedFailure;
    return "COMPLETE".equals(persistedStatus) && "PARTIAL".equals(displayStatus)
        ? "one or more tasks lack durable completion proof"
        : persistedFailure;
  }

  /**
   * Pulls a human-readable failure reason from the task's stored report JSON (WorkerReport#blockers
   * for BLOCKED, WorkerReport#selfReview for PARTIAL) or falls back to the lastLlmFailure flag set
   * by the recovery system.
   *
   * <p>Note: {@code self_review} is intentionally skipped for COMPLETE tasks — parent tasks
   * completed from subtask decomposition store "Decomposed into N subtasks: N complete" in {@code
   * self_review}, which is a success note, not a failure reason. That info is already surfaced via
   * {@code completionNotes} in the TaskSummary.
   */
  static String extractFailureReason(ObjectMapper objectMapper, AgentTask t) {
    boolean isComplete = "COMPLETE".equals(t.getStatus());
    if (t.getWorkerReportJson() != null && !t.getWorkerReportJson().isBlank()) {
      try {
        JsonNode report = objectMapper.readTree(t.getWorkerReportJson());
        // BLOCKED tasks: "blockers" field
        String blockers = report.path("blockers").asText("");
        if (!blockers.isBlank()) return blockers;
        // PARTIAL tasks: "self_review" (snake_case from WorkerReport).
        // Skip for COMPLETE tasks — self_review on COMPLETE tasks contains subtask
        // decomposition summaries ("Decomposed into N subtasks: N complete"), which
        // are success notes, not failures. These are already in completionNotes.
        if (!isComplete) {
          String selfReview = report.path("self_review").asText("");
          if (!selfReview.isBlank()) return selfReview;
        }
      } catch (Exception e) {
        // RR-1124 — a non-blank but malformed worker report is corruption, not "no failure
        // reason". Log a repair signal; the lastLlmFailure fallback below still applies.
        log.warn(
            "RR-1124 malformed persisted workerReportJson: taskId={} — failure reason from report"
                + " unavailable ({})",
            t.getTaskId(),
            e.getMessage());
      }
    }
    // Fallback: lastLlmFailure (set by blockTask / recovery)
    String last = t.getLastLlmFailure();
    if (last != null && !last.isBlank()) return last;
    // Final safety net: never return an empty string for FAILED tasks.
    // Empty string is worse than null because the UI's null-check may treat
    // it as "set" and skip the placeholder. Eval Bug D (2026-04-16): S2
    // Anthropic T3 surfaced as `failureReason: ""` and the harness retried
    // silently. Anchor to "unknown" so the UI always shows something.
    if ("FAILED".equals(t.getStatus())) {
      return "unknown — see worker logs for run " + t.getRunId();
    }
    return null;
  }
}
