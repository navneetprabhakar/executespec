package com.navneet.executespec.agentsession;

import com.navneet.executespec.agentsession.dto.AgentActionContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

final class AgentIntentDecisions {

  static final String CREATE_CLOUD_RUN = "create_cloud_run";

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
    return decision(
        route,
        confidence,
        reasonCode,
        requiresConfirmation,
        assistantReply,
        action,
        runApiAllowed,
        confirmationRequiredBeforeSubmit,
        policyReason,
        null);
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
      String policyReason,
      AgentActionContext actionContext) {
    assertCloudRunInvariant(action, actionContext, runApiAllowed);
    return new OrchestratorDecision(
        route,
        confidence,
        reasonCode,
        requiresConfirmation,
        assistantReply,
        action,
        new OrchestratorPolicy(runApiAllowed, confirmationRequiredBeforeSubmit, policyReason),
        null,
        actionContext);
  }

  /**
   * Fail-closed guard for the Phase 4 invariant: a decision without a confirmable typed context, or
   * without a project/repository to run against, must never carry a confirmable {@code
   * create_cloud_run} action. This is enforced structurally rather than by convention so a future
   * intent branch cannot reintroduce a non-executable but clickable run proposal.
   *
   * <p>The context is also where missing write authority is expressed ({@link
   * AgentActionContext#writeScopeRequired()}), so a {@code create_cloud_run} proposal must always
   * carry one: an absent context would silently skip both the project-selection and the write-scope
   * check.
   */
  private static void assertCloudRunInvariant(
      TypedAction action, AgentActionContext actionContext, boolean runApiAllowed) {
    if (action == null || !CREATE_CLOUD_RUN.equals(action.type())) return;
    if (!runApiAllowed) {
      throw new IllegalStateException("create_cloud_run proposed while the Run API is disallowed");
    }
    Object projectId = action.arguments() == null ? null : action.arguments().get("projectId");
    if (!(projectId instanceof String value) || value.isBlank()) {
      throw new IllegalStateException("create_cloud_run proposed without project context");
    }
    if (actionContext == null) {
      throw new IllegalStateException("create_cloud_run proposed without a typed action context");
    }
    if (!actionContext.confirmable()) {
      throw new IllegalStateException(
          "create_cloud_run proposed with a blocked context: " + actionContext.blockingReasons());
    }
  }

  static String billingImpact(String actionType) {
    return switch (actionType) {
      case "approve_run" -> "run_credit";
      case "cancel_run" -> "none";
      case "create_cloud_run" -> "run_credit_on_plan_approval";
      case "local_code_task" -> "conversation_usage";
      default -> "none";
    };
  }
}
