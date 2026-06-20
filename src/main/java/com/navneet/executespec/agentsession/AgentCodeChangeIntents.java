package com.navneet.executespec.agentsession;

import static com.navneet.executespec.agentsession.AgentIntentDecisions.billingImpact;
import static com.navneet.executespec.agentsession.AgentIntentDecisions.decision;

import java.util.Map;
import java.util.Optional;

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
    Map<String, Object> args =
        request != null && request.projectId() != null && !request.projectId().isBlank()
            ? Map.of("spec", message, "projectId", request.projectId())
            : Map.of("spec", message);
    boolean hasProject = args.containsKey("projectId");
    return decision(
        hasProject ? "CODE_RUN_REQUIRED" : "IMPLEMENTATION_PROPOSAL",
        0.8,
        hasProject ? "explicit_code_mutation_request" : "code_mutation_missing_project_context",
        true,
        hasProject
            ? "Create a cloud run? No run credits are charged until the generated plan is approved."
            : "I can turn this into an implementation, but I need a project/repo context before a"
                + " cloud run can be submitted.",
        new TypedAction(
            "create_cloud_run",
            args,
            true,
            billingImpact("create_cloud_run"),
            "Create a cloud run? No run credits are charged until the generated plan is approved."),
        hasProject,
        true,
        hasProject
            ? "Clear code mutation request with project context; submit only after confirmation."
            : "Missing project/repo context blocks run submission.");
  }
}
