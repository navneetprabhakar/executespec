package com.navneet.executespec.project.pr;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.finding.Finding;
import com.navneet.executespec.finding.FindingPatchSubmitter;
import com.navneet.executespec.finding.FindingType;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

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
 *   <li>Commits a unique V359 delivery claim and reserves one PR attempt before any asynchronous
 *       run submission. Concurrent redeliveries observe that claim and stop.
 *   <li>Builds a seeded user request from the failure tail plus the same-branch instruction.
 *   <li>Submits through {@link FindingPatchSubmitter} with {@code branchOverride=headBranch}; the
 *       run's initial row carries the same finding key, and terminal PATCH publication pushes
 *       incrementally onto the existing PR branch.
 *   <li>Fenced completion binds the canonical run to the delivery claim. A caught submission
 *       failure marks the claim FAILED and releases its reserved attempt for a later redelivery.
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
  private final CiFailureRunRepository pipelineRunRepo;
  private final CiFailureDeliveryClaimStore deliveryClaimStore;
  private final FindingPatchSubmitter findingSubmitter;
  private final AppUserRepository userRepository;
  private final FeatureFlagService featureFlagService;

  /**
   * V183 — feature flag gating the auto-fire webhook path. Default OFF until the SWE-bench matrix
   * data confirms patch-mode resolved% is high enough to trust the bot. Operators flip to true via
   * {@code PUT /api/feature-flags/ci.autofix.enabled}. The other phases (PR Review autofix, Tech
   * Debt, Code Review) are user-clicked so they don't need the gate; only the unattended webhook
   * path does.
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
  public AutoFixResult onCheckRunFailed(
      String repoFullName, String headBranch, String checkRunSummary, String checkRunUrl) {
    return onCheckRunFailed(
        repoFullName,
        headBranch,
        checkRunSummary,
        checkRunUrl,
        fallbackCheckIdentity(repoFullName, headBranch, checkRunSummary, checkRunUrl),
        null);
  }

  /**
   * Idempotent check-run entry point. GitHub may redeliver the same signed webhook after the first
   * PATCH run has already become terminal, so active-run dedupe alone is insufficient. The
   * check-run id plus head SHA names one immutable CI observation.
   */
  public AutoFixResult onCheckRunFailed(
      String repoFullName,
      String headBranch,
      String checkRunSummary,
      String checkRunUrl,
      String checkRunId,
      String headSha) {
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

    String findingId = ciFindingId(pr, checkRunId, headSha);
    String findingKey = FindingType.CI_FAILURE.name() + ":" + findingId;
    CiFailureDeliveryClaimStore.Claim deliveryClaim =
        deliveryClaimStore.claim(
            findingKey, pr.getId(), checkRunId, headSha, MAX_AUTO_FIX_ATTEMPTS);
    if (!deliveryClaim.acquired()) {
      if (deliveryClaim.outcome() == CiFailureDeliveryClaimStore.Outcome.ATTEMPT_LIMIT) {
        return AutoFixResult.skipped("max auto-fix attempts reached");
      }
      if (deliveryClaim.outcome() == CiFailureDeliveryClaimStore.Outcome.PR_NOT_OPEN) {
        return AutoFixResult.skipped("PR is no longer open");
      }
      String existingRun = deliveryClaim.runId() == null ? "" : " by run " + deliveryClaim.runId();
      return AutoFixResult.skipped(
          deliveryClaim.outcome() == CiFailureDeliveryClaimStore.Outcome.DISPATCHED
              ? "duplicate check_run delivery already handled" + existingRun
              : "duplicate check_run delivery is already being handled");
    }

    // V359 starts with an empty claim table. Reconcile a pre-migration delivery from the durable
    // run marker before submitting anything, then bind that historical run into the new claim.
    Optional<PipelineRun> priorDelivery =
        pipelineRunRepo.findFirstByTriggeredByFindingOrderByStartedAtDesc(findingKey);
    if (priorDelivery.isPresent()) {
      PipelineRun prior = priorDelivery.orElseThrow();
      if (!deliveryClaimStore.complete(deliveryClaim, prior.getRunId())) {
        throw new IllegalStateException("CI delivery claim lost before historical reconciliation");
      }
      return AutoFixResult.skipped(
          "duplicate check_run delivery already handled by run " + prior.getRunId());
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
            // Stable for one GitHub check observation. A new head SHA/check-run id gets a fresh
            // attempt; duplicate delivery of the same event can never consume another attempt.
            findingId,
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
      if (!deliveryClaimStore.fail(deliveryClaim, e.getClass().getSimpleName())) {
        throw new IllegalStateException("CI delivery claim lost while recording submit failure", e);
      }
      log.warn(
          "CiFailureAutoFixService: FindingPatchSubmitter failed for {} #{}: {}",
          repoFullName,
          pr.getPrNumber(),
          e.getMessage());
      return AutoFixResult.skipped("submit failed: " + e.getMessage());
    }

    if (result.outcome() == FindingPatchSubmitter.SubmitResult.Outcome.REUSED_ACTIVE) {
      if (!deliveryClaimStore.complete(deliveryClaim, result.runId())) {
        throw new IllegalStateException("CI delivery claim lost while binding active PATCH run");
      }
      return AutoFixResult.skipped(
          "duplicate check_run delivery already active as run " + result.runId());
    }

    if (!deliveryClaimStore.complete(deliveryClaim, result.runId())) {
      throw new IllegalStateException("CI delivery claim lost before PATCH run publication");
    }
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

  private static String ciFindingId(PullRequestRecord pr, String checkRunId, String headSha) {
    String check =
        checkRunId == null || checkRunId.isBlank()
            ? "unknown"
            : checkRunId.replaceAll("[^A-Za-z0-9._-]", "_");
    String sha =
        headSha == null || headSha.isBlank()
            ? "unknown"
            : headSha
                .replaceAll("[^A-Fa-f0-9]", "")
                .substring(0, Math.min(16, headSha.replaceAll("[^A-Fa-f0-9]", "").length()));
    if (sha.isBlank()) sha = "unknown";
    return pr.getId() + "|check-" + check + "|sha-" + sha;
  }

  private static String fallbackCheckIdentity(
      String repoFullName, String headBranch, String summary, String url) {
    try {
      String material =
          String.join(
              "\u001f",
              repoFullName == null ? "" : repoFullName,
              headBranch == null ? "" : headBranch,
              summary == null ? "" : summary,
              url == null ? "" : url);
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(material.getBytes(StandardCharsets.UTF_8)))
          .substring(0, 32);
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
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
