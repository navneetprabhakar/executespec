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
      String approvalPolicy,
      String runId) {}

  public record AgentSessionResponse(
      String id,
      String projectId,
      String runId,
      String repoRemote,
      String surface,
      String title,
      String status,
      String defaultProvider,
      String defaultModel,
      String defaultExecutionMode,
      String approvalPolicy,
      String latestLinkedRunId,
      String latestLinkedRunStatus,
      String latestLinkedRunTitle,
      Instant latestLinkedRunStartedAt,
      Instant latestLinkedRunCompletedAt,
      String latestLinkedRunPrUrl,
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
      String providerOverride,
      String modelOverride,
      List<String> attachmentIds) {
    /** Back-compat constructor for callers that predate server-side attachments. */
    public StreamTurnRequest(
        String message,
        List<Map<String, Object>> conversation,
        RepoContext repoContext,
        ClientCapabilities clientCapabilities,
        String providerOverride) {
      this(message, conversation, repoContext, clientCapabilities, providerOverride, null, null);
    }

    /** Back-compat constructor for callers that already send attachment ids. */
    public StreamTurnRequest(
        String message,
        List<Map<String, Object>> conversation,
        RepoContext repoContext,
        ClientCapabilities clientCapabilities,
        String providerOverride,
        List<String> attachmentIds) {
      this(
          message,
          conversation,
          repoContext,
          clientCapabilities,
          providerOverride,
          null,
          attachmentIds);
    }
  }

  /** Metadata returned after a chat upload is validated, extracted, sanitized, and stored. */
  public record AttachmentResponse(
      String attachmentId,
      String filename,
      String mimeType,
      long sizeBytes,
      int extractedChars,
      String preview) {}

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

  public record OrchestratorPolicy(
      boolean runApiAllowed, boolean confirmationRequiredBeforeSubmit, String reason) {}

  public record OrchestratorDecision(
      String route,
      double confidence,
      String reasonCode,
      boolean requiresUserConfirmation,
      String assistantReply,
      TypedAction proposedAction,
      OrchestratorPolicy policy,
      String traceId) {}

  public record OrchestratorRepoContext(
      String gitRoot,
      String branch,
      Boolean hasUncommittedChanges,
      List<String> changedFiles,
      List<InstructionFile> instructionFiles,
      String gitRemote,
      String cwdName,
      List<String> detectedStack) {}

  public record InstructionFile(String path, String sha256) {}

  public record OrchestratorClientCapabilities(
      boolean supportsLocalHands,
      boolean supportsLocalReadContext,
      boolean supportsRunConfirmation,
      boolean supportsPlanGate,
      boolean supportsEvidence,
      boolean supportsDiff,
      boolean supportsRevert,
      boolean supportsStreaming) {}

  public record OrchestratorMode(
      String permissionMode,
      String requestedExecutionMode,
      boolean requiresConfirmationByDefault) {}

  public record OrchestratorAttachment(
      String kind, String path, String contentSha256, boolean truncated) {}

  public record OrchestratorTurnRequest(
      String sessionId,
      String turnId,
      String message,
      String surface,
      String clientVersion,
      String projectId,
      String cwd,
      OrchestratorRepoContext repoContext,
      OrchestratorClientCapabilities clientCapabilities,
      OrchestratorMode mode,
      List<OrchestratorAttachment> attachments) {}

  public record OrchestratorTurnResponse(
      String turnId,
      String route,
      double confidence,
      String reasonCode,
      boolean requiresUserConfirmation,
      String assistantReply,
      TypedAction proposedAction,
      OrchestratorPolicy policy,
      String traceId) {}

  public record OrchestratorRunConfirmationRequest(
      String sessionId,
      String turnId,
      Boolean confirmed,
      String aiProvider,
      String modelName,
      String lowComplexityWorkerStartTier,
      Double userCostCap,
      String executionMode,
      Boolean localExecution,
      String spec) {
    /** Back-compat constructor for tests and clients that predate execution target fields. */
    public OrchestratorRunConfirmationRequest(
        String sessionId,
        String turnId,
        Boolean confirmed,
        String aiProvider,
        String modelName,
        String lowComplexityWorkerStartTier,
        Double userCostCap) {
      this(
          sessionId,
          turnId,
          confirmed,
          aiProvider,
          modelName,
          lowComplexityWorkerStartTier,
          userCostCap,
          null,
          null,
          null);
    }
  }

  public record OrchestratorRunConfirmationResponse(
      String sessionId,
      String turnId,
      String runId,
      String route,
      boolean calledRunApi,
      String traceId,
      String status) {}

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

  public record AgentTurnResponse(
      String turnId,
      String sessionId,
      int sequence,
      String userMessage,
      String assistantSummary,
      String intentType,
      double intentConfidence,
      String route,
      String reasonCode,
      String actionType,
      String proposedActionJson,
      String linkedRunId,
      long inputTokens,
      long outputTokens,
      long reasoningTokens,
      double costUsd,
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
