package com.navneet.executespec.project.pr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.v2.integration.V2RunOrchestrator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 2026-05-02 — auto-respond to GitHub {@code check_run.failure} events on a PR by submitting a
 * fresh V2 run that targets the SAME head branch (via {@code branchOverride}), seeded with the
 * failure context so the planner has the broken signal to fix.
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>{@link GitHubWebhookController} receives {@code X-GitHub-Event: check_run} with {@code
 *       conclusion=failure}.
 *   <li>Calls {@link #onCheckRunFailed} with repo full name + head branch + failure summary +
 *       check-run details URL.
 *   <li>Service looks up the {@link PullRequestRecord} by (repo, branch) and the originating {@link
 *       PipelineRun} by {@code runId}.
 *   <li>Bails if {@link #MAX_AUTO_FIX_ATTEMPTS} reached, the originating run is missing the project
 *       / org context (so we can't re-submit cleanly), or the PR is no longer OPEN.
 *   <li>Builds a seeded user request: original spec + failure tail + same-branch instruction.
 *   <li>Submits via {@link V2RunOrchestrator#submitRun} with {@code branchOverride=headBranch} so
 *       {@code WorkspaceInitService.cloneMultipleRepos} clones from the PR branch's tip and {@code
 *       ScmPushService} pushes incrementally onto the same branch.
 *   <li>Increments {@code ciAutoFixAttempts} on the PR record.
 * </ol>
 *
 * <p>Retry budget: {@link #MAX_AUTO_FIX_ATTEMPTS} per PR. After cap is hit, the webhook still
 * returns 204 (no retry storm) but no run is queued — operator picks it up manually.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CiFailureAutoFixService {

  /** Hard cap on auto-fix iterations per PR. Beyond this, surface to the human reviewer. */
  public static final int MAX_AUTO_FIX_ATTEMPTS = 3;

  private final PullRequestRecordRepository prRepo;
  private final PipelineRunRepository pipelineRunRepo;
  private final V2RunOrchestrator v2RunOrchestrator;
  private final AppUserRepository userRepository;

  /**
   * Result of an auto-fix dispatch attempt. The {@code newRunId} is set when a fresh run was
   * actually submitted; {@code reason} carries the rationale for skip/decline so the webhook caller
   * can log it for ops.
   */
  public record AutoFixResult(boolean dispatched, String newRunId, String reason) {
    public static AutoFixResult skipped(String reason) {
      return new AutoFixResult(false, null, reason);
    }

    public static AutoFixResult dispatched(String runId) {
      return new AutoFixResult(true, runId, "ok");
    }
  }

  /**
   * Entry point from {@link GitHubWebhookController}. All four arguments are best-effort: missing
   * fields just degrade the seeded prompt, never throw.
   *
   * @param repoFullName e.g. {@code pplai-app/pplai-api} (from {@code repository.full_name})
   * @param headBranch the PR's head branch (from {@code check_run.check_suite.head_branch})
   * @param checkRunSummary short summary of the failure (from {@code check_run.output.summary} or
   *     the conclusion text). Truncated to 4KB before being shoved into the seeded prompt to keep
   *     planner cost bounded.
   * @param checkRunUrl GitHub URL of the check run for human inspection (from {@code
   *     check_run.html_url}). Lands at the bottom of the seeded prompt as a deep-link.
   */
  @Transactional
  public AutoFixResult onCheckRunFailed(
      String repoFullName, String headBranch, String checkRunSummary, String checkRunUrl) {
    if (repoFullName == null
        || repoFullName.isBlank()
        || headBranch == null
        || headBranch.isBlank()) {
      return AutoFixResult.skipped("missing repo or branch");
    }

    Optional<PullRequestRecord> prOpt =
        prRepo.findFirstByRepoFullNameAndHeadBranchOrderByCreatedAtDesc(repoFullName, headBranch);
    if (prOpt.isEmpty()) {
      log.debug(
          "CiFailureAutoFixService: no PR record for {} / {} — branch was not opened by"
              + " ExecuteSpec, ignoring",
          repoFullName,
          headBranch);
      return AutoFixResult.skipped("no PR record for branch");
    }
    PullRequestRecord pr = prOpt.get();

    if (pr.getStatus() != PullRequestRecord.Status.OPEN) {
      return AutoFixResult.skipped("PR is " + pr.getStatus() + " — no auto-fix");
    }
    int attempts = pr.getCiAutoFixAttempts() == null ? 0 : pr.getCiAutoFixAttempts();
    if (attempts >= MAX_AUTO_FIX_ATTEMPTS) {
      log.warn(
          "CiFailureAutoFixService: {} #{} reached auto-fix cap ({}/{}) — surfacing to human",
          pr.getRepoFullName(),
          pr.getPrNumber(),
          attempts,
          MAX_AUTO_FIX_ATTEMPTS);
      return AutoFixResult.skipped(
          "auto-fix cap reached (" + attempts + "/" + MAX_AUTO_FIX_ATTEMPTS + ")");
    }

    if (pr.getRunId() == null || pr.getRunId().isBlank()) {
      return AutoFixResult.skipped("PR has no originating run_id (system PR, not a Launch)");
    }
    Optional<PipelineRun> runOpt = pipelineRunRepo.findById(pr.getRunId());
    if (runOpt.isEmpty()) {
      return AutoFixResult.skipped("originating run " + pr.getRunId() + " not found");
    }
    PipelineRun original = runOpt.get();

    // Re-submit context. We need the original user/org/project to charge credits + scope the run
    // correctly; the project is required for a multi-repo Launch (selectedRepoIds reads from
    // there). If any are missing, bail rather than guess.
    if (original.getCreatedByUserId() == null || original.getProjectId() == null) {
      return AutoFixResult.skipped("originating run missing user/project context");
    }

    // Seeded user request: original spec + failure tail + same-branch instruction. Keep it
    // bounded so the planner's context budget isn't wrecked by a 10MB CI log.
    String tail = truncate(checkRunSummary == null ? "" : checkRunSummary, 4_000);
    String seeded =
        "## Auto-fix re-run (CI failure on PR branch `"
            + headBranch
            + "`)\n\n"
            + "Original spec:\n\n"
            + (original.getUserRequest() == null ? "(missing)" : original.getUserRequest())
            + "\n\n---\n\n"
            + "**CI failed on the most recent commit.** Diagnose the failure below, fix the root\n"
            + "cause, and push your fix to the same branch (the workspace will be cloned from\n"
            + "this branch's HEAD via branchOverride; ScmPushService.pushToExistingBranch will\n"
            + "land additional commits without force-pushing).\n\n"
            + "Failure summary (truncated to 4KB):\n```\n"
            + (tail.isBlank() ? "(no summary in webhook payload)" : tail)
            + "\n```\n\n"
            + (checkRunUrl != null && !checkRunUrl.isBlank()
                ? "Check run details: " + checkRunUrl + "\n\n"
                : "")
            + "Auto-fix attempt "
            + (attempts + 1)
            + " of "
            + MAX_AUTO_FIX_ATTEMPTS
            + ".\n";

    String pat = resolveCallerPat(original.getCreatedByUserId());

    PipelineRun newRun;
    try {
      newRun =
          v2RunOrchestrator.submitRun(
              seeded,
              "ci-auto-fix",
              null, // repoUrl — multi-repo path uses selectedRepoIds
              "EXISTING",
              pat,
              null, // githubOwner
              true, // skipApproval — auto-fix runs auto-approve to avoid stalling on the gate
              true, // autoMerge irrelevant for branchOverride path
              null, // modelName — defer to project default
              original.getAiProvider(),
              original.getCreatedByUserId(),
              original.getOrgId(),
              original.getProjectId(),
              "REALTIME",
              parseRepoIds(original.getSelectedRepoIds()),
              original.getFigmaUrl(),
              false, // tddMode
              headBranch);
    } catch (Exception e) {
      log.warn(
          "CiFailureAutoFixService: V2 submitRun failed for {} #{}: {}",
          repoFullName,
          pr.getPrNumber(),
          e.getMessage());
      return AutoFixResult.skipped("submitRun failed: " + e.getMessage());
    }

    pr.setCiAutoFixAttempts(attempts + 1);
    pr.setUpdatedAt(Instant.now());
    prRepo.save(pr);

    log.info(
        "CiFailureAutoFixService: auto-fix run {} dispatched for {} #{} (attempt {}/{}),"
            + " branchOverride={}",
        newRun.getRunId(),
        repoFullName,
        pr.getPrNumber(),
        attempts + 1,
        MAX_AUTO_FIX_ATTEMPTS,
        headBranch);
    return AutoFixResult.dispatched(newRun.getRunId());
  }

  private String resolveCallerPat(String userId) {
    if (userId == null) return null;
    try {
      return userRepository.findById(userId).map(u -> u.getGithubPat()).orElse(null);
    } catch (Exception e) {
      return null;
    }
  }

  /** Parse {@code selected_repo_ids} (compact JSON array of UUID strings) → list of UUID. */
  private List<UUID> parseRepoIds(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      // Lightweight parse — array of quoted UUIDs, no nested structures.
      String stripped = json.trim();
      if (stripped.startsWith("[") && stripped.endsWith("]")) {
        stripped = stripped.substring(1, stripped.length() - 1);
      }
      if (stripped.isBlank()) return List.of();
      return java.util.Arrays.stream(stripped.split(","))
          .map(s -> s.trim().replaceAll("^\"|\"$", ""))
          .filter(s -> !s.isEmpty())
          .map(
              s -> {
                try {
                  return UUID.fromString(s);
                } catch (Exception e) {
                  return null;
                }
              })
          .filter(java.util.Objects::nonNull)
          .toList();
    } catch (Exception e) {
      log.debug("parseRepoIds failed: {}", e.getMessage());
      return List.of();
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    if (s.length() <= max) return s;
    return s.substring(0, max) + "\n\n...(truncated, " + s.length() + " chars total)";
  }
}
