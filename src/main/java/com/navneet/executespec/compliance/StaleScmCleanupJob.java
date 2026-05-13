package com.navneet.executespec.compliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * PR-H (2026-05-14) — daily sweeper for SCM artefacts left behind on customer repos by cancelled /
 * failed / abandoned runs:
 *
 * <ol>
 *   <li><b>Stale draft / open PRs</b> from runs that terminated as {@code CANCELLED} or {@code
 *       FAILED} more than {@link #STALE_PR_DAYS} days ago. The PR was opened by ScmPushService at
 *       run-end then abandoned when the run terminated badly; nothing's ever going to merge it. We
 *       {@code close} (not merge) so the customer's PR queue stays clean.
 *   <li><b>Stale {@code executespec/*} branches</b> from terminated runs older than {@link
 *       #STALE_BRANCH_DAYS} days, regardless of how the run terminated. The PR is closed (by the
 *       sweep above OR by the user merging) but the branch itself often lingers and shows up in
 *       autocomplete / branch lists. Deletes via the GitHub Git Refs API.
 * </ol>
 *
 * <p>Both sub-sweeps use the run-submitter's PAT (stored on {@code app_user.github_pat}) so the
 * close / delete acts as that user, not as a service account. If the user no longer has a PAT we
 * skip and counter the failure — better than 401-spamming GitHub.
 *
 * <p>Runs daily at 03:45 — 15 minutes after the workspace sweep so we operate on the same
 * post-row-sweep snapshot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StaleScmCleanupJob {

  /** Days a stale PR sits before this job closes it. */
  static final int STALE_PR_DAYS = 14;

  /**
   * Days a stale {@code executespec/*} branch sits before this job deletes it. Longer than the PR
   * window so a customer can still merge a not-yet-stale PR after this job closes the dead ones —
   * branch deletion is the last step.
   */
  static final int STALE_BRANCH_DAYS = 30;

  /** Cap on records processed per pass to keep job latency bounded. */
  static final int MAX_PER_PASS = 1_000;

  private final PullRequestRecordRepository prRepo;
  private final PipelineRunRepository runRepo;
  private final AppUserRepository userRepo;
  private final GitHubRepoService gitHub;
  private final MeterRegistry meterRegistry;

  /** Runs daily at 03:45 local. */
  @Scheduled(cron = "0 45 3 * * *")
  @SchedulerLock(name = "staleScmCleanup", lockAtLeastFor = "PT1M", lockAtMostFor = "PT1H")
  public void cleanup() {
    int prClosed = closeStalePrs();
    int branchDeleted = deleteStaleBranches();
    if (prClosed + branchDeleted > 0) {
      log.info("[StaleScmCleanup] pr_closed={} branch_deleted={}", prClosed, branchDeleted);
    }
  }

  /**
   * Sub-sweep 1: close PRs from CANCELLED/FAILED runs older than {@link #STALE_PR_DAYS}.
   * Package-private for test access.
   */
  int closeStalePrs() {
    Instant cutoff = Instant.now().minus(Duration.ofDays(STALE_PR_DAYS));
    // We don't have a direct query for "PRs whose backing run failed > Nd ago" so we walk OPEN
    // PRs older than cutoff and check the backing run. Cheap because OPEN-PR count is small
    // (terminated successful PRs are MERGED, terminal failed PRs are usually closed quickly).
    List<PullRequestRecord> candidates =
        prRepo.findAll().stream()
            .filter(pr -> pr.getStatus() == PullRequestRecord.Status.OPEN)
            .filter(pr -> pr.getCreatedAt() != null && pr.getCreatedAt().isBefore(cutoff))
            .limit(MAX_PER_PASS)
            .toList();

    int closed = 0;
    int skippedActive = 0;
    for (PullRequestRecord pr : candidates) {
      // Need a run to dereference (the PAT belongs to the run's creator)
      PipelineRun run = pr.getRunId() != null ? runRepo.findById(pr.getRunId()).orElse(null) : null;
      if (run == null) continue; // orphan PR — leave alone
      if (!isFailedTerminal(run.getStatus())) {
        skippedActive++;
        continue;
      }
      Optional<AppUser> user =
          run.getCreatedByUserId() != null
              ? userRepo.findById(run.getCreatedByUserId())
              : Optional.empty();
      String pat = user.map(AppUser::getGithubPat).orElse(null);
      if (pat == null || pat.isBlank()) {
        // No PAT to act with — skip + count so operators can see the class of failure.
        Counter.builder("executespec.scm.cleanup.skipped")
            .tag("reason", "no_pat")
            .tag("kind", "pr")
            .register(meterRegistry)
            .increment();
        continue;
      }
      try {
        if (gitHub.closePullRequest(pr.getRepoFullName(), pr.getPrNumber(), pat)) {
          pr.setStatus(PullRequestRecord.Status.CLOSED);
          pr.setClosedAt(Instant.now());
          prRepo.save(pr);
          closed++;
        }
      } catch (Exception e) {
        log.warn(
            "[StaleScmCleanup] close PR {}#{} failed: {}",
            pr.getRepoFullName(),
            pr.getPrNumber(),
            e.getMessage());
      }
    }
    if (closed > 0) {
      Counter.builder("executespec.scm.cleanup.closed")
          .tag("kind", "pr")
          .register(meterRegistry)
          .increment(closed);
    }
    log.debug(
        "[StaleScmCleanup] closeStalePrs scanned={} closed={} skipped_active={}",
        candidates.size(),
        closed,
        skippedActive);
    return closed;
  }

  /**
   * Sub-sweep 2: delete remote {@code executespec/*} branches whose backing PR is closed/merged and
   * whose row is older than {@link #STALE_BRANCH_DAYS}. Package-private for test access.
   */
  int deleteStaleBranches() {
    Instant cutoff = Instant.now().minus(Duration.ofDays(STALE_BRANCH_DAYS));
    List<PullRequestRecord> candidates =
        prRepo.findAll().stream()
            // Only consider terminal PRs — never touch a branch with an OPEN PR pointing at it
            .filter(pr -> pr.getStatus() != PullRequestRecord.Status.OPEN)
            .filter(
                pr -> pr.getHeadBranch() != null && pr.getHeadBranch().startsWith("executespec/"))
            .filter(pr -> pr.getCreatedAt() != null && pr.getCreatedAt().isBefore(cutoff))
            .limit(MAX_PER_PASS)
            .toList();

    int deleted = 0;
    for (PullRequestRecord pr : candidates) {
      PipelineRun run = pr.getRunId() != null ? runRepo.findById(pr.getRunId()).orElse(null) : null;
      String pat = null;
      if (run != null && run.getCreatedByUserId() != null) {
        pat = userRepo.findById(run.getCreatedByUserId()).map(AppUser::getGithubPat).orElse(null);
      }
      if (pat == null || pat.isBlank()) {
        Counter.builder("executespec.scm.cleanup.skipped")
            .tag("reason", "no_pat")
            .tag("kind", "branch")
            .register(meterRegistry)
            .increment();
        continue;
      }
      try {
        if (gitHub.deleteBranch(pr.getRepoFullName(), pr.getHeadBranch(), pat)) {
          deleted++;
        }
      } catch (Exception e) {
        log.warn(
            "[StaleScmCleanup] delete branch {} on {} failed: {}",
            pr.getHeadBranch(),
            pr.getRepoFullName(),
            e.getMessage());
      }
    }
    if (deleted > 0) {
      Counter.builder("executespec.scm.cleanup.deleted")
          .tag("kind", "branch")
          .register(meterRegistry)
          .increment(deleted);
    }
    log.debug(
        "[StaleScmCleanup] deleteStaleBranches scanned={} deleted={}", candidates.size(), deleted);
    return deleted;
  }

  private static boolean isFailedTerminal(String status) {
    return "CANCELLED".equals(status) || "FAILED".equals(status) || "ABORTED".equals(status);
  }
}
