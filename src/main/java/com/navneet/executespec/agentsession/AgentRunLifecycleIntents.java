package com.navneet.executespec.agentsession;

import static com.navneet.executespec.agentsession.AgentIntentDecisions.billingImpact;
import static com.navneet.executespec.agentsession.AgentIntentDecisions.decision;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

final class AgentRunLifecycleIntents {

  private AgentRunLifecycleIntents() {}

  static Optional<OrchestratorDecision> decide(String message, String lower) {
    if (AgentIntentPatterns.containsAny(lower, "approve", "approved")) {
      return Optional.of(approveDecision(message));
    }
    if (AgentIntentPatterns.containsAny(lower, "reject", "cancel", "decline")) {
      return Optional.of(rejectOrCancelDecision(message, lower));
    }
    if (AgentIntentPatterns.containsAny(
            lower, "pending approval", "awaiting approval", "approval", "gates")
        || AgentIntentPatterns.containsAny(
            lower, "list runs", "show runs", "recent runs", "run status")) {
      return Optional.of(
          decision(
              "RUN_LIFECYCLE_ACTION",
              0.95,
              new TypedAction("list_gates", Map.of(), false, "none", null),
              "list_or_show_runs",
              "I will list runs or gates without creating a new run."));
    }
    return Optional.empty();
  }

  private static OrchestratorDecision approveDecision(String message) {
    String runId = AgentIntentPatterns.extractRunId(message);
    if (runId == null) {
      return decision(
          "CLARIFY",
          0.86,
          "run_lifecycle_missing_run_id",
          false,
          "Which run should I approve? I need a specific run before creating an approval"
              + " confirmation.",
          null,
          false,
          false,
          "Approval needs a run id and must not submit a new run.");
    }
    String prompt =
        "Approve run " + runId + "? This may charge run credits through the existing plan gate.";
    return decision(
        "RUN_LIFECYCLE_ACTION",
        0.9,
        "approve_gate",
        true,
        prompt,
        new TypedAction(
            "approve_run", Map.of("runId", runId), true, billingImpact("approve_run"), prompt),
        false,
        true,
        "Run lifecycle action only; no new run submission is allowed.");
  }

  private static OrchestratorDecision rejectOrCancelDecision(String message, String lower) {
    String runId = AgentIntentPatterns.extractRunId(message);
    if (runId == null) {
      return decision(
          "CLARIFY",
          0.86,
          "run_lifecycle_missing_run_id",
          false,
          "Which run should I reject or cancel? I need a specific run id.",
          null,
          false,
          false,
          "Run lifecycle action needs a run id and must not submit a new run.");
    }
    String actionType = lower.contains("cancel") ? "cancel_run" : "reject_run";
    String prompt = ("cancel_run".equals(actionType) ? "Cancel run " : "Reject run ") + runId + "?";
    return decision(
        "RUN_LIFECYCLE_ACTION",
        0.9,
        "reject_or_cancel_gate",
        true,
        prompt,
        new TypedAction(
            actionType, Map.of("runId", runId), true, billingImpact(actionType), prompt),
        false,
        true,
        "Run lifecycle action only; no new run submission is allowed.");
  }
}
