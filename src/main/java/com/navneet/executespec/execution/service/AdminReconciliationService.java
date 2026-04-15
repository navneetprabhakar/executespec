package com.navneet.executespec.execution.service;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off operational cleanup for orphaned {@link ExecutionRun} rows that were
 * left in a non-terminal status pre-v2.5.1 (before the cancel path was made
 * authoritative). If an ExecutionRun hasn't been touched in {@value #DEFAULT_STALE_MINUTES}
 * minutes and the pipeline thread is gone (node restart, crash, thread interrupt
 * before the cancel path closed the row), we mark it FAILED with a traceable
 * reason instead of leaving it stuck on the dashboard.
 *
 * Triggered via {@code POST /api/admin/executions/reconcile} (SUPER_ADMIN).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReconciliationService {

    /** ExecutionRuns untouched this long are considered orphaned. */
    static final long DEFAULT_STALE_MINUTES = 30;

    static final String RECONCILED_REASON = "reconciled_orphan_v251";

    private final ExecutionRunRepository runRepo;

    @Transactional
    public ReconcileReport reconcileOrphans() {
        return reconcileOrphans(DEFAULT_STALE_MINUTES);
    }

    @Transactional
    public ReconcileReport reconcileOrphans(long staleMinutes) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(staleMinutes));
        List<ExecutionRun> stale = runRepo.findStaleNonTerminal(cutoff);

        List<String> reconciledIds = new ArrayList<>(stale.size());
        for (ExecutionRun er : stale) {
            String previousStatus = er.getStatus();
            er.setStatus("FAILED");
            runRepo.save(er);
            reconciledIds.add(er.getId());
            log.warn("Reconciled orphaned ExecutionRun id={} previousStatus={} -> FAILED reason={}",
                    er.getId(), previousStatus, RECONCILED_REASON);
        }

        return new ReconcileReport(stale.size(), reconciledIds, staleMinutes, cutoff);
    }

    public record ReconcileReport(
            int reconciled,
            List<String> reconciledIds,
            long staleMinutes,
            Instant cutoff) {}
}
