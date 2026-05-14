package com.navneet.executespec.compliance;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  private final PipelineRunRepository runRepository;
  private final ProjectRepository projectRepository;
  private final TierConfigRepository tierConfigRepository;
  private final MeterRegistry meterRegistry;

  // ── Scheduled sweep ───────────────────────────────────────────────────────

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
    log.info("[DataRetention] Retention sweep complete");
  }

  // ── GDPR right-to-erasure ─────────────────────────────────────────────────

  /**
   * Soft-deletes all non-active runs belonging to the user. Active runs
   * (PENDING/PLANNING/IN_PROGRESS) are skipped — they cannot be deleted while the pipeline is live.
   *
   * @param userId user requesting erasure
   * @return number of runs soft-deleted
   */
  @Transactional
  public int eraseUserData(String userId) {
    int deleted = runRepository.softDeleteByUserId(userId, Instant.now());
    log.info("[DataRetention] GDPR erasure: soft-deleted {} runs for user {}", deleted, userId);
    Counter.builder("executespec.data.retention.deleted_runs")
        .tag("trigger", "GDPR")
        .register(meterRegistry)
        .increment(deleted);
    return deleted;
  }

  // ── Internal sweep helpers ────────────────────────────────────────────────

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
    List<PipelineRun> toHardDelete = runRepository.findSoftDeletedOlderThan(graceCutoff);

    if (!toHardDelete.isEmpty()) {
      runRepository.deleteAll(toHardDelete);
      log.info("[DataRetention] Hard-deleted {} runs past grace period", toHardDelete.size());
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
