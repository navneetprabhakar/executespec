package com.navneet.executespec.execution.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * One-off operational cleanup for orphaned {@link ExecutionRun} rows that were left in a
 * non-terminal status pre-v2.5.1 (before the cancel path was made authoritative). If an
 * ExecutionRun hasn't been touched in {@value #DEFAULT_STALE_MINUTES} minutes and the pipeline
 * thread is gone (node restart, crash, thread interrupt before the cancel path closed the row), we
 * mark it FAILED with a traceable reason instead of leaving it stuck on the dashboard.
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@code POST /api/admin/executions/reconcile} — manual SUPER_ADMIN trigger with an optional
 *       {@code staleMinutes} override.
 *   <li>{@link #scheduledReconcile()} — runs every 15 minutes with a {@code @SchedulerLock} so
 *       multi-replica deployments fire it once. Closes the gap surfaced in the v2.5.10 evaluation
 *       where five ExecutionRuns sat stuck for 12-24 hours until somebody hit the admin endpoint by
 *       hand.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminReconciliationService {

  /** ExecutionRuns untouched this long are considered orphaned. */
  static final long DEFAULT_STALE_MINUTES = 30;

  /** Cutoff used by the scheduled job — generous to avoid racing healthy long runs. */
  static final long SCHEDULED_STALE_MINUTES = 60;

  static final String RECONCILED_REASON = "reconciled_orphan_v251";

  private final ExecutionRunRepository runRepo;

  /**
   * Periodic sweep of orphaned ExecutionRuns. Fires every 15 minutes with a {@link SchedulerLock}
   * so only one replica reconciles per window. The {@value #SCHEDULED_STALE_MINUTES}-minute cutoff
   * is deliberately wider than the manual default (30 min) to avoid racing a long-running, healthy
   * Launch.
   */
  @Scheduled(fixedDelay = 15 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
  @SchedulerLock(name = "executionRunReconcile", lockAtLeastFor = "PT2M", lockAtMostFor = "PT10M")
  public void scheduledReconcile() {
    try {
      ReconcileReport report = reconcileOrphans(SCHEDULED_STALE_MINUTES);
      if (report.reconciled() > 0) {
        log.warn(
            "Scheduled reconcile swept {} orphaned ExecutionRuns (cutoff={}m)",
            report.reconciled(),
            report.staleMinutes());
      } else {
        log.debug("Scheduled reconcile: no orphans (cutoff={}m)", report.staleMinutes());
      }
    } catch (Exception e) {
      log.error("Scheduled reconcile failed", e);
    }
  }

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
      log.warn(
          "Reconciled orphaned ExecutionRun id={} previousStatus={} -> FAILED reason={}",
          er.getId(),
          previousStatus,
          RECONCILED_REASON);
    }

    return new ReconcileReport(stale.size(), reconciledIds, staleMinutes, cutoff);
  }

  public record ReconcileReport(
      int reconciled, List<String> reconciledIds, long staleMinutes, Instant cutoff) {}
}
