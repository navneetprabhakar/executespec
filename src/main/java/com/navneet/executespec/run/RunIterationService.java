package com.navneet.executespec.run;

import java.util.List;
import java.util.Set;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.integration.RunPipelineSupport;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Creates a BUILD follow-up from a terminal run while preserving its workspace lineage. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunIterationService {

  private static final Set<String> ITERABLE_STATUSES = Set.of("COMPLETE", "PARTIAL", "FAILED");

  private final PipelineRunRepository runRepo;
  private final RunOrchestrator orchestrator;
  private final RunIdempotencyService idempotencyService;
  private final FeatureFlagService featureFlagService;
  private final InputSanitizer inputSanitizer;
  private final ModelAccessService modelAccessService;
  private final AiProviderPreferenceService providerPreferenceService;
  private final ManagedAiUsageService managedAiUsageService;
  private final ApiKeyResolver apiKeyResolver;
  private final TierEnforcementService tierEnforcementService;
  private final UsageTrackingService usageTrackingService;
  private final ProjectRepoService projectRepoService;
  private final RunLifecycleService runLifecycleService;

  public IterationResult iterate(
      String sourceRunId, String followUpRequest, String idempotencyKey, AppUserDetails actor) {
    if (actor == null || actor.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    if (sourceRunId == null || sourceRunId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceRunId is required");
    }
    if (followUpRequest == null || followUpRequest.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userRequest is required");
    }
    if (featureFlagService.isEnabled("submissions.paused")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Run submissions are temporarily paused for maintenance. Please try again later.");
    }
    inputSanitizer.scan(followUpRequest);

    PipelineRun source =
        runRepo
            .findById(sourceRunId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    if (!ITERABLE_STATUSES.contains(source.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only terminal COMPLETE, PARTIAL, or FAILED runs can be iterated.");
    }
    if (source.isLocalExecutionRequested()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "A server-side iteration cannot inherit a local-hands workspace. Submit from the local"
              + " client instead.");
    }

    String actorId = actor.getUser().getId();
    RunIdempotencyClaim claim =
        idempotencyService.claim(
            actorId,
            idempotencyKey,
            RunRequestFingerprint.of(List.of(sourceRunId, followUpRequest.trim())));
    IterationResult replay = resolveClaim(claim, actorId, sourceRunId);
    if (replay != null) return replay;

    try {
      String provider =
          providerPreferenceService.normalizeProvider(
              firstNonBlank(source.getRequestedProvider(), source.getAiProvider()));
      String pinnedModel = source.isRoutePinned() ? source.getRequestedModel() : null;
      modelAccessService.assertModelAllowed(actorId, pinnedModel);
      modelAccessService.assertProviderAllowed(actorId, provider);
      modelAccessService.assertProviderModelPair(provider, pinnedModel);
      managedAiUsageService.assertCanStartRun(actor, apiKeyResolver.isOwnKeysMode(actorId));
      apiKeyResolver.validateKeyAvailability(actorId);
      if (source.getProjectId() != null && !source.getProjectId().isBlank()) {
        projectRepoService.resolveForRunStrict(
            source.getProjectId(),
            RunPipelineSupport.deserializeRepoIds(source.getSelectedRepoIds()));
      }
      PipelineRun child =
          orchestrator.submitIterationRun(
              source,
              followUpRequest.trim(),
              actorId,
              actor.getUser().getOrgId(),
              actor.getUser().getGithubPat(),
              actor.getUser().getGithubOwner());
      RunIdempotencyService.Completion completion =
          idempotencyService.complete(claim, child.getRunId());
      if (!completion.authoritative()) {
        return resolveCompletionLoss(child, completion, actorId, sourceRunId);
      }
      recordPostDispatchAccounting(actorId, child.getRunId());
      return new IterationResult(child, false);
    } catch (ResponseStatusException e) {
      if (e.getStatusCode().is4xxClientError()) {
        idempotencyService.failFinal(claim, e.getStatusCode() + " " + e.getReason());
      } else {
        idempotencyService.releaseForRetry(claim, String.valueOf(e.getStatusCode()));
      }
      throw e;
    } catch (RuntimeException e) {
      idempotencyService.releaseForRetry(claim, e.getClass().getSimpleName());
      throw e;
    }
  }

  private IterationResult resolveCompletionLoss(
      PipelineRun child,
      RunIdempotencyService.Completion completion,
      String actorId,
      String sourceRunId) {
    boolean cancelled = false;
    try {
      cancelled = runLifecycleService.cancel(child.getRunId());
    } catch (RuntimeException cancellationFailure) {
      log.error(
          "Could not cancel non-authoritative iteration run={} failureType={}",
          child.getRunId(),
          cancellationFailure.getClass().getSimpleName());
    }
    boolean cancellationConfirmed = cancelled;
    if (completion.canonicalRunId() != null) {
      if (!cancellationConfirmed) {
        throw completionLoss(
            child.getRunId(),
            false,
            "a canonical iteration was published but the losing attempt remains unaccounted for");
      }
      PipelineRun canonical =
          runRepo
              .findById(completion.canonicalRunId())
              .filter(run -> actorId.equals(run.getCreatedByUserId()))
              .filter(run -> sourceRunId.equals(run.getSourceRunId()))
              .orElseThrow(
                  () ->
                      completionLoss(
                          child.getRunId(),
                          cancellationConfirmed,
                          "canonical iteration is not readable"));
      return new IterationResult(canonical, true);
    }
    throw completionLoss(
        child.getRunId(), cancellationConfirmed, "no canonical iteration was published");
  }

  private static ResponseStatusException completionLoss(
      String childRunId, boolean cancelled, String detail) {
    return new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Iteration idempotency completion lost authority; created attempt "
            + childRunId
            + (cancelled ? " was cancelled" : " could not be confirmed cancelled")
            + " and "
            + detail
            + ". Retry the same Idempotency-Key.");
  }

  private void recordPostDispatchAccounting(String actorId, String runId) {
    try {
      tierEnforcementService.incrementRunCountForAnalytics(actorId);
    } catch (RuntimeException analyticsFailure) {
      log.error(
          "Iteration analytics counter failed after dispatch run={} user={} failureType={}",
          runId,
          actorId,
          analyticsFailure.getClass().getSimpleName());
    }
    try {
      usageTrackingService.increment(actorId, "RUNS", 1);
    } catch (RuntimeException usageFailure) {
      log.error(
          "Iteration usage counter failed after dispatch run={} user={} failureType={};"
              + " pipeline_run remains canonical",
          runId,
          actorId,
          usageFailure.getClass().getSimpleName());
    }
  }

  private IterationResult resolveClaim(
      RunIdempotencyClaim claim, String actorId, String expectedSourceRunId) {
    return switch (claim.status()) {
      case DISABLED, CLAIMED -> null;
      case DUPLICATE -> {
        PipelineRun prior =
            runRepo
                .findById(claim.runId())
                .filter(run -> actorId.equals(run.getCreatedByUserId()))
                .filter(run -> expectedSourceRunId.equals(run.getSourceRunId()))
                .orElseThrow(
                    () ->
                        new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "This Idempotency-Key is bound to an unavailable iteration."));
        yield new IterationResult(prior, true);
      }
      case CONFLICT ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "This Idempotency-Key was already used with a different iteration request.");
      case FAILED_FINAL ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "This Idempotency-Key already failed permanently. Use a new key.");
      case IN_PROGRESS ->
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "An iteration with this Idempotency-Key is still in flight. Retry shortly.");
    };
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary != null && !primary.isBlank() ? primary : fallback;
  }

  public record IterationResult(PipelineRun run, boolean replayed) {}
}
