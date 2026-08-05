package com.navneet.executespec.execution.service;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.UserDetailsServiceImpl;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.run.RunRequestFingerprint;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Submission adapter between Execution View's durable launch model and the V2 pipeline.
 *
 * <p>The unified pre-approval launch is customer-authorized and therefore enters the same admission
 * boundary as REST. Per-solution dispatch after the launch's durable plan approval remains a
 * server-authorized bypass with its distinct {@code EXECUTION_VIEW_APPROVED} source.
 */
@Service
@RequiredArgsConstructor
public class ExecutionPipelineSubmissionService {

  private static final String UNIFIED_IDEMPOTENCY_PREFIX = "execution-view:";

  private final CustomerRunSubmissionService customerRunSubmissionService;
  private final UserDetailsServiceImpl userDetailsService;
  private final RunOrchestrator runOrchestrator;
  private final ExecutionSolutionDispatchRegistry solutionDispatchRegistry;
  private final RunLifecycleService runLifecycleService;
  private final ProjectRepository projectRepository;
  private final ProjectRepoService projectRepoService;
  private final GitHubRepoService gitHubRepoService;

  public PipelineRun submitUnified(ExecutionRun execution, List<UUID> selectedRepoIds) {
    AppUserDetails actor = loadActor(execution.getCreatedBy());
    BranchIntent branchIntent = branchIntent(execution, selectedRepoIds);
    String fingerprint =
        RunRequestFingerprint.of(
            java.util.Arrays.asList(
                execution.getId(),
                execution.getRequirement(),
                execution.getProjectId(),
                execution.getOrgId(),
                execution.getAiProvider(),
                execution.getFigmaUrl(),
                execution.getBranch(),
                canonicalRepoIds(selectedRepoIds)));
    return customerRunSubmissionService
        .submit(
            CustomerRunSubmissionService.Command.builder()
                .userRequest(execution.getRequirement())
                .source("EXECUTION_VIEW")
                .repoMode("EXISTING")
                .autoMerge(true)
                .aiProvider(execution.getAiProvider())
                .projectId(execution.getProjectId())
                .executionMode("PRODUCTION")
                .selectedRepoIds(selectedRepoIds)
                .figmaUrl(execution.getFigmaUrl())
                .branchOverride(branchIntent.publicationBranch())
                .checkoutBranch(branchIntent.checkoutBranch())
                .existingPrRepoFullName(branchIntent.publicationRepoFullName())
                .existingPrNumber(branchIntent.publicationPrNumber())
                .idempotencyKey(UNIFIED_IDEMPOTENCY_PREFIX + execution.getId())
                .requestFingerprint(fingerprint)
                .build(),
            actor)
        .run();
  }

