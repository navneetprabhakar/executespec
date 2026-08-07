package com.navneet.executespec.project.pr;

import java.util.List;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Pilot R5 — list PRs opened by a single run. Auth is inherited from the global security config
 * (authenticated users only); ownership checks are intentionally loose here because the record only
 * exposes the PR URL which GitHub will re-authorise on click.
 */
@RestController
@RequiredArgsConstructor
public class PullRequestController {

  private final PullRequestRecordService service;
  private final GitHubRepoService gitHub;
  private final PrRefreshService refreshService;
  private final RunAccessGuard runAccessGuard;
  private final ProjectAccessGuard projectAccessGuard;

  /**
   * RR-519 (2026-07-22) — hard server cap on the PR-snapshot diff preview. The endpoint accepts a
   * caller {@code maxChars}, which previously flowed straight through to the substring bound with
   * no ceiling — a caller could request an arbitrarily large slice of a huge PR diff and force the
   * whole thing to be buffered and returned. The snapshot is a preview surface, so we clamp the
   * caller value into {@code [0, SNAPSHOT_MAX_DIFF_CHARS]}. (The upstream GitHub body is still read
   * fully in GitHubRepoService; streaming/cancelling that read is a separate, larger change.)
   */
  static final int SNAPSHOT_MAX_DIFF_CHARS = 20_000;

  /**
   * Stable value for {@link PrSummary#ciStatusReason} when {@code ciStatus} is UNKNOWN because the
   * backing repo record proves no PR webhook is installed. The frontend pairs it with {@code
   * repoId} to offer {@code POST /api/repos/{repoId}/webhook} as the fix.
   */
  static final String CI_STATUS_REASON_WEBHOOK_NOT_INSTALLED = "WEBHOOK_NOT_INSTALLED";

  /**
   * Additive fields (2026-08-05, webhook-onboarding surfacing): {@code ciStatusReason} explains an
   * UNKNOWN {@code ciStatus} — value {@code WEBHOOK_NOT_INSTALLED} only when the repo record shows
   * the webhook is provably not installed; null otherwise (including when install state is
   * unknown). {@code repoId} is the ProjectRepo UUID so the client can call {@code POST
   * /api/repos/{repoId}/webhook}; null when the record carries no repo linkage.
   */
  public record PrSummary(
      String repoFullName,
      Integer prNumber,
      String prUrl,
      String status,
      String headBranch,
      String baseBranch,
      String createdAt,
      String mergedAt,
      String ciStatus,
      Integer ciFailingCount,
      String ciCheckedAt,
      String ciStatusReason,
      String repoId) {
    static PrSummary from(PullRequestRecord r) {
      return from(r, null);
    }

    static PrSummary from(PullRequestRecord r, Boolean webhookInstalled) {
      String ciStatus = r.getCiStatus() != null ? r.getCiStatus().name() : "UNKNOWN";
      String ciStatusReason =
          "UNKNOWN".equals(ciStatus) && Boolean.FALSE.equals(webhookInstalled)
              ? CI_STATUS_REASON_WEBHOOK_NOT_INSTALLED
              : null;
      return new PrSummary(
          r.getRepoFullName(),
          r.getPrNumber(),
          r.getPrUrl(),
          r.getStatus().name(),
          r.getHeadBranch(),
          r.getBaseBranch(),
          r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
          r.getMergedAt() != null ? r.getMergedAt().toString() : null,
          ciStatus,
          r.getCiFailingCount() != null ? r.getCiFailingCount() : 0,
          r.getCiCheckedAt() != null ? r.getCiCheckedAt().toString() : null,
          ciStatusReason,
          r.getRepoId() != null ? r.getRepoId().toString() : null);
    }
  }

  @GetMapping("/api/runs/{runId}/prs")
  public ResponseEntity<List<PrSummary>> listPrsForRun(
      @PathVariable String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    // GA-readiness P4.1-followup — multi-tenant access guard. The doc comment
    // above the class said "ownership checks are intentionally loose because
    // the record only exposes the PR URL", but probing which runs exist on
    // which repos is itself a useful signal for an attacker. Tighten to a
    // proper org-scoped check.
    runAccessGuard.requireRunAccess(runId, userDetails);
    // 2026-08-05 — resolve webhook-install state per distinct repoId (verified against the durable
    // project_repo.webhook_installed column) so an UNKNOWN ciStatus can carry an actionable
    // WEBHOOK_NOT_INSTALLED reason instead of an unexplained blank.
    java.util.Map<java.util.UUID, Boolean> webhookByRepo = new java.util.HashMap<>();
    List<PrSummary> out =
        service.listForRun(runId).stream()
            .map(
                r ->
                    PrSummary.from(
                        r,
                        r.getRepoId() == null
                            ? null
                            : webhookByRepo.computeIfAbsent(
                                r.getRepoId(), service::webhookInstalled)))
            .toList();
    return ResponseEntity.ok(out);
  }

  /**
   * Inline PR snapshot (title + body + diff preview) for the project dashboard to render expanded
   * PR cards without making the user leave for GitHub. Uses the caller's stored GitHub PAT.
   */
  /**
   * Refresh every non-terminal PR opened by this project against GitHub. Used by the dashboard
   * "Refresh" button when webhooks aren't wired up. Triggers the post-merge cascade (codemap regen
   * + notification) for any OPEN → MERGED transition.
   *
   * <p>2026-05-13 — added optional {@code ?forceAll=true} override. Default is open-only fast path
   * (skips merged + closed records entirely, including the DB fetch). Force-all is only needed for
   * backfill after a long outage or webhook-loss recovery.
   */
  @PostMapping("/api/projects/{projectId}/prs/refresh")
  public ResponseEntity<PrRefreshService.RefreshResult> refreshProjectPrs(
      @PathVariable String projectId,
      @org.springframework.web.bind.annotation.RequestParam(
              required = false,
              defaultValue = "false")
          boolean forceAll,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    // GA-readiness P4.1-followup — assertCanWrite: the refresh is a write (it
    // polls GitHub then flips PR merge/CI status + runs the post-merge cascade),
    // so it requires write access, not just read (RL-2026-05-040 hardened all
    // mutating POST handlers to assertCanWrite). Closes the legacy gap where any
    // authenticated caller could trigger a refresh on any project's PRs.
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    // 2026-05-02 — thread the caller's PAT so private-repo snapshots succeed. Without
    // this, getPullRequestSnapshot returned null on private repos and the refresh
    // silently no-op'd; user clicked Refresh + saw merged PRs still showing OPEN.
    String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
    return ResponseEntity.ok(refreshService.refreshProject(projectId, pat, forceAll));
  }

  @GetMapping("/api/prs/snapshot")
  public ResponseEntity<GitHubRepoService.PullRequestSnapshot> snapshot(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam("repo") String repoFullName,
      @RequestParam("number") int prNumber,
      @RequestParam(value = "maxChars", defaultValue = "4000") int maxChars) {
    String pat = userDetails != null ? userDetails.getUser().getGithubPat() : null;
    // RR-519 — clamp the caller-supplied preview size to a bounded server constant.
    int boundedMaxChars = Math.max(0, Math.min(maxChars, SNAPSHOT_MAX_DIFF_CHARS));
    var snap = gitHub.getPullRequestSnapshot(repoFullName, prNumber, pat, boundedMaxChars);
    if (snap == null) return ResponseEntity.noContent().build();
    return ResponseEntity.ok(snap);
  }
}
