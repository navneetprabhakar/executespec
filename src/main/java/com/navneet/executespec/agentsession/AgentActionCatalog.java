package com.navneet.executespec.agentsession;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentActionDescriptor;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CapabilityTool;

import org.springframework.stereotype.Component;

/** Static action catalog used to ground CLI/VS Code intent routing. */
@Component
public class AgentActionCatalog {

  public List<AgentActionDescriptor> list() {
    return List.of(
        new AgentActionDescriptor(
            "chat_answer",
            "Answer a non-mutating question in the current session.",
            false,
            "conversation_usage",
            List.of("message")),
        new AgentActionDescriptor(
            "list_gates", "List plan gates awaiting explicit approval.", false, "none", List.of()),
        new AgentActionDescriptor(
            "inspect_gate",
            "Return the decision card for one awaiting-approval run.",
            false,
            "none",
            List.of("runId")),
        new AgentActionDescriptor(
            "approve_run",
            "Approve a run plan gate. Credits are charged by the existing run approval path.",
            true,
            "run_credit",
            List.of("runId", "creditsConfirmed")),
        new AgentActionDescriptor(
            "reject_run", "Reject a run plan gate.", true, "none", List.of("runId")),
        new AgentActionDescriptor(
            "create_run",
            "Create a gated cloud run from a natural-language spec.",
            true,
            "run_credit_on_plan_approval",
            List.of("spec", "projectId")),
        new AgentActionDescriptor(
            "local_code_task",
            "Ask a local-capable client to handle a coding task in the working tree.",
            true,
            "conversation_usage",
            List.of("spec")),
        new AgentActionDescriptor(
            "resolve_project",
            "Resolve the current repository to a linked ExecuteSpec project.",
            false,
            "none",
            List.of("gitRemote")));
  }

  public List<CapabilityTool> capabilities(String surface, String tier) {
    String normalizedSurface = normalize(surface, "CLI");
    String normalizedTier = normalize(tier, "FREE");
    return allCapabilities().stream()
        .filter(c -> c.surfaces().contains(normalizedSurface))
        .filter(c -> tierAllows(normalizedTier, c.requiredTier()))
        .toList();
  }

  public List<CapabilityTool> allCapabilities() {
    return List.of(
        cap(
            "chat_answer",
            "Answer a non-mutating question in the current session.",
            "FREE",
            "read",
            false,
            "conversation_usage",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("message")),
        cap(
            "list_gates",
            "List plan gates awaiting explicit approval.",
            "FREE",
            "read",
            false,
            "none",
            List.of("WEB", "CLI", "VSCODE"),
            List.of()),
        cap(
            "inspect_gate",
            "Return the decision card for one awaiting-approval run.",
            "FREE",
            "read",
            false,
            "none",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("runId")),
        cap(
            "approve_run",
            "Approve a run plan gate through the existing guarded approval path.",
            "FREE",
            "billable",
            true,
            "run_credit",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("runId", "creditsConfirmed")),
        cap(
            "reject_run",
            "Reject a run plan gate.",
            "FREE",
            "write",
            true,
            "none",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("runId")),
        cap(
            "create_cloud_run",
            "Create a gated cloud run from a natural-language spec.",
            "FREE",
            "billable",
            true,
            "run_credit_on_plan_approval",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("spec", "projectId")),
        cap(
            "local_code_task",
            "Ask a local-capable client to handle a coding task in the working tree.",
            "FREE",
            "write",
            true,
            "conversation_usage",
            List.of("CLI", "VSCODE"),
            List.of("spec")),
        cap(
            "local_git_diff",
            "Read local working-tree diff from a client workspace.",
            "FREE",
            "read",
            false,
            "none",
            List.of("CLI", "VSCODE"),
            List.of("path")),
        cap(
            "local_git_status",
            "Read local working-tree status from a client workspace.",
            "FREE",
            "read",
            false,
            "none",
            List.of("CLI", "VSCODE"),
            List.of()),
        cap(
            "generate_architecture_doc",
            "Generate or refresh project architecture documentation.",
            "FREE",
            "write",
            true,
            "conversation_usage",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("projectId", "targetPath")),
        cap(
            "run_code_review",
            "Run the paid code review workflow available from the Web UI.",
            "PRO",
            "billable",
            true,
            "subscription",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("projectId", "scope")),
        cap(
            "manage_org_model_overrides",
            "Read or update Enterprise model-routing overrides.",
            "ENTERPRISE",
            "destructive",
            true,
            "none",
            List.of("WEB", "CLI", "VSCODE"),
            List.of("provider", "tier", "model")));
  }

  private static CapabilityTool cap(
      String name,
      String description,
      String requiredTier,
      String sideEffect,
      boolean confirmationRequired,
      String billingImpact,
      List<String> surfaces,
      List<String> requiredFields) {
    Map<String, Object> inputSchema =
        Map.of("type", "object", "required", requiredFields, "additionalProperties", true);
    Map<String, Object> outputSchema = Map.of("type", "object", "additionalProperties", true);
    return new CapabilityTool(
        name,
        description,
        inputSchema,
        outputSchema,
        requiredTier,
        List.of("authenticated"),
        sideEffect,
        confirmationRequired,
        billingImpact,
        surfaces);
  }

  private static boolean tierAllows(String userTier, String requiredTier) {
    return tierRank(userTier) >= tierRank(requiredTier);
  }

  private static int tierRank(String tier) {
    return switch (normalize(tier, "FREE")) {
      case "ENTERPRISE" -> 4;
      case "TEAM", "MAX" -> 3;
      case "PRO" -> 2;
      default -> 1;
    };
  }

  private static String normalize(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
  }
}