  public ApprovedSolutionSubmission submitApprovedSolution(
      ExecutionSolution solution,
      ExecutionRun execution,
      String fullRequest,
      List<UUID> selectedRepoIds,
      int attemptGeneration) {
    AppUserDetails actor = loadActor(execution.getCreatedBy());
    BranchIntent branchIntent = branchIntent(execution, selectedRepoIds);
    ExecutionSolutionDispatchRegistry.Reservation reservation =
        solutionDispatchRegistry.reserve(solution.getId(), attemptGeneration);
    PipelineRun canonical = solutionDispatchRegistry.findCanonicalRun(reservation).orElse(null);
    if (canonical != null) {
      verifyApprovedSolutionBinding(
          canonical, execution, actor.getUser().getOrgId(), selectedRepoIds);
      return new ApprovedSolutionSubmission(canonical, reservation.attempt());
    }
    String publicationHeadSha =
        validateContinuePrLive(execution, selectedRepoIds, branchIntent, actor);

    PipelineRun submitted;
    try {
      submitted =
          runOrchestrator.withReservedRunId(
              reservation.attempt().getPipelineRunId(),
              () ->
                  runOrchestrator.submitRunWithLocalExecution(
                      fullRequest,
                      "EXECUTION_VIEW_APPROVED",
                      null,
                      selectedRepoIds.isEmpty() ? "NEW" : "EXISTING",
                      actor.getUser().getGithubPat(),
                      actor.getUser().getGithubOwner(),
                      true,
                      true,
                      null,
                      execution.getAiProvider(),
                      execution.getCreatedBy(),
                      execution.getOrgId(),
                      execution.getProjectId(),
                      "PRODUCTION",
                      selectedRepoIds,
                      null,
                      false,
                      branchIntent.publicationBranch(),
                      null,
                      com.navneet.executespec.state.RunMode.BUILD,
                      null,
                      null,
                      null,
                      false,
                      new RunOrchestrator.SubmitPolicy(
                          null,
                          false,
                          execution.getOrgId(),
                          actor.getUser().getOrgId(),
                          null,
                          branchIntent.checkoutBranch(),
                          branchIntent.publicationRepoFullName(),
                          branchIntent.publicationPrNumber(),
                          publicationHeadSha)));
    } catch (RuntimeException submissionFailure) {
      // Any exception escaping submitRun occurs before it returns the registered async future. If
      // the initial row was already persisted, fence it now so a same-process retry cannot mistake
      // an unscheduled PLANNING row for a live canonical child. A real process crash is recovered
      // by
      // StartupRunResumer; this branch handles the non-crash exception window.
      solutionDispatchRegistry
          .findCanonicalRun(reservation)
          .ifPresent(
              canonicalRun -> {
                try {
                  verifyApprovedSolutionBinding(
                      canonicalRun, execution, actor.getUser().getOrgId(), selectedRepoIds);
                  runLifecycleService.cancel(canonicalRun.getRunId());
                } catch (RuntimeException fencingFailure) {
                  submissionFailure.addSuppressed(fencingFailure);
                }
              });
      throw submissionFailure;
    }
    verifyApprovedSolutionBinding(
        submitted, execution, actor.getUser().getOrgId(), selectedRepoIds);
    return new ApprovedSolutionSubmission(submitted, reservation.attempt());
  }

  private static void verifyApprovedSolutionBinding(
      PipelineRun run, ExecutionRun execution, String billingOrgId, List<UUID> selectedRepoIds) {
    boolean continueOnPr =
        execution.getContinuePrNumber() != null
            && execution.getContinuePrRepo() != null
            && !execution.getContinuePrRepo().isBlank();
    String expectedPublicationBranch = continueOnPr ? execution.getBranch() : null;
    boolean exact =
        "EXECUTION_VIEW_APPROVED".equals(run.getSourceType())
            && java.util.Objects.equals(run.getCreatedByUserId(), execution.getCreatedBy())
            && java.util.Objects.equals(run.getOrgId(), execution.getOrgId())
            && java.util.Objects.equals(run.getBillingOrgId(), billingOrgId)
            && java.util.Objects.equals(run.getProjectId(), execution.getProjectId())
            && canonicalPersistedRepoIds(run.getSelectedRepoIds())
                .equals(canonicalRepoIds(selectedRepoIds))
            && java.util.Objects.equals(run.getCheckoutBranch(), execution.getBranch())
            && java.util.Objects.equals(run.getBranchOverride(), expectedPublicationBranch)
            && java.util.Objects.equals(
                run.getPublicationRepoFullName(),
                continueOnPr ? execution.getContinuePrRepo() : null)
            && java.util.Objects.equals(
                run.getPublicationPrNumber(), continueOnPr ? execution.getContinuePrNumber() : null)
            && (continueOnPr
                ? run.getPublicationHeadSha() != null
                    && run.getPublicationHeadSha().matches("[0-9a-fA-F]{40}")
                : run.getPublicationHeadSha() == null);
    if (!exact) {
      throw new IllegalStateException(
          "reserved approved-solution child identity is bound to a different execution authority");
    }
  }

