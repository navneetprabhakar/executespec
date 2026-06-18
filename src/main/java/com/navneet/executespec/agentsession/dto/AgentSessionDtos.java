package com.navneet.executespec.agentsession.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public DTOs for the CLI/VS Code agent-session protocol. */
public final class AgentSessionDtos {

  private AgentSessionDtos() {}

  public record CreateSessionRequest(
      String id,
      String projectId,
      String repoRemote,
      String repoRootHash,
      RepoContext repoContext,
      String surface,
      String title,
      String defaultProvider,
      String defaultModel,
      String defaultExecutionMode,
      String approvalPolicy) {}

  public record AgentSessionResponse(
      String id,
      String projectId,
      String repoRemote,
      String surface,
      String title,
      String status,
      String defaultProvider,
      String defaultModel,
      String defaultExecutionMode,
      String approvalPolicy,
      Instant createdAt,
      Instant updatedAt) {}

  public record RepoContext(
      String cwdName,
      String gitRemote,
      String branch,
      List<String> changedFiles,
      List<String> detectedStack) {}

  public record ClientCapabilities(
      boolean localTools, boolean interactive, boolean canRenderDiff, boolean canRenderTui) {}

  public record TurnRequest(
      String message, RepoContext repoContext, ClientCapabilities clientCapabilities) {}

  public record StreamTurnRequest(
      String message,
      List<Map<String, Object>> conversation,
      RepoContext repoContext,
      ClientCapabilities clientCapabilities,
      String providerOverride) {}

  public record InterpretIntentRequest(
      String sessionId,
      String message,
      String projectId,
      RepoContext repoContext,
      ClientCapabilities clientCapabilities) {}

  public record TypedAction(
      String type,
      Map<String, Object> arguments,
      boolean requiresConfirmation,
      String billingImpact,
      String confirmationPrompt) {}

  public record IntentDecision(
      String intent,
      double confidence,
      boolean needsConfirmation,
      String confirmationPrompt,
      TypedAction proposedAction,
      String response) {}

  public record TurnResponse(
      String turnId,
      String sessionId,
      int sequence,
      IntentDecision decision,
      String status,
      Instant createdAt) {}

  public record AgentActionDescriptor(
      String type,
      String description,
      boolean requiresConfirmation,
      String billingImpact,
      List<String> requiredFields) {}

  public record CapabilityTool(
      String name,
      String description,
      Map<String, Object> inputSchema,
      Map<String, Object> outputSchema,
      String requiredTier,
      List<String> requiredScopes,
      String sideEffect,
      boolean confirmationRequired,
      String billingImpact,
      List<String> surfaces) {}

  public record ActionPlanRequest(
      String message,
      String projectId,
      RepoContext repoContext,
      ClientCapabilities clientCapabilities,
      String surface) {}

  public record ActionPlanResponse(
      String actionId,
      String sessionId,
      IntentDecision decision,
      List<CapabilityTool> capabilities,
      Instant createdAt) {}

  public record ActionEventRequest(String type, Map<String, Object> payload) {}

  public record ActionResultRequest(
      String status,
      String summary,
      String runId,
      List<String> touchedFiles,
      Map<String, Object> verification) {}

  public record ActionRecordResponse(
      String actionId, String sessionId, String status, String summary, Instant updatedAt) {}

  public record ContextResolveRequest(
      String gitRemote, String repoName, String branch, RepoContext repoContext) {}

  public record ProjectMatch(
      String projectId, String projectName, String repoId, String repoFullName) {}

  public record ContextResolveResponse(ProjectMatch project, String reason) {}

  public record GateDecisionCard(
      String runId,
      String projectId,
      String projectName,
      String title,
      String status,
      Instant submittedAt,
      String provider,
      String model,
      Double estimatedCostUsd,
      Double hardCapUsd,
      Integer creditsRequired,
      String risk,
      String summary,
      String recommendedAction,
      List<String> tasks) {}
}
