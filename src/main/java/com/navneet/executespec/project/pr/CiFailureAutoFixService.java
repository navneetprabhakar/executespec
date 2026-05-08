package com.navneet.executespec.project.pr;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.finding.FindingType;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

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
 *   <li>Submits via {@link RunOrchestrator#submitRun} with {@code branchOverride=headBranch} so
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
  private final FindingPatchSubmitter findingSubmitter;
  private final AppUserRepository userRepository;
  private final FeatureFlagService featureFlagService;

  /**
   * V183 — feature flag gating the auto-fire webhook path. Default OFF until the
   * SWE-bench matrix data confirms patch-mode resolved% is high enough to trust the
   * bot. Operators flip to true via {@code PUT /api/feature-flags/ci.autofix.enabled}.
   * The other phases (PR Review autofix, Tech Debt, Code Review) are user-clicked so
   * they don't need the gate; only the unattended webhook path does.
   */
  static final String FEATURE_FLAG = "ci.autofix.enabled";

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

    // V183 — feature-flag gate. Webhook still 204s (no retry storm), but no run is
    // queued until the matrix data confirms patch-mode resolved% is reliable enough
    // to trust the unattended bot path. Manual user-clicked autofixes (Phase 1, 2, 4)
    // ignore this flag because the user is the gate.
    if (!featureFlagService.isEnabled(FEATURE_FLAG)) {
      log.debug(
          "CiFailureAutoFixService: skipped {} / {} — feature flag {} disabled",
          repoFullName,
          headBranch,
          FEATURE_FLAG);
      return AutoFixResult.skipped("feature flag " + FEATURE_FLAG + " disabled");
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

    // Re-submit context. Need the original user/project to scope the patch run; the
    // selectedRepoIds come from the originating run's column so the workspace clones
    // the same repos.
    if (original.getCreatedByUserId() == null || original.getProjectId() == null) {
      return AutoFixResult.skipped("originating run missing user/project context");
    }

    // Seeded user prompt — finding shape this time. We don't replay the full original
    // spec the way the legacy build-mode path did; the patch agent is meant to fix
    // ONE failing CI check, not redo the whole feature. Embedding the failure log is
    // the highest-value signal — that's what SWE-bench taught us.
    String tail = truncate(checkRunSummary == null ? "" : checkRunSummary, 4_000);
    String prompt =
        "Address the CI check failure below on PR `"
            + repoFullName
            + "#"
            + pr.getPrNumber()
            + "` (branch `"
            + headBranch
            + "`).\n\n"
            + "# Failing CI check (verbatim)\n\n```\n"
            + (tail.isBlank() ? "(no summary in webhook payload)" : tail)
            + "\n```\n\n"
            + (checkRunUrl != null && !checkRunUrl.isBlank()
                ? "GitHub: " + checkRunUrl + "\n\n"
                : "")
            + "# How to work\n\n"
            + "1. Read the failure trace above carefully — the assertion / error /\n"
            + "   stack tells you exactly what to fix.\n"
            + "2. The workspace is cloned from the PR's head branch; you see the\n"
            + "   current state of the PR's commit.\n"
            + "3. Apply minimum-change fixes. Do NOT rewrite the whole feature — the\n"
            + "   PR's existing commits are what the reviewer wants.\n"
            + "4. Run `runTests` (or the project's test runner via `shell`) to confirm\n"
            + "   the failure is gone before completing.\n"
            + "5. Commit will be pushed automatically to the same PR branch (no force-\n"
            + "   push, additive commits only).\n\n"
            + "# Constraints\n\n"
            + "- Auto-fix attempt "
            + (attempts + 1)
            + " of "
            + MAX_AUTO_FIX_ATTEMPTS
            + " — after the cap is reached the PR is surfaced to the human reviewer.\n"
            + "- Do NOT modify tests unless the failure is genuinely a wrong test\n"
            + "  expectation (rare). When in doubt, fix the production code.\n";

    Finding finding =
        new Finding(
            FindingType.CI_FAILURE,
            // Combined PR id + attempt index so a re-failure on the same branch can
            // dispatch a fresh run (each attempt has a unique finding key) within the
            // MAX_AUTO_FIX_ATTEMPTS budget.
            pr.getId() + "|attempt-" + (attempts + 1),
            original.getProjectId(),
            parseRepoIds(original.getSelectedRepoIds()),
            prompt,
            headBranch,
            original.getCreatedByUserId(),
            original.getAiProvider());

    FindingPatchSubmitter.SubmitResult result;
    try {
      result = findingSubmitter.submit(finding);
    } catch (Exception e) {
      log.warn(
          "CiFailureAutoFixService: FindingPatchSubmitter failed for {} #{}: {}",
          repoFullName,
          pr.getPrNumber(),
          e.getMessage());
      return AutoFixResult.skipped("submit failed: " + e.getMessage());
    }

    pr.setCiAutoFixAttempts(attempts + 1);
    pr.setUpdatedAt(Instant.now());
    prRepo.save(pr);

    log.info(
        "CiFailureAutoFixService: auto-fix run {} dispatched for {} #{} (attempt {}/{}),"
            + " branchOverride={} outcome={}",
        result.runId(),
        repoFullName,
        pr.getPrNumber(),
        attempts + 1,
        MAX_AUTO_FIX_ATTEMPTS,
        headBranch,
        result.outcome());
    return AutoFixResult.dispatched(result.runId());
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
