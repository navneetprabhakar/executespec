package com.navneet.executespec.agentsession;

import static com.navneet.executespec.agentsession.AgentIntentDecisions.billingImpact;
import static com.navneet.executespec.agentsession.AgentIntentDecisions.decision;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agentsession.dto.AgentActionContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

final class AgentCodeChangeIntents {

  private AgentCodeChangeIntents() {}

  static Optional<OrchestratorDecision> decide(
      String message, String lower, InterpretIntentRequest request, ClientCapabilities caps) {
    if (!AgentIntentPatterns.looksLikeCodeChange(lower)) {
      return Optional.empty();
    }
    if (caps != null && caps.localTools()) {
      return Optional.of(localCodeTask(message));
    }
    return Optional.of(cloudRunProposal(message, request));
  }

  private static OrchestratorDecision localCodeTask(String message) {
    String prompt =
        "Handle this as a local working-tree task? The client will still enforce tool approvals.";
    return decision(
        "IMPLEMENTATION_PROPOSAL",
        0.84,
        "local_code_change_request",
        true,
        prompt,
        new TypedAction(
            "local_code_task",
            Map.of("spec", message),
            true,
            billingImpact("local_code_task"),
            prompt),
        false,
        true,
        "Local code task is handled by the client; no /api/runs submission.");
  }

  private static OrchestratorDecision cloudRunProposal(
      String message, InterpretIntentRequest request) {
    boolean hasProject =
        request != null && request.projectId() != null && !request.projectId().isBlank();
    if (!hasProject) {
      return decision(
          "IMPLEMENTATION_PROPOSAL",
          0.8,
          "code_mutation_missing_project_context",
          false,
          "I can turn this into an implementation, but select a project/repository first. No run"
              + " has been proposed.",
          null,
          false,
          false,
          "Missing project/repo context blocks run submission and confirmation.",
          AgentActionContext.requiresProjectSelection());
    }
    Map<String, Object> args = Map.of("spec", message, "projectId", request.projectId());
    AgentActionContext context = AgentActionContext.ready();
    String prompt =
        "Create a cloud run? No run credits are charged until the generated plan is approved.";
    return decision(
        "CODE_RUN_REQUIRED",
        0.8,
        "explicit_code_mutation_request",
        true,
        prompt,
        new TypedAction(
            "create_cloud_run", args, true, billingImpact("create_cloud_run"), prompt, context),
        true,
        true,
        "Clear code mutation request with project context; submit only after confirmation.",
        context);
  }
}
