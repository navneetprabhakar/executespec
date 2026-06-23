package com.navneet.executespec.agentsession;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;

final class AgentIntentLegacyAdapter {

  private AgentIntentLegacyAdapter() {}

  static IntentDecision toLegacy(OrchestratorDecision decision) {
    String response =
        decision.requiresUserConfirmation()
            ? null
            : AgentText.firstNonBlank(
                decision.assistantReply(), fallbackResponse(decision.route()));
    String confirmationPrompt =
        decision.requiresUserConfirmation()
            ? AgentText.firstNonBlank(
                decision.assistantReply(),
                decision.proposedAction() == null
                    ? null
                    : decision.proposedAction().confirmationPrompt())
            : null;
    return new IntentDecision(
        legacyIntent(decision),
        decision.confidence(),
        decision.requiresUserConfirmation(),
        confirmationPrompt,
        decision.proposedAction(),
        response);
  }

  private static String legacyIntent(OrchestratorDecision decision) {
    if (decision.proposedAction() != null) {
      return switch (decision.proposedAction().type()) {
        case "approve_run" -> "APPROVE_GATE";
        case "reject_run", "cancel_run" -> "REJECT_GATE";
        case "list_gates" -> "LIST_GATES";
        case "cost_query" -> "COST_QUERY";
        case "local_code_task" -> "LOCAL_CODE_TASK";
        case "create_cloud_run" -> "CREATE_RUN";
        case "resolve_project" -> "RESOLVE_PROJECT";
        default -> decision.route();
      };
    }
    return switch (decision.route()) {
      case "CHAT_ONLY", "READ_ONLY_CODE_HELP", "PLAN_ONLY", "REVIEW_ONLY", "CLARIFY" -> "CHAT";
      default -> decision.route();
    };
  }

  private static String fallbackResponse(String route) {
    return switch (route) {
      case "READ_ONLY_CODE_HELP" -> "I will answer this without creating a run.";
      case "PLAN_ONLY" -> "I will write the plan without creating a run.";
      case "REVIEW_ONLY" -> "I will review this without creating a run.";
      case "CLARIFY" -> "I need one clarification before proceeding.";
      default -> "I will keep this as chat and will not create a run.";
    };
  }
}
