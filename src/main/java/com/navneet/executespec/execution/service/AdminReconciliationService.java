package com.navneet.executespec.execution.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
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

  /**
   * Cutoff used by the boot-time sweep. Tight (1 min) because at JVM startup there are NO running
   * orchestrator threads — anything still in a non-terminal status MUST have been interrupted by
   * the previous JVM's death. The 1-min floor is just a safety fence for very-recent rows that
   * might still be in-flight from a different replica during a rolling deploy.
   */
  static final long STARTUP_STALE_MINUTES = 1;

  static final String RECONCILED_REASON = "reconciled_orphan_v251";

  private final ExecutionRunRepository runRepo;

  /**
   * Boot-time sweep — fires once on {@link ApplicationReadyEvent}. The previous scheduled-only
   * model meant a Launch interrupted by a deploy could sit "stuck" on the dashboard for up to 5–20
   * minutes (5 min initialDelay + up to 15 min until the next sweep) before getting flipped to
   * FAILED. That window is the worst single piece of UX in the deploy-during-launch flow.
   *
   * <p>This listener tightens it to <30s: as soon as Spring fires {@link ApplicationReadyEvent} the
   * new JVM sweeps orphaned non-terminal ExecutionRuns with a {@value
   * #STARTUP_STALE_MINUTES}-minute cutoff. {@code @Async} so the boot path isn't blocked; an
   * exception here must never prevent the app from starting.
   *
   * <p>Pairs with {@link com.navneet.executespec.recovery.StartupRunResumer} which already does the
   * same for V2 PipelineRuns.
   */
  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      // Coordinate with ExecutionOrchestrator.onApplicationReady — that listener resumes
      // EXECUTING ExecutionRuns by re-dispatching their solution layers. We must NOT fail
      // EXECUTING rows here or we'd undo that resume. So this boot sweep only fail-cleans the
      // non-resumable upstream phases (SETUP / ANALYSING / ARCH_REVIEW / PLANNING / PAUSED) —
      // those have no in-flight V2 sub-runs to recover, and re-dispatching them needs user
      // input (they're pre-execution gates).
      ReconcileReport report = reconcileOrphansExcludingExecuting(STARTUP_STALE_MINUTES);
      if (report.reconciled() > 0) {
        log.warn(
            "Boot-time reconcile swept {} orphaned ExecutionRun(s) in non-resumable phases"
                + " (cutoff={}m); EXECUTING runs are handled by ExecutionOrchestrator's"
                + " resume listener",
            report.reconciled(),
            report.staleMinutes());
      } else {
        log.info("Boot-time reconcile: no orphans (EXECUTING runs handled separately)");
      }
    } catch (Exception e) {
      // Never let recovery failures block startup.
      log.error("Boot-time reconcile failed", e);
    }
  }

  /**
   * Variant of {@link #reconcileOrphans(long)} that excludes {@code EXECUTING} rows so the
   * ExecutionOrchestrator boot-resume can re-dispatch them. Used only by {@link
   * #onApplicationReady}; the {@code @Scheduled} sweep keeps the broader cutoff because by the time
   * it fires, any EXECUTING row that ExecutionOrchestrator was supposed to resume has either
   * advanced to a terminal status OR genuinely got stuck and deserves to be failed.
   */
  @Transactional
  ReconcileReport reconcileOrphansExcludingExecuting(long staleMinutes) {
    Instant cutoff = Instant.now().minus(Duration.ofMinutes(staleMinutes));
    List<ExecutionRun> stale =
        runRepo.findStaleNonTerminal(cutoff).stream()
            .filter(r -> !"EXECUTING".equals(r.getStatus()))
            .toList();

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

  /**
   * Periodic sweep of orphaned ExecutionRuns. Fires every 15 minutes with a {@link SchedulerLock}
   * so only one replica reconciles per window. The {@value #SCHEDULED_STALE_MINUTES}-minute cutoff
   * is deliberately wider than the manual default (30 min) to avoid racing a long-running, healthy
   * Launch.
   *
   * <p>This is the second-line catch — runs that the boot-time listener missed (e.g. a row that
   * went non-terminal AFTER {@link ApplicationReadyEvent} fired but the orchestrator thread still
   * died via some other mechanism) get caught here.
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
