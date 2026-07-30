package com.navneet.executespec.execution.service;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.UserDetailsServiceImpl;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.entity.ExecutionSolutionRun;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.run.RunRequestFingerprint;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

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

  public PipelineRun submitUnified(ExecutionRun execution, List<UUID> selectedRepoIds) {
    AppUserDetails actor = loadActor(execution.getCreatedBy());
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
                .branchOverride(execution.getBranch())
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
    ExecutionSolutionDispatchRegistry.Reservation reservation =
        solutionDispatchRegistry.reserve(solution.getId(), attemptGeneration);
    PipelineRun canonical = solutionDispatchRegistry.findCanonicalRun(reservation).orElse(null);
    if (canonical != null) {
      verifyApprovedSolutionBinding(canonical, execution, actor.getUser().getOrgId());
      return new ApprovedSolutionSubmission(canonical, reservation.attempt());
    }

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
                      execution.getBranch(),
                      null,
                      com.navneet.executespec.state.RunMode.BUILD,
                      null,
                      null,
                      null,
                      false,
                      new RunOrchestrator.SubmitPolicy(
                          null, false, execution.getOrgId(), actor.getUser().getOrgId(), null)));
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
                      canonicalRun, execution, actor.getUser().getOrgId());
                  runLifecycleService.cancel(canonicalRun.getRunId());
                } catch (RuntimeException fencingFailure) {
                  submissionFailure.addSuppressed(fencingFailure);
                }
              });
      throw submissionFailure;
    }
    verifyApprovedSolutionBinding(submitted, execution, actor.getUser().getOrgId());
    return new ApprovedSolutionSubmission(submitted, reservation.attempt());
  }

  private static void verifyApprovedSolutionBinding(
      PipelineRun run, ExecutionRun execution, String billingOrgId) {
    boolean exact =
        "EXECUTION_VIEW_APPROVED".equals(run.getSourceType())
            && java.util.Objects.equals(run.getCreatedByUserId(), execution.getCreatedBy())
            && java.util.Objects.equals(run.getOrgId(), execution.getOrgId())
            && java.util.Objects.equals(run.getBillingOrgId(), billingOrgId)
            && java.util.Objects.equals(run.getProjectId(), execution.getProjectId());
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
    if (ids == null) return "null";
    return ids.stream()
        .map(UUID::toString)
        .sorted()
        .collect(java.util.stream.Collectors.joining(","));
  }
}
