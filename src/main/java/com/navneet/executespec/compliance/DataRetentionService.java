package com.navneet.executespec.compliance;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Enforces per-tier data retention policy and GDPR right-to-erasure.
 *
 * <p>Retention windows (days):
 *
 * <ul>
 *   <li>FREE — 30 days after completion
 *   <li>PRO — 90 days after completion
 *   <li>TEAM — per tier_config (V144 reinstatement)
 *   <li>ENTERPRISE — 365 days after completion
 * </ul>
 *
 * <p>Retention sweep iterates over every row in {@code tier_config} via {@link
 * TierConfigRepository#findAll()} so future tiers are auto-included without code changes. The
 * scheduler runs daily. It first soft-deletes expired runs, then hard-deletes runs that have been
 * soft-deleted for ≥ 7 days (grace period for the GCS workspace deletion to complete).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataRetentionService {

  /** Default retention when tier config has no override. */
  private static final int DEFAULT_RETENTION_DAYS = 30;

  /** Grace period between soft-delete and hard-delete (workspace GCS cleanup time). */
  private static final int HARD_DELETE_GRACE_DAYS = 7;

  /**
   * PR-H (2026-05-13) — window between user soft-delete and hard-delete. Independent of the
   * GDPR/tier sweep grace period above (which gates on workspace cleanup). This one is the
   * user-facing "undo" window that PR-B-part-2 advertised; expired tombstones are reaped here.
   */
  private static final int USER_TOMBSTONE_DAYS = 30;

  /**
   * PR-H (2026-05-13) — archived runs are not user-visible but the row keeps consuming DB +
   * workspace storage. After this window we purge them. Doc in PipelineRun.archivedAt advertises 90
   * days; matched here.
   */
  private static final int ARCHIVED_RUN_PURGE_DAYS = 90;

  /**
   * RR-090 — how long erasure waits for a user's just-cancelled live runs to reach a terminal
   * status before recording a durable retry obligation. {@code cancel()} flips the run to {@code
   * CANCELLED} synchronously, so this is a safety margin for a run that raced in or a cancel that
   * threw, not the common path.
   */
  private static final Duration ERASURE_SETTLE_TIMEOUT = Duration.ofSeconds(10);

  /** Poll interval while waiting for cancelled runs to terminalise. */
  private static final long ERASURE_SETTLE_POLL_MILLIS = 500;

  private final PipelineRunRepository runRepository;
  private final ProjectRepository projectRepository;
  private final TierConfigRepository tierConfigRepository;
  private final MeterRegistry meterRegistry;
  private final RunLifecycleService runLifecycleService;
  private final PendingErasureObligationRepository obligationRepository;
  private final ErasureObligationStore obligationStore;

  @Scheduled(cron = "0 0 3 * * *") // 03:00 daily — low-traffic window
  public void runRetentionSweep() {
    log.info("[DataRetention] Starting retention sweep");
    softDeleteExpiredRuns();
    hardDeleteGracedRuns();
    // PR-H (2026-05-13) — PR-B-part-2 contract said user-soft-deleted projects + runs become
    // permanent after 30 days, and PR-A's PipelineRun.archivedAt doc advertises a 90-day
    // archived-run purge. Both ran every existing call site (controller, dashboard listing)
    // as if the sweeper existed; pre-PR-H there was no sweeper for either, so tombstones
    // accumulated forever. The methods below close the loop.
    hardDeleteTombstonedProjects();
    hardDeleteArchivedRuns();
    // RR-090: finish any GDPR erasure that could not confirm every live run terminal on the first
    // pass. The obligation is the durable "not done yet" marker; this is where it gets retried.
    retryPendingErasures();
    log.info("[DataRetention] Retention sweep complete");
  }

  /**
   * RR-090 — re-attempts every open erasure obligation. Each retry runs the full {@link
   * #eraseUserData} flow again: it re-cancels any still-live run (by now usually already terminal),
   * tombstones the newly terminal rows, and clears the obligation once no live run remains. An
   * obligation therefore cannot linger indefinitely — every daily sweep drives it toward
   * completion.
   */
  private void retryPendingErasures() {
    List<PendingErasureObligation> pending = obligationRepository.findAllByOrderByRequestedAtAsc();
    if (pending.isEmpty()) return;
    log.info("[DataRetention] Retrying {} pending GDPR erasure obligation(s)", pending.size());
    for (PendingErasureObligation obligation : pending) {
      try {
        eraseUserData(obligation.getUserId());
      } catch (Exception e) {
        log.warn(
            "[DataRetention] Pending GDPR erasure retry failed for user {} (attempt {}): {}",
            obligation.getUserId(),
            obligation.getAttempts(),
            e.getMessage());
      }
    }
  }

  /**
   * Settles then erases a user's runs (GDPR / Article-17 right-to-erasure).
   *
   * <p>RR-090 (2026-07-21) — this used to merely SKIP live runs and log "erasure INCOMPLETE",
   * leaving the obligation unmet with no follow-up. It now actually completes:
   *
   * <ol>
   *   <li><b>Cancel first.</b> Every one of the user's live (non-terminal) runs is settled through
   *       the real cancellation path, {@link RunLifecycleService#cancel(String)} — which atomically
   *       flips the run to {@code CANCELLED}, signals the plan gate, interrupts the pipeline future
   *       and closes opened PRs. This is not a status hack; it is the same operation the cancel
   *       endpoint uses.
   *   <li><b>Wait bounded.</b> {@link #awaitTerminal(String)} then confirms the runs have reached a
   *       terminal status, up to {@link #ERASURE_SETTLE_TIMEOUT}. Because {@code cancel()} is
   *       synchronous this is usually immediate.
   *   <li><b>Erase.</b> Terminal rows (now including the just-cancelled ones) are tombstoned via
   *       the terminal-status allowlist, so nothing live is tombstoned out from under an active
   *       worker.
   *   <li><b>Track the remainder.</b> If any run still cannot be confirmed terminal within the
   *       bound, a durable {@link PendingErasureObligation} is recorded so the daily sweep retries
   *       it ({@link #retryPendingErasures()}) — the incompleteness is a tracked, retryable state,
   *       not a dropped log line. When nothing remains live, any prior obligation is cleared.
   * </ol>
   *
   * @param userId user requesting erasure
   * @return outcome carrying the tombstoned count and any runs still live after the settle window
   */
  public ErasureResult eraseUserData(String userId) {
    // 1. Settle live runs through the real cancellation path before erasing anything.
    List<PipelineRun> live =
        runRepository.findNonTerminalByUserId(userId, PipelineRunRepository.TERMINAL_STATUSES);
    int cancelled = 0;
    for (PipelineRun run : live) {
      try {
        if (runLifecycleService.cancel(run.getRunId())) {
          cancelled++;
        }
      } catch (Exception e) {
        log.warn(
            "[DataRetention] GDPR erasure: cancel failed for run {} (user {}): {}",
            run.getRunId(),
            userId,
            e.getMessage());
      }
    }

    // 2. Wait bounded for the cancelled runs to terminalise.
    long stillLive = awaitTerminal(userId);

    // 3. Tombstone the terminal rows (includes the runs cancelled in step 1).
    int deleted = obligationStore.softDeleteTerminal(userId);

    // 4. Record or clear the durable obligation so incompleteness is retried, not dropped.
    if (stillLive > 0) {
      obligationStore.recordObligation(
          userId, stillLive, "runs did not terminalise within the settle window");
      log.warn(
          "[DataRetention] GDPR erasure INCOMPLETE for user {}: cancelled {} run(s), tombstoned {}"
              + " terminal run(s), but {} run(s) are still live. A durable erasure obligation was"
              + " recorded and will be retried by the daily sweep.",
          userId,
          cancelled,
          deleted,
          stillLive);
    } else {
      obligationStore.clearObligation(userId);
      log.info(
          "[DataRetention] GDPR erasure complete for user {}: cancelled {} live run(s), tombstoned"
              + " {} terminal run(s).",
          userId,
          cancelled,
          deleted);
    }

    Counter.builder("executespec.data.retention.deleted_runs")
        .tag("trigger", "GDPR")
        .register(meterRegistry)
        .increment(deleted);
    return new ErasureResult(deleted, stillLive);
  }

  /**
   * RR-090 — waits up to {@link #ERASURE_SETTLE_TIMEOUT} for the user's runs to reach a terminal
   * status, polling the non-terminal count. Returns the count still live when the wait ends (0 on
   * the happy path). This runs OUTSIDE any transaction so the poll sleeps never hold a DB
   * connection open.
   */
  private long awaitTerminal(String userId) {
    long remaining =
        runRepository.countNonTerminalByUserId(userId, PipelineRunRepository.TERMINAL_STATUSES);
    long deadline = System.nanoTime() + ERASURE_SETTLE_TIMEOUT.toNanos();
    while (remaining > 0 && System.nanoTime() < deadline) {
      try {
        Thread.sleep(ERASURE_SETTLE_POLL_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      remaining =
          runRepository.countNonTerminalByUserId(userId, PipelineRunRepository.TERMINAL_STATUSES);
    }
    return remaining;
  }

  /**
   * Outcome of a GDPR erasure pass.
   *
   * @param runsSoftDeleted terminal runs tombstoned by this pass
   * @param liveRunsSkipped non-terminal runs still live after the bounded settle window (RR-090);
   *     when non-zero a durable {@link PendingErasureObligation} is recorded for retry
   */
  public record ErasureResult(int runsSoftDeleted, long liveRunsSkipped) {}

  private void softDeleteExpiredRuns() {
    // Iterate over every configured tier — was a hardcoded ["FREE","PRO","ENTERPRISE"] list,
    // which silently missed TEAM (V144 reinstatement) and any future tiers added to tier_config.
    // Audit: 2026-05-12 line-by-line review item H #53.
    List<com.navneet.executespec.auth.entity.TierConfig> allTiers = tierConfigRepository.findAll();
    int total = 0;

    for (var tierConfig : allTiers) {
      String tier = tierConfig.getTier();
      int retentionDays =
          tierConfig.getRetentionDays() != null
              ? tierConfig.getRetentionDays()
              : DEFAULT_RETENTION_DAYS;

      Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
      List<PipelineRun> expired = runRepository.findExpiredRunsForTier(tier, cutoff);

      if (!expired.isEmpty()) {
        Instant now = Instant.now();
        expired.forEach(r -> r.setDeletedAt(now));
        runRepository.saveAll(expired);
        log.info(
            "[DataRetention] Soft-deleted {} {} runs (retention={}d)",
            expired.size(),
            tier,
            retentionDays);
        total += expired.size();
      }
    }

    if (total > 0) {
      Counter.builder("executespec.data.retention.deleted_runs")
          .tag("trigger", "SCHEDULED")
          .register(meterRegistry)
          .increment(total);
    }
  }

  private void hardDeleteGracedRuns() {
    Instant graceCutoff = Instant.now().minus(HARD_DELETE_GRACE_DAYS, ChronoUnit.DAYS);
    // RR-090: terminal-only. Hard deletion is irreversible; never fire it on a row whose status is
    // not positively proven finished, including one tombstoned by an older build of the erasure
    // path.
    List<PipelineRun> toHardDelete =
        runRepository.findSoftDeletedOlderThan(
            graceCutoff, PipelineRunRepository.TERMINAL_STATUSES);

    if (!toHardDelete.isEmpty()) {
      int deleted = 0;
      for (PipelineRun run : toHardDelete) {
        try {
          // V387: a quarantined legacy BUILD row can be under a database-enforced evidence hold.
          // Delete one row per repository transaction so that hold (or any other broken FK) cannot
          // roll back and starve unrelated eligible rows in the same retention batch.
          runRepository.delete(run);
          deleted++;
        } catch (Exception e) {
          log.warn(
              "[DataRetention] Failed to hard-delete graced run {}: {}",
              run.getRunId(),
              e.getMessage());
        }
      }
      log.info(
          "[DataRetention] Hard-deleted {} of {} runs past grace period",
          deleted,
          toHardDelete.size());
    }
  }

  /**
   * PR-H (2026-05-13) — purges projects soft-deleted more than {@link #USER_TOMBSTONE_DAYS} ago.
   * The 30-day window is the user-facing undo period advertised by the controller {@code DELETE
   * /api/projects/{id}} endpoint (PR-B-part-2). Runs cascade through Postgres foreign keys —
   * pipeline_run + execution_run + project_repo_link rows for this project go with it. Other org
   * members' shared workflows aren't affected because we only touch rows the user themselves had
   * tombstoned.
   *
   * <p>Failures on individual rows are isolated — we delete in a loop with per-row try/catch so one
   * broken FK or trigger doesn't poison the whole sweep.
   */
  private void hardDeleteTombstonedProjects() {
    Instant cutoff = Instant.now().minus(USER_TOMBSTONE_DAYS, ChronoUnit.DAYS);
    List<Project> expired = projectRepository.findByDeletedAtBefore(cutoff);
    if (expired.isEmpty()) return;

    int deleted = 0;
    for (Project p : expired) {
      try {
        projectRepository.delete(p);
        deleted++;
      } catch (Exception e) {
        log.warn(
            "[DataRetention] Failed to hard-delete tombstoned project {}: {}",
            p.getId(),
            e.getMessage());
      }
    }
    log.info(
        "[DataRetention] Hard-deleted {} of {} tombstoned projects past {}d window",
        deleted,
        expired.size(),
        USER_TOMBSTONE_DAYS);
    Counter.builder("executespec.data.retention.deleted_projects")
        .tag("trigger", "SCHEDULED")
        .register(meterRegistry)
        .increment(deleted);
  }

  /**
   * PR-H (2026-05-13) — purges runs the user archived more than {@link #ARCHIVED_RUN_PURGE_DAYS}
   * ago. Mirrors the {@code PipelineRun.archivedAt} javadoc contract ("archived runs are
   * permanently deleted after 90 days"). Distinct from the tier-retention path above which sweeps
   * based on {@code completedAt} for live (non-archived) runs.
   *
   * <p>Same per-row try/catch isolation as the project sweep above.
   */
  private void hardDeleteArchivedRuns() {
    Instant cutoff = Instant.now().minus(ARCHIVED_RUN_PURGE_DAYS, ChronoUnit.DAYS);
    List<PipelineRun> expired = runRepository.findArchivedRunsOlderThan(cutoff);
    if (expired.isEmpty()) return;

    int deleted = 0;
    for (PipelineRun r : expired) {
      try {
        runRepository.delete(r);
        deleted++;
      } catch (Exception e) {
        log.warn(
            "[DataRetention] Failed to hard-delete archived run {}: {}",
            r.getRunId(),
            e.getMessage());
      }
    }
    log.info(
        "[DataRetention] Hard-deleted {} of {} archived runs past {}d window",
        deleted,
        expired.size(),
        ARCHIVED_RUN_PURGE_DAYS);
    Counter.builder("executespec.data.retention.deleted_runs")
        .tag("trigger", "ARCHIVED_PURGE")
        .register(meterRegistry)
        .increment(deleted);
  }
}
