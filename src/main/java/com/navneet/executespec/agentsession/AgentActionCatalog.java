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

  private static final List<String> ALL_SURFACES = List.of("WEB", "CLI", "VSCODE");
  private static final List<String> LOCAL_SURFACES = List.of("CLI", "VSCODE");
  private static final List<String> AUTHENTICATED_SCOPE = List.of("authenticated");

  private static final List<ActionDefinition> ACTIONS =
      List.of(
          action(
              "chat_answer",
              "Answer a non-mutating question in the current session.",
              "FREE",
              "read",
              false,
              "conversation_usage",
              ALL_SURFACES,
              true,
              "message"),
          action(
              "list_gates",
              "List plan gates awaiting explicit approval.",
              "FREE",
              "read",
              false,
              "none",
              ALL_SURFACES,
              true),
          action(
              "inspect_gate",
              "Return the decision card for one awaiting-approval run.",
              "FREE",
              "read",
              false,
              "none",
              ALL_SURFACES,
              true,
              "runId"),
          action(
              "approve_run",
              "Approve a run plan gate through the existing guarded approval path.",
              "FREE",
              "billable",
              true,
              "run_credit",
              ALL_SURFACES,
              true,
              "runId",
              "creditsConfirmed"),
          action(
              "reject_run",
              "Reject a run plan gate.",
              "FREE",
              "write",
              true,
              "none",
              ALL_SURFACES,
              true,
              "runId"),
          action(
              "create_cloud_run",
              "Create a gated cloud run from a natural-language spec.",
              "FREE",
              "billable",
              true,
              "run_credit_on_plan_approval",
              ALL_SURFACES,
              true,
              "spec",
              "projectId"),
          action(
              "local_code_task",
              "Ask a local-capable client to handle a coding task in the working tree.",
              "FREE",
              "write",
              true,
              "conversation_usage",
              LOCAL_SURFACES,
              true,
              "spec"),
          action(
              "local_git_diff",
              "Read local working-tree diff from a client workspace.",
              "FREE",
              "read",
              false,
              "none",
              LOCAL_SURFACES,
              false,
              "path"),
          action(
              "local_git_status",
              "Read local working-tree status from a client workspace.",
              "FREE",
              "read",
              false,
              "none",
              LOCAL_SURFACES,
              false),
          action(
              "resolve_project",
              "Resolve the current repository to a linked ExecuteSpec project.",
              "FREE",
              "read",
              false,
              "none",
              LOCAL_SURFACES,
              true,
              "gitRemote"),
          action(
              "generate_architecture_doc",
              "Generate or refresh project architecture documentation.",
              "FREE",
              "write",
              true,
              "conversation_usage",
              ALL_SURFACES,
              false,
              "projectId",
              "targetPath"),
          action(
              "run_code_review",
              "Run the paid code review workflow available from the Web UI.",
              "PRO",
              "billable",
              true,
              "subscription",
              ALL_SURFACES,
              false,
              "projectId",
              "scope"),
          action(
              "manage_org_model_overrides",
              "Read or update Enterprise model-routing overrides.",
              "ENTERPRISE",
              "destructive",
              true,
              "none",
              ALL_SURFACES,
              false,
              "provider",
              "tier",
              "model"));

  public List<AgentActionDescriptor> list() {
    return ACTIONS.stream()
        .filter(ActionDefinition::descriptor)
        .map(ActionDefinition::toDescriptor)
        .toList();
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
    return ACTIONS.stream().map(ActionDefinition::toCapability).toList();
  }

  private static ActionDefinition action(
      String type,
      String description,
      String requiredTier,
      String sideEffect,
      boolean confirmationRequired,
      String billingImpact,
      List<String> surfaces,
      boolean descriptor,
      String... requiredFields) {
    return new ActionDefinition(
        type,
        description,
        requiredTier,
        sideEffect,
        confirmationRequired,
        billingImpact,
        surfaces,
        List.of(requiredFields),
        descriptor);
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

  private record ActionDefinition(
      String type,
      String description,
      String requiredTier,
      String sideEffect,
      boolean confirmationRequired,
      String billingImpact,
      List<String> surfaces,
      List<String> requiredFields,
      boolean descriptor) {

    AgentActionDescriptor toDescriptor() {
      return new AgentActionDescriptor(
          type, description, confirmationRequired, billingImpact, requiredFields);
    }

    CapabilityTool toCapability() {
      Map<String, Object> inputSchema =
          Map.of("type", "object", "required", requiredFields, "additionalProperties", true);
      Map<String, Object> outputSchema = Map.of("type", "object", "additionalProperties", true);
      return new CapabilityTool(
          type,
          description,
          inputSchema,
          outputSchema,
          requiredTier,
          AUTHENTICATED_SCOPE,
          sideEffect,
          confirmationRequired,
          billingImpact,
          surfaces);
    }
  }
}