  public record ApprovedSolutionSubmission(PipelineRun run, ExecutionSolutionRun attempt) {
    public ApprovedSolutionSubmission {
      if (run == null) throw new IllegalArgumentException("run is required");
      if (attempt == null) throw new IllegalArgumentException("attempt is required");
      if (!run.getRunId().equals(attempt.getPipelineRunId())) {
        throw new IllegalArgumentException("attempt is not bound to the submitted run");
      }
    }
  }

  private AppUserDetails loadActor(String userId) {
    return (AppUserDetails) userDetailsService.loadUserById(userId);
  }

  private static String canonicalRepoIds(List<UUID> ids) {
    if (ids == null) return "";
    return ids.stream()
        .map(UUID::toString)
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }

  private static String canonicalPersistedRepoIds(String json) {
    if (json == null || json.isBlank()) return "";
    try {
      List<UUID> parsed =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<UUID>>() {});
      return canonicalRepoIds(parsed);
    } catch (Exception invalid) {
      return "!invalid-persisted-repository-identity!";
    }
  }

  static BranchIntent branchIntent(ExecutionRun execution, List<UUID> selectedRepoIds) {
    boolean hasNumber = execution.getContinuePrNumber() != null;
    boolean hasRepo =
        execution.getContinuePrRepo() != null && !execution.getContinuePrRepo().isBlank();
    if (hasNumber != hasRepo) {
      throw new IllegalStateException("continue-on-PR requires both repository and PR number");
    }
    if (!hasNumber) return new BranchIntent(execution.getBranch(), null, null, null);
    if (execution.getBranch() == null || execution.getBranch().isBlank()) {
      throw new IllegalStateException("continue-on-PR has no resolved head branch");
    }
    if (selectedRepoIds == null || selectedRepoIds.size() != 1) {
      throw new IllegalStateException("continue-on-PR requires exactly one selected repository");
    }
    return new BranchIntent(
        execution.getBranch(),
        execution.getBranch(),
        execution.getContinuePrRepo(),
        execution.getContinuePrNumber());
  }

  private String validateContinuePrLive(
      ExecutionRun execution,
      List<UUID> selectedRepoIds,
      BranchIntent intent,
      AppUserDetails actor) {
    if (intent.publicationBranch() == null) return null;
    var project =
        projectRepository
            .findById(execution.getProjectId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "Continue-on-PR project no longer exists"));
    if (!java.util.Objects.equals(project.getOrgId(), execution.getOrgId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Continue-on-PR project authority changed");
    }
    var repos = projectRepoService.resolveForRunStrict(execution.getProjectId(), selectedRepoIds);
    if (repos.size() != 1
        || !repos
            .get(0)
            .getGithubRepoFullName()
            .equalsIgnoreCase(intent.publicationRepoFullName())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Continue-on-PR repository is not the selected project repository");
    }
    String pat = actor.getUser().getGithubPat();
    if (pat == null || pat.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Continue-on-PR requires a connected GitHub account");
    }
    GitHubRepoService.OpenPullRequestLookup lookup =
        gitHubRepoService.findOpenPullRequestForHead(
            intent.publicationRepoFullName(), intent.publicationBranch(), pat);
    String expectedBase =
        project.getTargetBranch() != null && !project.getTargetBranch().isBlank()
            ? project.getTargetBranch()
            : repos.get(0).getDefaultBranch();
    if (!lookup.known()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "GitHub pull-request verification is temporarily unavailable");
    }
    if (!lookup.present()
        || !intent.publicationPrNumber().equals(lookup.prNumber())
        || expectedBase == null
        || !expectedBase.equals(lookup.baseBranch())
        || lookup.headSha() == null
        || !lookup.headSha().matches("[0-9a-fA-F]{40}")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Continue-on-PR is no longer an open pull request against the configured target");
    }
    return lookup.headSha();
  }

  record BranchIntent(
      String checkoutBranch,
      String publicationBranch,
      String publicationRepoFullName,
      Integer publicationPrNumber) {}
}
