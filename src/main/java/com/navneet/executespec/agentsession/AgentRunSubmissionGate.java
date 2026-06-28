package com.navneet.executespec.agentsession;

import java.util.Locale;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.run.RunIdempotencyService;
import com.navneet.executespec.run.RunLocalExecutionFlag;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * The only agent-session component allowed to submit a new cloud run from an orchestrator turn.
 *
 * <p>Normal turns remain decision-only. This gate accepts only a stored {@code CODE_RUN_REQUIRED}
 * decision, explicit user confirmation, and a policy that already allows the run API.
 */
@Service
@RequiredArgsConstructor
public class AgentRunSubmissionGate {

  private static final String IDEMPOTENCY_PREFIX = "agent-orchestrator:";

  private final RunOrchestrator runOrchestrator;
  private final FeatureFlagService featureFlagService;
  private final InputSanitizer inputSanitizer;
  private final ModelAccessService modelAccessService;
  private final TierEnforcementService tierEnforcementService;
  private final UsageTrackingService usageTrackingService;
  private final ApiKeyResolver apiKeyResolver;
  private final ManagedAiUsageService managedAiUsageService;
  private final AiProviderPreferenceService aiProviderPreferenceService;
  private final RunIdempotencyService runIdempotencyService;
  private final StateStore stateStore;
  private final RunLocalExecutionFlag runLocalExecutionFlag;

  public Submission submitConfirmed(
      AgentSession session,
      AgentTurn turn,
      OrchestratorTurnResponse decision,
      OrchestratorRunConfirmationRequest request,
      AppUserDetails user) {
    return submitConfirmed(session, turn, decision, request, user, false);
  }

  public Submission submitConfirmed(
      AgentSession session,
      AgentTurn turn,
      OrchestratorTurnResponse decision,
      OrchestratorRunConfirmationRequest request,
      AppUserDetails user,
      boolean patAuthenticated) {
    if (!"CODE_RUN_REQUIRED".equals(decision.route())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored orchestrator decision is not a run route");
    }
    if (!decision.policy().runApiAllowed()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored orchestrator policy does not allow run submission");
    }
    if (decision.policy().confirmationRequiredBeforeSubmit()
        && (request == null || !Boolean.TRUE.equals(request.confirmed()))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "explicit run confirmation required");
    }
    TypedAction action = decision.proposedAction();
    if (action == null || !"create_cloud_run".equals(action.type())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "no cloud-run action is proposed");
    }

    String spec = request == null ? null : AgentText.blankToNull(request.spec());
    if (spec == null) {
      spec = AgentActionArguments.stringArg(action.arguments(), "spec");
    }
    if (spec == null || spec.isBlank()) {
      spec = turn.getUserMessage();
    }
    if (spec == null || spec.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run spec is required");
    }
    String projectId =
        AgentText.firstNonBlank(
            AgentActionArguments.stringArg(action.arguments(), "projectId"),
            session.getProjectId());
    if (projectId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "project context is required");
    }
    boolean localExecutionRequested = request != null && Boolean.TRUE.equals(request.localExecution());
    if (localExecutionRequested && !featureFlagService.isEnabled("worker.local-hands.enabled")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Local-hands execution is not enabled on this backend. Use remote run submit or ask an"
              + " operator to enable worker.local-hands.enabled.");
    }
    if (localExecutionRequested && !patAuthenticated) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Local-hands execution requires a CLI or VS Code Personal Access Token session; web UI"
              + " runs execute remotely.");
    }

    String idempotencyKey = IDEMPOTENCY_PREFIX + turn.getId();
    var priorRunId = runIdempotencyService.lookup(user.getUser().getId(), idempotencyKey);
    if (priorRunId.isPresent()) {
      var prior = stateStore.findRun(priorRunId.get());
      if (prior.isPresent() && user.getUser().getId().equals(prior.get().getCreatedByUserId())) {
        return new Submission(prior.get().getRunId(), false, "ALREADY_SUBMITTED");
      }
    }

    inputSanitizer.scan(spec);
    if (featureFlagService.isEnabled("submissions.paused")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Run submissions are temporarily paused for maintenance. Please try again later.");
    }

    String modelName = request == null ? null : AgentText.blankToNull(request.modelName());
    modelAccessService.assertModelAllowed(user.getUser().getId(), modelName);
    String lowStartTier =
        request == null ? null : normalizeLowComplexityWorkerStartTier(request.lowComplexityWorkerStartTier());
    // Run-count retirement (2026-06-22): gate on managed ES AI credits, not a run cap.
    managedAiUsageService.assertCanStartRun(
        user, apiKeyResolver.isOwnKeysMode(user.getUser().getId()));
    tierEnforcementService.incrementRunCountForAnalytics(user.getUser().getId());
    apiKeyResolver.validateKeyAvailability(user.getUser().getId());

    String providerOverride = request == null ? null : AgentText.blankToNull(request.aiProvider());
    String effectiveProvider =
        providerOverride != null
            ? aiProviderPreferenceService.updatePreferredProvider(user, providerOverride)
            : aiProviderPreferenceService.preferredProvider(user);
    String executionMode = normalizeExecutionMode(request == null ? null : request.executionMode());

    PipelineRun run =
        runOrchestrator.submitRun(
            spec,
            "AGENT_ORCHESTRATOR",
            null,
            "EXISTING",
            user.getUser().getGithubPat(),
            user.getUser().getGithubOwner(),
            false,
            false,
            modelName,
            effectiveProvider,
            user.getUser().getId(),
            user.getUser().getOrgId(),
            projectId,
            executionMode,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            lowStartTier);

    usageTrackingService.increment(user.getUser().getId(), "RUNS", 1);
    if (request != null && request.userCostCap() != null) {
      stateStore.setUserCostCap(run.getRunId(), request.userCostCap());
    }
    if (localExecutionRequested) {
      runLocalExecutionFlag.mark(run.getRunId());
    }
    runIdempotencyService.record(user.getUser().getId(), idempotencyKey, run.getRunId());
    return new Submission(run.getRunId(), true, "SUBMITTED");
  }

  public record Submission(String runId, boolean calledRunApi, String status) {}

  static String normalizeLowComplexityWorkerStartTier(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
    return "LIGHTWEIGHT".equals(normalized) || "WORKHORSE".equals(normalized) ? normalized : null;
  }

  static String normalizeExecutionMode(String value) {
    if (value == null || value.isBlank()) {
      return "PRODUCTION";
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "PROTOTYPE" -> "PROTOTYPE";
      case "PRODUCTION" -> "PRODUCTION";
      default -> "PRODUCTION";
    };
  }
}
