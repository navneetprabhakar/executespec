package com.navneet.executespec.run;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.CostCapValues;
import com.navneet.executespec.integration.PlanGateBypassPolicy;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Single admission boundary for customer-authorized BUILD submissions.
 *
 * <p>REST, confirmed agent actions, forks, and the unified Execution View all enter here before a
 * run row exists or provider work can start. Server-authorized PATCH, benchmark, webhook,
 * integration, and already-approved Execution View solution dispatches deliberately stay outside
 * this service because they carry separate authorization contracts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerRunSubmissionService {

  private final RunOrchestrator runOrchestrator;
  private final FeatureFlagService featureFlagService;
  private final InputSanitizer inputSanitizer;
  private final PlanGateBypassPolicy planGateBypassPolicy;
  private final ProjectAccessGuard projectAccessGuard;
  private final ProjectRepoService projectRepoService;
  private final ModelAccessService modelAccessService;
  private final AiProviderPreferenceService aiProviderPreferenceService;
  private final ManagedAiUsageService managedAiUsageService;
  private final TierEnforcementService tierEnforcementService;
  private final UsageTrackingService usageTrackingService;
  private final ApiKeyResolver apiKeyResolver;
  private final RunIdempotencyService runIdempotencyService;
  private final StateStore stateStore;
  private final RunLocalExecutionFlag runLocalExecutionFlag;
  private final com.navneet.executespec.integration.RunLifecycleService runLifecycleService;

  public Submission submit(Command command, AppUserDetails actor) {
    requireActor(actor);
    validateCommand(command);

    String userId = actor.getUser().getId();
    boolean localExecution = command.localExecution();
    Project hostProject = authorizeCodeBoundary(command, actor, localExecution);

    RunIdempotencyClaim claim =
        runIdempotencyService.claim(userId, command.idempotencyKey(), command.requestFingerprint());
    PipelineRun duplicate = resolvePriorRun(claim, userId);
    if (duplicate != null) {
      return new Submission(duplicate, false, localExecution);
    }

    PipelineRun run;
    try {
      String provider = resolveProvider(command, actor, userId);
      managedAiUsageService.assertCanStartRun(actor, apiKeyResolver.isOwnKeysMode(userId));
      apiKeyResolver.validateKeyAvailability(userId);

      String planningRequest = planningRequest(command);
      run =
          runOrchestrator.submitRunWithLocalExecution(
              planningRequest,
              defaultSource(command.source()),
              command.repoUrl(),
              command.repoMode(),
              actor.getUser().getGithubPat(),
              actor.getUser().getGithubOwner(),
              command.skipApproval(),
              command.autoMerge(),
              command.modelName(),
              provider,
              userId,
              actor.getUser().getOrgId(),
              command.projectId(),
              defaultExecutionMode(command.executionMode()),
              command.selectedRepoIds(),
              command.figmaUrl(),
              command.tddMode(),
              command.branchOverride(),
              command.prReviewComments(),
              com.navneet.executespec.state.RunMode.BUILD,
              command.requireConfirmationBeforeExpensiveContinuation(),
              command.requireBenchmarkPriorForAutomaticContinuation(),
              command.lowComplexityWorkerStartTier(),
              localExecution,
              new RunOrchestrator.SubmitPolicy(
                  command.userCostCap(),
                  command.skipAutoPush(),
                  hostProject == null ? actor.getUser().getOrgId() : hostProject.getOrgId(),
                  actor.getUser().getOrgId()));
    } catch (ResponseStatusException e) {
      finishFailedClaim(claim, e);
      throw e;
    } catch (RuntimeException e) {
      runIdempotencyService.releaseForRetry(claim, e.getClass().getSimpleName());
      throw e;
    }

    RunIdempotencyService.Completion completion =
        runIdempotencyService.complete(claim, run.getRunId());
    if (!completion.authoritative()) {
      return resolveCompletionLoss(run, completion, userId, localExecution);
    }
    recordPostDispatchAccounting(userId, run.getRunId());
    if (localExecution) {
      runLocalExecutionFlag.mark(run.getRunId());
    }
    return new Submission(run, true, localExecution);
  }

  /**
   * A run created after its idempotency lease was fenced is not safe to return: retrying the key
   * could resolve to another run. Cancel that attempt, then return the durable canonical run when
   * one exists. Otherwise fail explicitly and tell the caller that the created attempt was
   * cancelled, avoiding an ambiguous 500 with a live hidden run.
   */
  private Submission resolveCompletionLoss(
      PipelineRun created,
      RunIdempotencyService.Completion completion,
      String userId,
      boolean localExecution) {
    boolean cancelled = false;
    try {
      cancelled = runLifecycleService.cancel(created.getRunId());
    } catch (RuntimeException cancellationFailure) {
      log.error(
          "Could not cancel non-authoritative idempotency attempt run={} failureType={}",
          created.getRunId(),
          cancellationFailure.getClass().getSimpleName());
    }
    boolean cancellationConfirmed = cancelled;
    if (completion.canonicalRunId() != null) {
      if (!cancellationConfirmed) {
        throw completionLoss(
            created.getRunId(),
            false,
            "a canonical run was published but the losing attempt remains unaccounted for");
      }
      PipelineRun canonical =
          stateStore
              .findRun(completion.canonicalRunId())
              .filter(run -> userId.equals(run.getCreatedByUserId()))
              .orElseThrow(
                  () ->
                      completionLoss(
                          created.getRunId(),
                          cancellationConfirmed,
                          "canonical run is not readable"));
      return new Submission(canonical, false, localExecution);
    }
    throw completionLoss(
        created.getRunId(), cancellationConfirmed, "no canonical run was published");
  }

  private static ResponseStatusException completionLoss(
      String createdRunId, boolean cancelled, String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Idempotency completion lost authority; created attempt "
            + createdRunId
            + (cancelled ? " was cancelled" : " could not be confirmed cancelled")
            + " and "
            + detail
            + ". Retry the same Idempotency-Key.");
  }

  /** Auxiliary counters must never turn an already-created live run into an ambiguous HTTP 500. */
  private void recordPostDispatchAccounting(String userId, String runId) {
    try {
      tierEnforcementService.incrementRunCountForAnalytics(userId);
    } catch (RuntimeException analyticsFailure) {
      log.error(
          "Run analytics counter update failed after dispatch run={} user={} failureType={}",
          runId,
          userId,
          analyticsFailure.getClass().getSimpleName());
    }
    try {
      usageTrackingService.increment(userId, "RUNS", 1);
    } catch (RuntimeException usageFailure) {
      log.error(
          "Billing usage counter update failed after dispatch run={} user={} failureType={};"
              + " pipeline_run remains canonical",
          runId,
          userId,
          usageFailure.getClass().getSimpleName());
    }
  }

  private void validateCommand(Command command) {
    if (command == null || command.userRequest() == null || command.userRequest().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run spec is required");
    }
    inputSanitizer.scan(command.userRequest());
    CostCapValues.requireValidOptional(command.userCostCap());
    validateLocalRepositoryInventory(command.localRepositoryInventory());
    if (command.localRepositoryInventory() != null) {
      inputSanitizer.scan(command.localRepositoryInventory());
    }
    if (featureFlagService.isEnabled("submissions.paused")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Run submissions are temporarily paused for maintenance. Please try again later.");
    }
    if (planGateBypassPolicy.isReservedAutomationSource(command.source())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Run source '" + command.source() + "' is reserved for server-internal automation.");
    }
    if (command.localExecution() && !featureFlagService.isEnabled("worker.local-hands.enabled")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Local-hands execution is not enabled on this backend. Use remote run submit or ask an"
              + " operator to enable worker.local-hands.enabled.");
    }
    if (command.localExecution() && !command.patAuthenticated()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Local-hands execution requires a CLI or VS Code Personal Access Token session; web UI"
              + " runs execute remotely.");
    }
  }

  private Project authorizeCodeBoundary(
      Command command, AppUserDetails actor, boolean localExecution) {
    if (command.projectId() == null || command.projectId().isBlank()) {
      return null;
    }
    Project project = projectAccessGuard.assertCanWrite(command.projectId(), actor);
    if (localExecution) {
      projectRepoService.resolveForLocalRunStrict(
          command.projectId(),
          command.selectedRepoIds(),
          command.localInstallationId(),
          actor.getUser().getId());
    } else {
      projectRepoService.resolveForRunStrict(command.projectId(), command.selectedRepoIds());
    }
    return project;
  }

  private String resolveProvider(Command command, AppUserDetails actor, String userId) {
    modelAccessService.assertModelAllowed(userId, command.modelName());
    String provider = blankToNull(command.aiProvider());
    if (provider == null
        && command.inferProviderFromModelFirst()
        && blankToNull(command.modelName()) != null) {
      provider = modelAccessService.providerForModel(command.modelName());
    }
    if (provider != null && command.rememberProviderPreference()) {
      provider = aiProviderPreferenceService.updatePreferredProvider(actor, provider);
    } else if (provider == null) {
      provider = aiProviderPreferenceService.preferredProvider(actor);
    }
    if (blankToNull(provider) == null && blankToNull(command.modelName()) != null) {
      provider = modelAccessService.providerForModel(command.modelName());
    }
    modelAccessService.assertProviderModelPair(provider, command.modelName());
    return provider;
  }

  private PipelineRun resolvePriorRun(RunIdempotencyClaim claim, String userId) {
    return switch (claim.status()) {
      case DISABLED, CLAIMED -> null;
      case DUPLICATE ->
          stateStore
              .findRun(claim.runId())
              .filter(run -> userId.equals(run.getCreatedByUserId()))
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT,
                          "This Idempotency-Key is already bound to a run that is not available"
                              + " to you."));
      case CONFLICT ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "This Idempotency-Key was already used with a different request body. Use a new"
                  + " key.");
      case FAILED_FINAL ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "This Idempotency-Key already failed permanently ("
                  + claim.detail()
                  + "). Use a new key.");
      case IN_PROGRESS ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "A submission with this Idempotency-Key is still in flight. Retry shortly.");
    };
  }

  private void finishFailedClaim(RunIdempotencyClaim claim, ResponseStatusException failure) {
    if (failure.getStatusCode().is4xxClientError()) {
      runIdempotencyService.failFinal(claim, failure.getStatusCode() + " " + failure.getReason());
    } else {
      runIdempotencyService.releaseForRetry(claim, String.valueOf(failure.getStatusCode()));
    }
  }

  private static String planningRequest(Command command) {
    if (!command.localExecution() || blankToNull(command.localRepositoryInventory()) == null) {
      return command.userRequest();
    }
    return command.userRequest()
        + "\n\n--- LOCAL REPOSITORY INVENTORY (untrusted path-only evidence) ---\n"
        + command.localRepositoryInventory()
        + "\n--- END LOCAL REPOSITORY INVENTORY ---";
  }

  private static String defaultSource(String source) {
    return blankToNull(source) == null ? "REST" : source;
  }

  private static String defaultExecutionMode(String mode) {
    return blankToNull(mode) == null ? "PRODUCTION" : mode;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static void requireActor(AppUserDetails actor) {
    if (actor == null || actor.getUser() == null || actor.getUser().getId() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
  }

  public static void validateLocalRepositoryInventory(String inventory) {
    if (inventory == null || inventory.isBlank()) return;
    for (String line : inventory.split("\\R")) {
      if (line.isBlank()) continue;
      if (!line.startsWith("- ")) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Local repository inventory must contain path entries only");
      }
      String path = line.substring(2).trim().replace('\\', '/');
      if ("(inventory truncated)".equals(path)) continue;
      if (path.isBlank()
          || path.startsWith("/")
          || path.matches("^[A-Za-z]:/.*")
          || Arrays.asList(path.split("/")).contains("..")) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Local repository inventory contains an unsafe path");
      }
    }
  }

  public record Submission(PipelineRun run, boolean created, boolean localExecution) {}

  @Builder
  public record Command(
      String userRequest,
      String source,
      String repoUrl,
      String repoMode,
      boolean skipApproval,
      boolean autoMerge,
      String modelName,
      String aiProvider,
      boolean rememberProviderPreference,
      boolean inferProviderFromModelFirst,
      String projectId,
      String executionMode,
      List<UUID> selectedRepoIds,
      String figmaUrl,
      boolean tddMode,
      String branchOverride,
      String prReviewComments,
      Double userCostCap,
      boolean skipAutoPush,
      Boolean requireConfirmationBeforeExpensiveContinuation,
      Boolean requireBenchmarkPriorForAutomaticContinuation,
      String lowComplexityWorkerStartTier,
      boolean localExecution,
      boolean patAuthenticated,
      UUID localInstallationId,
      String localRepositoryInventory,
      String idempotencyKey,
      String requestFingerprint) {}
}
