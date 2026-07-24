package com.navneet.executespec.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Boot-time recovery for V2 runs left mid-flight by a previous JVM — crash, SIGTERM, or deploy
 * rollover.
 *
 * <p>Recovery claims a bounded restart attempt with a database compare-and-set, validates the
 * heartbeat/workspace state, and resumes from persisted task/context checkpoints when the run is
 * eligible. Stale or exhausted runs fail cleanly with an explicit reason and an SSE event. The
 * recovery path does not rebind an in-flight provider future, transfer a workspace across nodes, or
 * provide cross-JVM hot handoff; those cases are deliberately surfaced as bounded recovery outcomes
 * rather than being presented as transparent continuation.
 *
 * <p>Run state handling:
 *
 * <ul>
 *   <li>{@code AWAITING_APPROVAL} — untouched. The plan gate is user-facing; the user can approve
 *       whenever, and that path does not require the old orchestrator thread.
 *   <li>{@code PLANNING} — recovered only when the persisted plan/task state is sufficient;
 *       otherwise failed with an explicit restart-required reason.
 *   <li>{@code WORKING} / {@code REVIEWING} — resumed from the persisted checkpoint when the
 *       workspace and heartbeat checks pass; stale, exhausted, or unrecoverable rows fail cleanly.
 * </ul>
 *
 * <p>Scoping: only touches rows where {@code harnessVersion = "v2"}. V1's {@code StartupRunResumer}
 * remains authoritative for legacy runs until the deprecation-removal plan lands.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartupRunResumer {

  /** Statuses that indicate a run was in-flight when the JVM died. */
  private static final List<String> RESUMABLE_STATUSES =
      List.of("PLANNING", "WORKING", "REVIEWING", "AWAITING_APPROVAL", "APPROVAL_RESUMING");

  /**
   * Hard cap on resume attempts per run. Once exceeded the run is permanently FAILED; the user can
   * submit a fresh run instead. Made public 2026-05-04 so {@code RunOrchestrator.resumeAfterCrash}
   * can reuse the same constant — both paths must enforce the same cap or a run that bounces
   * between the two could exceed it.
   */
  public static final int MAX_RESUME_ATTEMPTS = 3;

  /**
   * Runs older than this at boot time are considered abandoned rather than interrupted — we fail
   * them cleanly instead of attempting resume.
   */
  private static final Duration STALE_CUTOFF = Duration.ofHours(6);

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final SseBroadcaster broadcaster;
  private final AtomicBoolean scanInProgress = new AtomicBoolean();

  /**
   * 2026-05-04 — lazy {@code RunLifecycleService} reference (was RunOrchestrator before the inc-6b
   * god-class split; {@code resumeAfterCrash} moved to the lifecycle service). Direct constructor
   * injection would create a bean-init cycle (the service transitively depends on RunOrchestrator,
   * which depends on many beans that could reach back here). {@link ObjectProvider} defers the
   * lookup until {@link #scanAndRecover} actually calls in, by which point the graph is fully
   * wired.
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.integration.RunLifecycleService>
      runLifecycleServiceProvider;

  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    runRecoveryScan("startup");
  }

  /**
   * Retry candidates that were legitimately unclaimable during the boot scan because the dead
   * predecessor's last heartbeat or task update had not yet crossed the two-minute stale fence. The
   * database compare-and-set in {@code resumeAfterCrash} remains the takeover authority; this sweep
   * only makes a prior NOT_APPLICABLE decision non-permanent.
   */
  @Scheduled(
      fixedDelayString = "${execution.recovery.scan-interval-ms:60000}",
      initialDelayString = "${execution.recovery.scan-initial-delay-ms:60000}")
  void scheduledRecoverySweep() {
    runRecoveryScan("scheduled");
  }

  private void runRecoveryScan(String trigger) {
    if (!scanInProgress.compareAndSet(false, true)) {
      log.debug("StartupRunResumer: {} scan skipped because another scan is active", trigger);
      return;
    }
    try {
      scanAndRecover("scheduled".equals(trigger));
    } catch (Exception e) {
      // Never let recovery failures block startup.
      log.error("StartupRunResumer: unexpected error during {} scan", trigger, e);
    } finally {
      scanInProgress.set(false);
    }
  }

  @Transactional
  public void scanAndRecover() {
    scanAndRecover(false);
  }

  private void scanAndRecover(boolean scheduledSweep) {
    Instant heartbeatCutoff = Instant.now().minus(Duration.ofMinutes(2));
    List<PipelineRun> candidates =
        runRepo.findByStatusIn(RESUMABLE_STATUSES).stream()
            .filter(r -> "v2".equalsIgnoreCase(r.getHarnessVersion()))
            // Periodic recovery must not poll every live run or durable plan gate. It exists only
            // to revisit takeover candidates once the previous owner's heartbeat can satisfy the
            // same two-minute stale fence enforced by RunLifecycleService.claimCrashResume.
            .filter(
                r ->
                    !scheduledSweep
                        || (!("AWAITING_APPROVAL".equals(r.getStatus())
                                || "APPROVAL_RESUMING".equals(r.getStatus()))
                            && (r.getExecutionHeartbeat() != null
                                ? !r.getExecutionHeartbeat().isAfter(heartbeatCutoff)
                                : r.getStartedAt() == null
                                    || !r.getStartedAt().isAfter(heartbeatCutoff))))
            .toList();

    if (candidates.isEmpty()) {
      log.info("StartupRunResumer: no interrupted V2 runs to recover");
      return;
    }

    log.warn("StartupRunResumer: found {} interrupted V2 run(s); recovering", candidates.size());

    int awaitingApproval = 0;
    int failedInterrupted = 0;
    int failedCapped = 0;
    int failedStale = 0;
    int deferred = 0;
    Instant cutoff = Instant.now().minus(STALE_CUTOFF);
    for (PipelineRun run : candidates) {
      String status = run.getStatus();
      if ("AWAITING_APPROVAL".equals(status) || "APPROVAL_RESUMING".equals(status)) {
        try {
          var outcome = runLifecycleServiceProvider.getObject().recoverPlanGate(run.getRunId());
          log.info(
              "StartupRunResumer: run={} status={} recoverPlanGate → {}",
              run.getRunId(),
              status,
              outcome);
          awaitingApproval++;
        } catch (Exception e) {
          // Leave the durable gate untouched. A later approval retry or next restart can reconcile
          // it; failing the run here would destroy a valid user decision.
          log.error(
              "StartupRunResumer: durable plan-gate recovery failed for {}", run.getRunId(), e);
        }
        continue;
      }

      int attempts = run.getResumeAttempts() == null ? 0 : run.getResumeAttempts();
      if (attempts >= MAX_RESUME_ATTEMPTS) {
        markFailed(
            run,
            status,
            "Resume attempt limit ("
                + MAX_RESUME_ATTEMPTS
                + ") reached. "
                + "Please submit a fresh run.");
        failedCapped++;
        continue;
      }

      // Long-running work must not become "abandoned" merely because its original start time is
      // old. The execution owner refreshes this durable heartbeat every 30 seconds, so a run that
      // was healthy immediately before a JVM/VM restart remains eligible for the fenced recovery
      // path even after six hours. Legacy rows without a heartbeat retain the started-at fallback.
      Instant latestRunActivity =
          run.getExecutionHeartbeat() != null ? run.getExecutionHeartbeat() : run.getStartedAt();
      if (latestRunActivity != null && latestRunActivity.isBefore(cutoff)) {
        // Abandoned — too old to replay meaningfully. Fail without consuming
        // an attempt so the counter only reflects real resume attempts.
        markFailed(
            run,
            status,
            "Run exceeded "
                + STALE_CUTOFF.toHours()
                + "h since its last durable execution activity; "
                + "considered stale rather than interrupted.");
        failedStale++;
        continue;
      }

      // 2026-05-04 — Phase 2: actual replay instead of fail-cleanly.
      // Delegates to RunOrchestrator.resumeAfterCrash which:
      //   - Reads the persisted plan from pipeline_run.plan_json
      //   - Reuses the existing workspace at <agent.workspace>/<runId>/
      //   - Calls SequentialExecutor.execute(req, ResumeRequest) so terminal AgentTasks are
      //     skipped and the in-flight task picks up from its Redis snapshot
      //   - Then runs reviewer + report (REVIEWING-resume re-runs the reviewer; idempotent)
      // resume_attempts is incremented INSIDE resumeAfterCrash so the increment is tied to the
      // actual dispatch, not just our fall-through.
      try {
        var lifecycle = runLifecycleServiceProvider.getObject();
        var outcome = lifecycle.resumeAfterCrash(run.getRunId());
        log.info(
            "StartupRunResumer: run={} status={} resumeAfterCrash → {}",
            run.getRunId(),
            status,
            outcome);
        if (outcome == com.navneet.executespec.integration.RunOrchestrator.CrashResumeOutcome.OK) {
          failedInterrupted++; // counted under "interrupted-then-resumed" in the summary log
          continue;
        }
        if (outcome
            == com.navneet.executespec.integration.RunOrchestrator.CrashResumeOutcome
                .NOT_APPLICABLE) {
          // A fresh predecessor heartbeat/task lease is expected immediately after a rolling
          // restart. Leave the row untouched; the scheduled sweep will retry after the stale
          // fence expires. This used to be a one-shot boot decision and stranded runs forever.
          deferred++;
          continue;
        }
        // Other non-OK outcomes are terminalized inside resumeAfterCrash.
        failedInterrupted++;
      } catch (Exception e) {
        // If the resume entry-point itself blew up, fall back to the old fail-cleanly path so
        // the run doesn't sit forever in WORKING.
        log.error(
            "StartupRunResumer: resumeAfterCrash threw for run={}; falling back to fail-cleanly",
            run.getRunId(),
            e);
        run.setResumeAttempts(attempts + 1);
        markFailed(
            run,
            status,
            "Interrupted during " + status + "; resume dispatch failed: " + e.getMessage());
        failedInterrupted++;
      }
    }
    log.warn(
        "StartupRunResumer: recovery complete — awaiting-approval={} "
            + "interrupted={} deferred={} cap-exceeded={} stale={}",
        awaitingApproval,
        failedInterrupted,
        deferred,
        failedCapped,
        failedStale);
  }

  private void markFailed(PipelineRun run, String previousStatus, String reason) {
    String runId = run.getRunId();
    run.setStatus("FAILED");
    run.setFailureReason(reason);
    run.setCompletedAt(Instant.now());
    runRepo.save(run);
    log.warn("StartupRunResumer: run={} prev={} → FAILED ({})", runId, previousStatus, reason);
    try {
      broadcaster.emit(runId, RunEvent.runStatus(runId, "FAILED"));
      broadcaster.complete(runId);
    } catch (Exception e) {
      log.debug("StartupRunResumer: SSE emit failed for run={}: {}", runId, e.getMessage());
    }
  }
}
