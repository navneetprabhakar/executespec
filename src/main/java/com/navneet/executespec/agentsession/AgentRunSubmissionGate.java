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
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.run.RunIdempotencyService;
import com.navneet.executespec.run.RunIdempotencyStatus;
import com.navneet.executespec.run.RunRequestFingerprint;
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
  private final ProjectAccessGuard projectAccessGuard;

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
    projectAccessGuard.assertCanWrite(projectId, user);
    // RR-825 — this surface has no bean validation, so a caller could send "NaN"/"Infinity" as the
    // hard cost cap and disable the worker ceiling. Reject before the claim, the quota accounting
    // and any provider spend rather than after the asynchronous pipeline has already started.
    com.navneet.executespec.cost.CostCapValues.requireValidOptional(
        request == null ? null : request.userCostCap());
    boolean localExecutionRequested =
        request != null && Boolean.TRUE.equals(request.localExecution());
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
    if (localExecutionRequested) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Agent proposal confirmation cannot bind a local connector and repository selection."
              + " Submit through the CLI/VS Code local-run command so selectedRepoIds and"
              + " localInstallationId are verified before any run or provider spend.");
    }

    // Durable atomic claim (V303): one confirmed turn can only ever produce one run, even when the
    // same confirmation is dispatched twice in parallel. Taken before quota/usage accounting below.
    String idempotencyKey = IDEMPOTENCY_PREFIX + turn.getId();
    var claim =
        runIdempotencyService.claim(
            user.getUser().getId(),
            idempotencyKey,
            RunRequestFingerprint.of(java.util.Arrays.asList(projectId, spec)));
    if (claim.status() == RunIdempotencyStatus.DUPLICATE) {
      var prior = stateStore.findRun(claim.runId());
      if (prior.isPresent() && user.getUser().getId().equals(prior.get().getCreatedByUserId())) {
        return new Submission(prior.get().getRunId(), false, "ALREADY_SUBMITTED");
      }
    }
    if (!claim.owned() && claim.status() != RunIdempotencyStatus.DISABLED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This confirmation is already being submitted. Reload the session before retrying.");
    }

    // Everything from here to submitRun is inside the claim's failure handling. A throw from the
    // validation band used to leave the durable claim held with no run id, so the very next retry
    // -- after the user topped up credits or fixed their key -- was rejected as a duplicate
    // submission until the lease elapsed.
    PipelineRun run;
    try {
      inputSanitizer.scan(spec);
      if (featureFlagService.isEnabled("submissions.paused")) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Run submissions are temporarily paused for maintenance. Please try again later.");
      }

      String explicitModel = request == null ? null : AgentText.blankToNull(request.modelName());
      String modelName =
          AgentText.firstNonBlank(explicitModel, AgentText.blankToNull(session.getDefaultModel()));
      modelAccessService.assertModelAllowed(user.getUser().getId(), modelName);
      String lowStartTier =
          request == null
              ? null
              : normalizeLowComplexityWorkerStartTier(request.lowComplexityWorkerStartTier());
      // Run-count retirement (2026-06-22): gate on managed ES AI credits, not a run cap.
      managedAiUsageService.assertCanStartRun(
          user, apiKeyResolver.isOwnKeysMode(user.getUser().getId()));
      tierEnforcementService.incrementRunCountForAnalytics(user.getUser().getId());
      apiKeyResolver.validateKeyAvailability(user.getUser().getId());

      String providerOverride =
          request == null ? null : AgentText.blankToNull(request.aiProvider());
      String modelProvider =
          explicitModel == null ? null : modelAccessService.providerForModel(explicitModel);
      String sessionProvider = AgentText.blankToNull(session.getDefaultProvider());
      String selectedProvider =
          AgentText.firstNonBlank(providerOverride, modelProvider, sessionProvider);
      String effectiveProvider =
          selectedProvider != null
              ? (providerOverride != null || modelProvider != null
                  ? aiProviderPreferenceService.updatePreferredProvider(user, selectedProvider)
                  : selectedProvider)
              : aiProviderPreferenceService.preferredProvider(user);
      modelAccessService.assertProviderModelPair(effectiveProvider, modelName);
      String executionMode =
          normalizeExecutionMode(request == null ? null : request.executionMode());

      run =
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
    } catch (ResponseStatusException e) {
      if (e.getStatusCode().is4xxClientError()) {
        runIdempotencyService.failFinal(claim, e.getStatusCode() + " " + e.getReason());
      } else {
        runIdempotencyService.releaseForRetry(claim, String.valueOf(e.getStatusCode()));
      }
      throw e;
    } catch (RuntimeException e) {
      runIdempotencyService.releaseForRetry(claim, e.getClass().getSimpleName());
      throw e;
    }

    runIdempotencyService.complete(claim, run.getRunId());
    usageTrackingService.increment(user.getUser().getId(), "RUNS", 1);
    if (request != null && request.userCostCap() != null) {
      stateStore.setUserCostCap(run.getRunId(), request.userCostCap());
    }
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
