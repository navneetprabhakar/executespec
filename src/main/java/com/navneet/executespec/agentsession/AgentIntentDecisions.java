package com.navneet.executespec.agentsession;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

final class AgentIntentDecisions {

  private AgentIntentDecisions() {}

  static OrchestratorDecision decision(
      String route,
      double confidence,
      TypedAction action,
      String reasonCode,
      String assistantReply) {
    return decision(
        route,
        confidence,
        reasonCode,
        action != null && action.requiresConfirmation(),
        assistantReply,
        action,
        false,
        action != null && action.requiresConfirmation(),
        "No new run submission is allowed for this route.");
  }

  static OrchestratorDecision decision(
      String route,
      double confidence,
      String reasonCode,
      boolean requiresConfirmation,
      String assistantReply,
      TypedAction action,
      boolean runApiAllowed,
      boolean confirmationRequiredBeforeSubmit,
      String policyReason) {
    return new OrchestratorDecision(
        route,
        confidence,
        reasonCode,
        requiresConfirmation,
        assistantReply,
        action,
        new OrchestratorPolicy(runApiAllowed, confirmationRequiredBeforeSubmit, policyReason),
        null);
  }

  static String billingImpact(String actionType) {
    return switch (actionType) {
      case "approve_run" -> "run_credit";
      case "create_cloud_run" -> "run_credit_on_plan_approval";
      case "local_code_task" -> "conversation_usage";
      default -> "none";
    };
  }
}
