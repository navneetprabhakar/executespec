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
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
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
  private final GitHubRepoService gitHubRepoService;
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
    CodeBoundary boundary = authorizeCodeBoundary(command, actor, localExecution);

    RunIdempotencyClaim claim =
        runIdempotencyService.claim(userId, command.idempotencyKey(), command.requestFingerprint());
    PipelineRun duplicate = resolvePriorRun(claim, userId);
    if (duplicate != null) {
      return new Submission(duplicate, false, localExecution);
    }

    PipelineRun run;
    try {
      PublicationTarget publicationTarget =
          validateExistingPrTarget(command, actor, boundary, localExecution);
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
                  boundary.project() == null
                      ? actor.getUser().getOrgId()
                      : boundary.project().getOrgId(),
                  actor.getUser().getOrgId(),
                  null,
                  command.checkoutBranch(),
                  publicationTarget.repoFullName(),
                  publicationTarget.prNumber(),
                  publicationTarget.headSha()));
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
    if (blankToNull(command.branchOverride()) != null
        && !com.navneet.executespec.run.sync.RunSyncService.isValidBranch(
            command.branchOverride().trim())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "branch is invalid");
    }
    if (blankToNull(command.checkoutBranch()) != null
        && !com.navneet.executespec.run.sync.RunSyncService.isValidBranch(
            command.checkoutBranch().trim())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "checkout branch is invalid");
    }
    if (command.localExecution() && blankToNull(command.branchOverride()) != null) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Local-hands execution cannot publish to an existing pull request; submit a remote run"
              + " for that PR instead.");
    }
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
    if (command.localExecution()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Local BUILD requires generation-bound verification receipts that this client protocol"
              + " does not yet provide. Submit a remote BUILD run instead.");
    }
  }

  private CodeBoundary authorizeCodeBoundary(
      Command command, AppUserDetails actor, boolean localExecution) {
    if (command.projectId() == null || command.projectId().isBlank()) {
      return new CodeBoundary(null, List.of());
    }
    Project project = projectAccessGuard.assertCanWrite(command.projectId(), actor);
    List<ProjectRepo> repos;
    if (localExecution) {
      repos =
          projectRepoService.resolveForLocalRunStrict(
              command.projectId(),
              command.selectedRepoIds(),
              command.localInstallationId(),
              actor.getUser().getId());
    } else {
      repos =
          projectRepoService.resolveForRunStrict(command.projectId(), command.selectedRepoIds());
    }
    return new CodeBoundary(project, repos);
  }

  private PublicationTarget validateExistingPrTarget(
      Command command, AppUserDetails actor, CodeBoundary boundary, boolean localExecution) {
    if (boundary.project() == null) {
      return validateStandaloneExistingPrTarget(command, actor, localExecution);
    }
    return validateProjectExistingPrTarget(
        command, actor, boundary.project(), boundary.repositories(), localExecution);
  }

  private PublicationTarget validateStandaloneExistingPrTarget(
      Command command, AppUserDetails actor, boolean localExecution) {
    String branch = blankToNull(command.branchOverride());
    if (branch == null || localExecution) return PublicationTarget.NONE;
    if (blankToNull(command.repoUrl()) == null
        || !command.repoUrl().startsWith("https://github.com/")) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "An existing-branch run requires a GitHub repository and an open pull request.");
    }
    String repoFullName = githubFullName(command.repoUrl());
    String pat = actor.getUser().getGithubPat();
    return requireExistingPrHead(
        command.repoUrl(),
        repoFullName,
        branch.trim(),
        pat,
        null,
        command.existingPrRepoFullName(),
        command.existingPrNumber());
  }

  private PublicationTarget validateProjectExistingPrTarget(
      Command command,
      AppUserDetails actor,
      Project project,
      List<ProjectRepo> repos,
      boolean localExecution) {
    String branch = blankToNull(command.branchOverride());
    if (branch == null || localExecution) return PublicationTarget.NONE;
    if (repos.size() != 1) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Existing-PR publication requires exactly one selected repository.");
    }
    String normalized = branch.trim();
    if (normalized.equals(project.getTargetBranch())) {
      throw protectedBranch();
    }
    ProjectRepo repo = repos.get(0);
    if (!"GITHUB".equalsIgnoreCase(repo.getScmProvider())) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Existing-branch runs currently require a GitHub project repository.");
    }
    if (normalized.equals(repo.getDefaultBranch())) throw protectedBranch();
    return requireExistingPrHead(
        "https://github.com/" + repo.getGithubRepoFullName(),
        repo.getGithubRepoFullName(),
        normalized,
        actor.getUser().getGithubPat(),
        blankToNull(project.getTargetBranch()) == null
            ? repo.getDefaultBranch()
            : project.getTargetBranch(),
        command.existingPrRepoFullName(),
        command.existingPrNumber());
  }

  private PublicationTarget requireExistingPrHead(
      String repoUrl,
      String repoFullName,
      String branch,
      String pat,
      String knownDefaultBranch,
      String expectedRepoFullName,
      Integer expectedPrNumber) {
    if (blankToNull(pat) == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Connect GitHub before targeting an existing pull-request branch.");
    }
    String defaultBranch = knownDefaultBranch;
    if (blankToNull(defaultBranch) == null) {
      GitHubRepoService.DefaultBranchLookup defaultLookup =
          gitHubRepoService.findDefaultBranch(repoUrl, pat);
      if (!defaultLookup.known()) {
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "GitHub default-branch verification is temporarily unavailable.");
      }
      defaultBranch = defaultLookup.branch();
    }
    if (branch.equals(defaultBranch)) throw protectedBranch();
    GitHubRepoService.OpenPullRequestLookup lookup =
        gitHubRepoService.findOpenPullRequestForHead(repoFullName, branch, pat);
    if (!lookup.known()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "GitHub pull-request verification is temporarily unavailable.");
    }
    if (!lookup.present()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The selected branch is not the head of an open pull request.");
    }
    if (blankToNull(lookup.baseBranch()) == null || !defaultBranch.equals(lookup.baseBranch())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The selected pull request does not target the configured project branch.");
    }
    if (blankToNull(expectedRepoFullName) != null
        && !repoFullName.equalsIgnoreCase(expectedRepoFullName.trim())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "The selected repository does not match the requested pull request.");
    }
    if (expectedPrNumber != null && !expectedPrNumber.equals(lookup.prNumber())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "The selected branch resolves to a different pull request.");
    }
    if (blankToNull(lookup.headSha()) == null || !lookup.headSha().matches("[0-9a-fA-F]{40}")) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "GitHub pull-request head verification is temporarily unavailable.");
    }
    return new PublicationTarget(repoFullName, lookup.prNumber(), lookup.headSha());
  }

  private static ResponseStatusException protectedBranch() {
    return new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "The repository default or target branch cannot be used as an existing PR head.");
  }

  private static String githubFullName(String repoUrl) {
    String fullName = repoUrl.substring("https://github.com/".length());
    if (fullName.endsWith(".git")) fullName = fullName.substring(0, fullName.length() - 4);
    if (!fullName.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub repository URL is invalid");
    }
    return fullName;
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
    modelAccessService.assertProviderAllowed(userId, provider);
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

  private record CodeBoundary(Project project, List<ProjectRepo> repositories) {}

  private record PublicationTarget(String repoFullName, Integer prNumber, String headSha) {
    private static final PublicationTarget NONE = new PublicationTarget(null, null, null);
  }

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
      String checkoutBranch,
      String existingPrRepoFullName,
      Integer existingPrNumber,
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
