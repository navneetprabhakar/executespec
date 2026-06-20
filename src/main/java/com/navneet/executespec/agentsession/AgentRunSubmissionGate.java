package com.navneet.executespec.agentsession;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.run.RunIdempotencyService;
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
  private final AiProviderPreferenceService aiProviderPreferenceService;
  private final RunIdempotencyService runIdempotencyService;
  private final StateStore stateStore;

  public Submission submitConfirmed(
      AgentSession session,
      AgentTurn turn,
      OrchestratorTurnResponse decision,
      OrchestratorRunConfirmationRequest request,
      AppUserDetails user) {
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

    String spec = AgentActionArguments.stringArg(action.arguments(), "spec");
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
    tierEnforcementService.assertAndIncrementRunCount(user.getUser().getId());
    usageTrackingService.assertWithinLimits(user.getUser().getId(), "RUNS");
    apiKeyResolver.validateKeyAvailability(user.getUser().getId());

    String providerOverride = request == null ? null : AgentText.blankToNull(request.aiProvider());
    String effectiveProvider =
        providerOverride != null
            ? aiProviderPreferenceService.updatePreferredProvider(user, providerOverride)
            : aiProviderPreferenceService.preferredProvider(user);

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
            "PRODUCTION",
            null,
            null,
            false,
            null,
            null);

    usageTrackingService.increment(user.getUser().getId(), "RUNS", 1);
    if (request != null && request.userCostCap() != null) {
      stateStore.setUserCostCap(run.getRunId(), request.userCostCap());
    }
    runIdempotencyService.record(user.getUser().getId(), idempotencyKey, run.getRunId());
    return new Submission(run.getRunId(), true, "SUBMITTED");
  }

  public record Submission(String runId, boolean calledRunApi, String status) {}
}
