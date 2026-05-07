package com.navneet.executespec.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Boot-time recovery for V2 runs left mid-flight by a previous JVM — crash, SIGTERM, or deploy
 * rollover.
 *
 * <p>Phase 1 behaviour (this class): <em>fail-cleanly</em>. The resumer scans non-terminal V2 runs
 * and marks each FAILED with a clear reason + emits an SSE event so the UI shows it. This turns
 * "stuck in WORKING forever" into "user can see and retry" — already a meaningful availability
 * improvement without needing replay logic.
 *
 * <p>Phase 2 (follow-up PR): actual replay via {@link
 * com.navneet.executespec.context.ContextStore#load} + {@code WorkerAgent.resumeFromSnapshot()} +
 * {@code SequentialExecutor.continueFromTask(...)}. Designed as a drop-in replacement for the
 * fail-path below.
 *
 * <p>Run state handling:
 *
 * <ul>
 *   <li>{@code AWAITING_APPROVAL} — untouched. The plan gate is user-facing; the user can approve
 *       whenever, and that path does not require the old orchestrator thread.
 *   <li>{@code PLANNING} — failed. No snapshot yet (worker hadn't started); no useful recovery
 *       other than restart-from-scratch, which we leave to the user.
 *   <li>{@code WORKING} / {@code REVIEWING} — failed with "recovery pending" reason. Snapshots
 *       exist in Redis, but replay wiring is Phase 2.
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
      List.of("PLANNING", "WORKING", "REVIEWING", "AWAITING_APPROVAL");

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

  /**
   * 2026-05-04 — lazy {@link RunOrchestrator} reference. Direct constructor injection would create
   * a bean-init cycle (RunOrchestrator already depends on many beans that could transitively reach
   * back here). {@link ObjectProvider} defers the lookup until {@link #scanAndRecover} actually
   * calls into the orchestrator, by which point the bean graph is fully wired.
   */
  private final org.springframework.beans.factory.ObjectProvider<
          com.navneet.executespec.integration.RunOrchestrator>
      runOrchestratorProvider;

  @Async
  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    try {
      scanAndRecover();
    } catch (Exception e) {
      // Never let recovery failures block startup.
      log.error("StartupRunResumer: unexpected error during scan", e);
    }
  }

  @Transactional
  public void scanAndRecover() {
    List<PipelineRun> candidates =
        runRepo.findByStatusIn(RESUMABLE_STATUSES).stream()
            .filter(r -> "v2".equalsIgnoreCase(r.getHarnessVersion()))
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
    int salvaged = 0;
    Instant cutoff = Instant.now().minus(STALE_CUTOFF);
    for (PipelineRun run : candidates) {
      String status = run.getStatus();
      if ("AWAITING_APPROVAL".equals(status)) {
        // User-facing gate; no orchestrator thread required to unblock.
        log.info("StartupRunResumer: run={} AWAITING_APPROVAL — left as-is", run.getRunId());
        awaitingApproval++;
        continue;
      }

      // NEW — "salvage" path: if the executor already completed (every
      // AgentTask is in a terminal status), the generated code is on disk
      // and the run is effectively done. Only the post-executor phase
      // (reviewer / validation) was interrupted, which is a strictly
      // non-destructive loss. Mark the run COMPLETE (with a clear
      // status_reason) so the user keeps access to the workspace, preview,
      // chat, and retry-validation — instead of being told to re-run
      // everything from scratch.
      if (isExecutorDone(run.getRunId())) {
        markSalvaged(run, status);
        salvaged++;
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

      Instant startedAt = run.getStartedAt();
      if (startedAt != null && startedAt.isBefore(cutoff)) {
        // Abandoned — too old to replay meaningfully. Fail without consuming
        // an attempt so the counter only reflects real resume attempts.
        markFailed(
            run,
            status,
            "Run exceeded "
                + STALE_CUTOFF.toHours()
                + "h since start; "
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
        var orchestrator = runOrchestratorProvider.getObject();
        var outcome = orchestrator.resumeAfterCrash(run.getRunId());
        log.info(
            "StartupRunResumer: run={} status={} resumeAfterCrash → {}",
            run.getRunId(),
            status,
            outcome);
        if (outcome == com.navneet.executespec.integration.RunOrchestrator.CrashResumeOutcome.OK) {
          failedInterrupted++; // counted under "interrupted-then-resumed" in the summary log
          continue;
        }
        // Any non-OK outcome (NOT_FOUND / WORKSPACE_GONE / PLAN_MISSING / EXHAUSTED /
        // NOT_APPLICABLE) was already terminate()'d inside resumeAfterCrash. Move on.
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
            + "salvaged={} interrupted={} cap-exceeded={} stale={}",
        awaitingApproval,
        salvaged,
        failedInterrupted,
        failedCapped,
        failedStale);
  }

  /**
   * True if every {@link AgentTask} for this run is in a terminal status. "Terminal" = COMPLETE /
   * PARTIAL / FAILED / BLOCKED. PENDING or WORKING means the executor didn't actually finish → the
   * run isn't salvageable, it was genuinely interrupted mid-flight.
   */
  private boolean isExecutorDone(String runId) {
    List<AgentTask> tasks = taskRepo.findByRunId(runId);
    if (tasks.isEmpty()) return false; // planner never made it — not salvageable
    for (AgentTask t : tasks) {
      if (t.getParentTaskId() != null) continue; // subtasks follow parent; ignore
      String s = t.getStatus();
      if (s == null) return false;
      if (!("COMPLETE".equals(s)
          || "PARTIAL".equals(s)
          || "FAILED".equals(s)
          || "BLOCKED".equals(s))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Mark a run COMPLETE (or PARTIAL if any task warned) when the executor already finished but a
   * post-executor phase was interrupted. The code is on disk; the user keeps access; no
   * resume-attempt consumed; validation report can be (re-)generated via {@code POST
   * /runs/{id}/retry-validation}.
   */
  private void markSalvaged(PipelineRun run, String previousStatus) {
    String runId = run.getRunId();
    List<AgentTask> tasks = taskRepo.findByRunId(runId);
    boolean anyNonClean =
        tasks.stream()
            .filter(t -> t.getParentTaskId() == null)
            .anyMatch(
                t ->
                    "PARTIAL".equals(t.getStatus())
                        || "FAILED".equals(t.getStatus())
                        || "BLOCKED".equals(t.getStatus()));
    String terminalStatus = anyNonClean ? "PARTIAL" : "COMPLETE";
    String reason = "REVIEWER_INTERRUPTED_CODE_PRESERVED";

    run.setStatus(terminalStatus);
    run.setStatusReason(reason);
    if (run.getFailureReason() == null) {
      run.setFailureReason(
          "Post-executor phase ("
              + previousStatus
              + ") was interrupted by a JVM restart, but all tasks had already finished and "
              + "the workspace is intact. Run marked "
              + terminalStatus
              + ". Click Regenerate on the Validation Report to re-run the reviewer + scoring.");
    }
    if (run.getCompletedAt() == null) run.setCompletedAt(Instant.now());
    runRepo.save(run);
    log.warn(
        "StartupRunResumer: run={} prev={} salvaged → {} ({})",
        runId,
        previousStatus,
        terminalStatus,
        reason);
    try {
      broadcaster.emit(runId, RunEvent.runStatus(runId, terminalStatus));
      broadcaster.complete(runId);
    } catch (Exception e) {
      log.debug("StartupRunResumer salvage: SSE emit failed for run={}: {}", runId, e.getMessage());
    }
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
