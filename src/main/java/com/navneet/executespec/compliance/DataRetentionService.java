package com.navneet.executespec.compliance;

import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Enforces per-tier data retention policy and GDPR right-to-erasure.
 *
 * <p>Retention windows (days):
 * <ul>
 *   <li>FREE — 30 days after completion</li>
 *   <li>PRO — 90 days after completion</li>
 *   <li>ENTERPRISE — 365 days after completion</li>
 * </ul>
 *
 * <p>The scheduler runs daily. It first soft-deletes expired runs, then
 * hard-deletes runs that have been soft-deleted for ≥ 7 days (grace period
 * for the GCS workspace deletion to complete).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataRetentionService {

    /** Default retention when tier config has no override. */
    private static final int DEFAULT_RETENTION_DAYS = 30;
    /** Grace period between soft-delete and hard-delete (workspace GCS cleanup time). */
    private static final int HARD_DELETE_GRACE_DAYS = 7;

    private final PipelineRunRepository runRepository;
    private final TierConfigRepository tierConfigRepository;
    private final MeterRegistry meterRegistry;

    // ── Scheduled sweep ───────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * *")   // 03:00 daily — low-traffic window
    public void runRetentionSweep() {
        log.info("[DataRetention] Starting retention sweep");
        softDeleteExpiredRuns();
        hardDeleteGracedRuns();
        log.info("[DataRetention] Retention sweep complete");
    }

    // ── GDPR right-to-erasure ─────────────────────────────────────────────────

    /**
     * Soft-deletes all non-active runs belonging to the user.
     * Active runs (PENDING/PLANNING/IN_PROGRESS) are skipped — they cannot be
     * deleted while the pipeline is live.
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
                .register(meterRegistry).increment(deleted);
        return deleted;
    }

    // ── Internal sweep helpers ────────────────────────────────────────────────

    private void softDeleteExpiredRuns() {
        List<String> tiers = List.of("FREE", "PRO", "ENTERPRISE");
        int total = 0;

        for (String tier : tiers) {
            int retentionDays = tierConfigRepository.findById(tier)
                    .map(tc -> tc.getRetentionDays() != null ? tc.getRetentionDays() : DEFAULT_RETENTION_DAYS)
                    .orElse(DEFAULT_RETENTION_DAYS);

            Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
            List<PipelineRun> expired = runRepository.findExpiredRunsForTier(tier, cutoff);

            if (!expired.isEmpty()) {
                Instant now = Instant.now();
                expired.forEach(r -> r.setDeletedAt(now));
                runRepository.saveAll(expired);
                log.info("[DataRetention] Soft-deleted {} {} runs (retention={}d)", expired.size(), tier, retentionDays);
                total += expired.size();
            }
        }

        if (total > 0) {
            Counter.builder("executespec.data.retention.deleted_runs")
                    .tag("trigger", "SCHEDULED")
                    .register(meterRegistry).increment(total);
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
}
