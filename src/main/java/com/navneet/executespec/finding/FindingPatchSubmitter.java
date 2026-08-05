package com.navneet.executespec.finding;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.RunMode;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Single chokepoint for "this project-side finding wants a patch run." Used by:
 *
 * <ul>
 *   <li>{@code PrReviewService#dispatchAutofix} — Phase 1: address all PR review comments
 *   <li>{@code TechDebtFixService} — Phase 2: fix-by-category sweep (planned)
 *   <li>{@code CodeReviewAutofixService} — Phase 4: per-issue or per-severity autofix (planned)
 *   <li>{@code CiAutoFixListener} — Phase 3: webhook-triggered fix on failed PR check (planned)
 * </ul>
 *
 * <p>Each caller adapts its source data into a {@link Finding} and calls {@link #submit(Finding)}.
 * The submitter:
 *
 * <ol>
 *   <li>Dedupes against any existing active patch run for the same {@link Finding#findingKey()}. If
 *       one is in-flight, returns it without dispatching a duplicate (prevents double-PRs from
 *       impatient operators or duplicate webhooks).
 *   <li>Calls {@link RunOrchestrator#submitRunWithLocalExecution} with {@code runMode=PATCH} +
 *       {@code source} = {@code "finding:<TYPE>"} so the orchestrator's terminal-time push logic
 *       can distinguish project-mode finding patches (push to remote) from bench-mode runs (no push
 *       — bench services capture the diff themselves).
 *   <li>Stamps {@code triggered_by_finding} on the run row immediately after persistence so the
 *       dedupe lookup, the dashboard ribbon, and per-finding cost telemetry can all read it before
 *       the async pipeline thread reaches a checkpoint.
 * </ol>
 *
 * <p>Tier checks live in the calling service (each surface has its own tier matrix on tier_config).
 * The submitter assumes the caller has already enforced "user can dispatch this kind of finding."
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FindingPatchSubmitter {

  private final RunOrchestrator orchestrator;
  private final PipelineRunRepository runRepo;
  private final AppUserRepository userRepo;
  private final ProjectRepository projectRepo;
  private final ProjectRepoService projectRepoService;
  private final GitHubRepoService gitHubRepoService;

  /**
   * Result of {@link #submit(Finding)}. Either {@link Outcome#DISPATCHED} (a fresh run was created)
   * or {@link Outcome#REUSED_ACTIVE} (an existing in-flight run was returned). Callers surface the
   * {@code runId} either way so the user can navigate to {@code /runs/{id}} and watch progress.
   */
  public record SubmitResult(Outcome outcome, String runId, String findingKey) {
    public enum Outcome {
      DISPATCHED,
      REUSED_ACTIVE
    }
  }

  /**
   * Dispatch a patch-mode run for the supplied finding, deduping against any active run already
   * targeting the same finding key.
   */
  public SubmitResult submit(Finding finding) {
    if (finding == null) throw new IllegalArgumentException("finding is required");
    String key = finding.findingKey();

    // Resolve both authorities before even returning a deduped run id. The surface caller has
    // already authorized the finding action; this boundary still fails closed when its durable
    // actor/project records are missing or no longer eligible for work.
    SubmissionTenancy tenancy = resolveSubmissionTenancy(finding);

    // 1. Dedupe — if there's an active run for this finding, reuse it.
    List<PipelineRun> active = runRepo.findActiveByTriggeredByFinding(key);
    if (!active.isEmpty()) {
      PipelineRun existing =
          active.stream()
              .filter(run -> exactActiveRun(run, finding, tenancy, key))
              .findFirst()
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.CONFLICT,
                          "An active patch run exists for this finding, but its exact repository"
                              + " and publication authority cannot be verified."));
      log.info(
          "FindingPatchSubmitter REUSE_ACTIVE finding={} existing-run={} status={}",
          key,
          existing.getRunId(),
          existing.getStatus());
      return new SubmitResult(SubmitResult.Outcome.REUSED_ACTIVE, existing.getRunId(), key);
    }

    // A PR-shaped finding is allowed to spend only while its exact publication target remains
    // current. Re-check after canonical dedupe (replay must not depend on mutable GitHub state) and
    // before the orchestrator can make any provider call. This shared boundary covers both the
    // user-clicked PR-review path and unattended CI autofix deliveries.
    validatePublicationTarget(finding, tenancy);

    // 2. Submit through the server-internal PATCH route. The host project's org owns the data
    // boundary; the actor's home org remains the payer, credential and provider-policy authority.
    // This deliberately does not enter CustomerRunSubmissionService, whose admission contract is
    // BUILD-only and would incorrectly reclassify an already-authorized finding automation.
    String source = "finding:" + finding.type().name();
    PipelineRun run =
        orchestrator.submitRunWithLocalExecution(
            finding.userPrompt(),
            /* source */ source,
            /* repoUrl */ null,
            /* repoMode */ "EXISTING",
            /* githubPat */ tenancy.actor().getGithubPat(),
            /* githubOwner */ tenancy.actor().getGithubOwner(),
            /* skipApproval */ true,
            /* autoMerge */ false,
            /* modelName */ null,
            /* aiProvider */ finding.aiProvider(),
            /* createdByUserId */ finding.userId(),
            /* orgId */ tenancy.billingOrgId(),
            /* projectId */ finding.projectId(),
            /* executionMode */ "PROTOTYPE",
            /* selectedRepoIds */ finding.selectedRepoIds(),
            /* figmaUrl */ null,
            /* tddMode */ false,
            /* branchOverride */ finding.branchOverride(),
            /* prReviewComments */ null,
            /* runMode */ RunMode.PATCH,
            /* requireConfirmationBeforeExpensiveContinuation */ null,
            /* requireBenchmarkPriorForAutomaticContinuation */ null,
            /* lowComplexityWorkerStartTier */ null,
            /* localExecutionRequested */ false,
            new RunOrchestrator.SubmitPolicy(
                /* userCostCap */ null,
                /* skipAutoPush */ false,
                tenancy.tenantOrgId(),
                tenancy.billingOrgId(),
                key,
                /* checkoutBranch */ null,
                finding.publicationRepoFullName(),
                finding.publicationPrNumber(),
                finding.publicationHeadSha()));

    log.info(
        "FindingPatchSubmitter DISPATCHED finding={} run={} project={} repos={} branchOverride={}",
        key,
        run.getRunId(),
        finding.projectId(),
        finding.selectedRepoIds().size(),
        finding.branchOverride() == null ? "<new>" : finding.branchOverride());

    return new SubmitResult(SubmitResult.Outcome.DISPATCHED, run.getRunId(), key);
  }

  private SubmissionTenancy resolveSubmissionTenancy(Finding finding) {
    AppUser actor =
        userRepo
            .findById(finding.userId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Finding PATCH actor does not exist: " + finding.userId()));
    String billingOrgId = requireOrgId(actor.getOrgId(), "Finding PATCH actor");
    if (!"ACTIVE".equals(actor.getStatus())) {
      throw new IllegalStateException("Finding PATCH actor is not active: " + finding.userId());
    }

    Project project =
        projectRepo
            .findById(finding.projectId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Finding PATCH project does not exist: " + finding.projectId()));
    String tenantOrgId = requireOrgId(project.getOrgId(), "Finding PATCH project");
    if (!"ACTIVE".equals(project.getStatus()) || project.getDeletedAt() != null) {
      throw new IllegalStateException(
          "Finding PATCH project is not active: " + finding.projectId());
    }
    return new SubmissionTenancy(tenantOrgId, billingOrgId, actor, project);
  }

  private void validatePublicationTarget(Finding finding, SubmissionTenancy tenancy) {
    if (finding.branchOverride() == null || finding.branchOverride().isBlank()) return;

    List<ProjectRepo> repositories =
        projectRepoService.resolveForRunStrict(finding.projectId(), finding.selectedRepoIds());
    if (repositories.size() != 1) {
      throw publicationConflict("Existing-PR autofix requires exactly one selected repository.");
    }
    ProjectRepo repository = repositories.get(0);
    if (!"GITHUB".equalsIgnoreCase(repository.getScmProvider())
        || repository.getGithubRepoFullName() == null
        || !repository
            .getGithubRepoFullName()
            .equalsIgnoreCase(finding.publicationRepoFullName())) {
      throw publicationConflict(
          "The selected repository no longer matches the autofix pull request.");
    }

    String pat = tenancy.actor().getGithubPat();
    if (pat == null || pat.isBlank()) {
      throw publicationConflict("Connect GitHub before retrying this pull-request autofix.");
    }
    GitHubRepoService.OpenPullRequestLookup lookup =
        gitHubRepoService.findOpenPullRequestForHead(
            finding.publicationRepoFullName(), finding.branchOverride(), pat);
    if (!lookup.known()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "GitHub pull-request verification is temporarily unavailable.");
    }
    if (!lookup.present()
        || lookup.prNumber() == null
        || !lookup.prNumber().equals(finding.publicationPrNumber())) {
      throw publicationConflict("The autofix pull request is no longer open on this branch.");
    }
    if (lookup.headSha() == null
        || !lookup.headSha().equalsIgnoreCase(finding.publicationHeadSha())) {
      throw publicationConflict(
          "The pull-request head changed after this finding was captured; refresh and retry.");
    }

    String expectedBase = tenancy.project().getTargetBranch();
    if (expectedBase == null || expectedBase.isBlank()) {
      expectedBase = repository.getDefaultBranch();
    }
    if (expectedBase == null
        || lookup.baseBranch() == null
        || !expectedBase.equals(lookup.baseBranch())) {
      throw publicationConflict(
          "The autofix pull request no longer targets the configured project branch.");
    }
  }

  private static ResponseStatusException publicationConflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static boolean exactActiveRun(
      PipelineRun run, Finding finding, SubmissionTenancy tenancy, String findingKey) {
    return java.util.Objects.equals(run.getTriggeredByFinding(), findingKey)
        && java.util.Objects.equals(run.getCreatedByUserId(), finding.userId())
        && java.util.Objects.equals(run.getProjectId(), finding.projectId())
        && java.util.Objects.equals(run.getOrgId(), tenancy.tenantOrgId())
        && java.util.Objects.equals(run.getBillingOrgId(), tenancy.billingOrgId())
        && canonicalRepoIds(run.getSelectedRepoIds())
            .equals(canonicalRepoIds(finding.selectedRepoIds()))
        && java.util.Objects.equals(run.getCheckoutBranch(), finding.branchOverride())
        && java.util.Objects.equals(run.getBranchOverride(), finding.branchOverride())
        && java.util.Objects.equals(
            run.getPublicationRepoFullName(), finding.publicationRepoFullName())
        && java.util.Objects.equals(run.getPublicationPrNumber(), finding.publicationPrNumber())
        && java.util.Objects.equals(run.getPublicationHeadSha(), finding.publicationHeadSha());
  }

  private static String canonicalRepoIds(List<UUID> ids) {
    if (ids == null) return "";
    return ids.stream()
        .map(UUID::toString)
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static String canonicalRepoIds(String json) {
    if (json == null || json.isBlank()) return "";
    try {
      List<UUID> ids =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<UUID>>() {});
      return canonicalRepoIds(ids);
    } catch (Exception invalid) {
      return "!invalid-active-run-repository-identity!";
    }
  }

  private static String requireOrgId(String orgId, String owner) {
    if (orgId == null || orgId.isBlank()) {
      throw new IllegalStateException(owner + " has no organization");
    }
    return orgId;
  }

  private record SubmissionTenancy(
      String tenantOrgId, String billingOrgId, AppUser actor, Project project) {}
}
