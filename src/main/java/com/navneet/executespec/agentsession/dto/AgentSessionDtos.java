package com.navneet.executespec.agentsession.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public DTOs for the CLI/VS Code agent-session protocol. */
public final class AgentSessionDtos {

  private AgentSessionDtos() {}

  public record CreateSessionRequest(
      String projectId,
      String repoRemote,
      String repoRootHash,
      String clientSurface,
      String title,
      String defaultProvider,
      String defaultModel,
      String defaultExecutionMode,
      String approvalPolicy) {}

  public record AgentSessionResponse(
      String id,
      String projectId,
      String repoRemote,
      String clientSurface,
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

  public record ContextResolveRequest(String gitRemote, String repoName, String branch) {}

  public record ProjectMatch(String id, String name, double confidence, String matchReason) {}

  public record ContextResolveResponse(ProjectMatch matchedProject, List<String> suggestions) {}

  public record GateDecisionCard(
      String runId,
      String title,
      String projectId,
      String projectName,
      String status,
      String provider,
      String model,
      Double costEstimateUsd,
      Double costCapUsd,
      String risk,
      String planSummary,
      List<String> tasks,
      String createdBy,
      Instant createdAt) {}
}
