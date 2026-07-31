package com.navneet.executespec.execution.service;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.integration.scm.PublicationAdmissionService;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Option C — hybrid tagged commits on a per-repo {@code exec/<executionRunId>} branch. Per-solution
 * work is bracketed by {@code exec/<runId>/s<N>-start} / {@code -end} markers stored on {@link
 * ExecutionSolution#getGitTagStart()} / {@code getGitTagEnd()} so a failed solution can be
 * surgically rolled back and the final per-repo PR bundles everything. Actual git ops (tag /
 * revert) are deferred to follow-up work; this service tracks the marker names + opens the final PR
 * via {@link GitHubRepoService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionBranchService {

  private final GitHubRepoService gitHubRepoService;
  private final PullRequestRecordService prRecordService;
  private final PipelineRunRepository pipelineRunRepository;
  private final PublicationAdmissionService publicationAdmissionService;

  public record FinalPrResult(
      boolean created,
      PullRequestRecord record,
      String code,
      String blockingGate,
      String message,
      boolean retryable,
      String remediation,
      String pipelineRunId) {

    static FinalPrResult created(PullRequestRecord record) {
      return new FinalPrResult(
          true,
          record,
          "EXECUTION_FINAL_PR_CREATED",
          null,
          "Final pull request created",
          false,
          null,
          null);
    }

    static FinalPrResult blocked(
        String code,
        String blockingGate,
        String message,
        boolean retryable,
        String remediation,
        String pipelineRunId) {
      return new FinalPrResult(
          false, null, code, blockingGate, message, retryable, remediation, pipelineRunId);
    }
  }

  public String branchName(String executionRunId) {
    return "exec/" + executionRunId.substring(0, Math.min(8, executionRunId.length()));
  }

  public String tagStart(String executionRunId, int seq) {
    return branchName(executionRunId) + "/s" + seq + "-start";
  }

  public String tagEnd(String executionRunId, int seq) {
    return branchName(executionRunId) + "/s" + seq + "-end";
  }

  public void tagSolutionStart(ExecutionRun run, ExecutionSolution s) {
    s.setGitTagStart(tagStart(run.getId(), s.getSequenceNumber()));
  }

  public void tagSolutionEnd(ExecutionRun run, ExecutionSolution s) {
    s.setGitTagEnd(tagEnd(run.getId(), s.getSequenceNumber()));
  }

  /** Final PR for all solutions on a repo: {@code exec/<runId>} → default. */
  public FinalPrResult createFinalPr(
      String repoFullName,
      UUID repoId,
      String executionRunId,
      List<String> backingPipelineRunIds,
      String reportBody,
      String githubPat) {
    if (repoFullName == null || repoFullName.isBlank()) {
      return FinalPrResult.blocked(
          "EXECUTION_FINAL_PR_REPOSITORY_MISSING",
          "REPOSITORY_RESOLUTION",
          "Final pull-request creation requires a repository",
          false,
          "Restore the execution repository mapping before retrying publication.",
          null);
    }
    List<String> runIds =
        backingPipelineRunIds == null
            ? List.of()
            : backingPipelineRunIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    if (runIds.isEmpty()) {
      return FinalPrResult.blocked(
          "EXECUTION_FINAL_PR_BACKING_RUN_MISSING",
          "RUN",
          "Final pull-request creation requires at least one backing pipeline run",
          false,
          "Repair the execution-to-pipeline-run linkage before retrying publication.",
          null);
    }
    for (String pipelineRunId : runIds) {
      com.navneet.executespec.state.entity.PipelineRun pipelineRun;
      try {
        pipelineRun = pipelineRunRepository.findById(pipelineRunId).orElse(null);
      } catch (RuntimeException unavailable) {
        return FinalPrResult.blocked(
            "EXECUTION_FINAL_PR_ADMISSION_UNAVAILABLE",
            "PUBLICATION_ADMISSION",
            "Backing pipeline-run evidence could not be loaded",
            true,
            "Restore database availability and retry final publication.",
            pipelineRunId);
      }
      if (pipelineRun == null) {
        return FinalPrResult.blocked(
            "EXECUTION_FINAL_PR_BACKING_RUN_NOT_FOUND",
            "RUN",
            "Backing pipeline run could not be loaded",
            false,
            "Restore the missing pipeline-run record before retrying publication.",
            pipelineRunId);
      }
      try {
        publicationAdmissionService.assertEligible(pipelineRun);
      } catch (PublicationAdmissionService.PublicationBlockedException blocked) {
        var eligibility = blocked.eligibility();
        return FinalPrResult.blocked(
            blocked.code(),
            eligibility.blockingGate(),
            blocked.getMessage(),
            eligibility.retryable(),
            eligibility.remediation(),
            pipelineRunId);
      } catch (RuntimeException unavailable) {
        log.warn(
            "createFinalPr exec={} pipeline={} admission unavailable: {}",
            executionRunId,
            pipelineRunId,
            unavailable.getMessage());
        return FinalPrResult.blocked(
            "EXECUTION_FINAL_PR_ADMISSION_UNAVAILABLE",
            "PUBLICATION_ADMISSION",
            "Publication admission could not be evaluated",
            true,
            "Restore publication-evidence availability and retry. No Git operation was attempted.",
            pipelineRunId);
      }
    }
    String head = branchName(executionRunId);
    String title = "Execution " + executionRunId.substring(0, 8) + " — combined solutions";
    String prUrl;
    try {
      prUrl =
          gitHubRepoService.createPullRequest(
              "https://github.com/" + repoFullName,
              head,
              null,
              title,
              reportBody == null ? "" : reportBody,
              githubPat);
      if (prUrl == null) {
        return FinalPrResult.blocked(
            "EXECUTION_FINAL_PR_CREATE_FAILED",
            "PR_CREATION",
            "GitHub did not return a pull-request URL",
            true,
            "Check repository permissions and branch state, then retry final publication.",
            null);
      }
    } catch (Exception e) {
      log.warn(
          "createFinalPr repo={} exec={} failed: {}", repoFullName, executionRunId, e.getMessage());
      return FinalPrResult.blocked(
          "EXECUTION_FINAL_PR_CREATE_FAILED",
          "PR_CREATION",
          e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
          true,
          "Check repository credentials, branch protection, and GitHub availability, then retry.",
          null);
    }
    try {
      PullRequestRecord record =
          prRecordService.recordCreated(null, repoId, repoFullName, prUrl, head, null);
      if (record != null) return FinalPrResult.created(record);
    } catch (RuntimeException persistenceFailure) {
      log.warn(
          "createFinalPr repo={} exec={} PR={} record persistence failed: {}",
          repoFullName,
          executionRunId,
          prUrl,
          persistenceFailure.getMessage());
    }
    return FinalPrResult.blocked(
        "EXECUTION_FINAL_PR_RECORD_FAILED",
        "PR_STATE_PERSISTENCE",
        "The pull request was created at " + prUrl + " but its durable record was not persisted",
        false,
        "Refresh or reconcile pull-request state; do not retry PR creation.",
        null);
  }
}
