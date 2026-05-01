package com.navneet.executespec.execution.service;

import java.util.UUID;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.repo.GitHubRepoService;

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
  public PullRequestRecord createFinalPr(
      String repoFullName,
      UUID repoId,
      String executionRunId,
      String reportBody,
      String githubPat) {
    if (repoFullName == null || repoFullName.isBlank()) return null;
    try {
      String head = branchName(executionRunId);
      String title = "Execution " + executionRunId.substring(0, 8) + " — combined solutions";
      String prUrl =
          gitHubRepoService.createPullRequest(
              "https://github.com/" + repoFullName,
              head,
              null,
              title,
              reportBody == null ? "" : reportBody,
              githubPat);
      if (prUrl == null) return null;
      return prRecordService.recordCreated(null, repoId, repoFullName, prUrl, head, null);
    } catch (Exception e) {
      log.warn(
          "createFinalPr repo={} exec={} failed: {}", repoFullName, executionRunId, e.getMessage());
      return null;
    }
  }
}
