package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.isTerminal;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Run lifecycle / recovery control-plane, extracted from {@link RunOrchestrator} (god-class split,
 * increment 6 — audit #43). Owns the operator + user actions that act on an already-submitted run:
 * cancel / pause / resume / crash-resume / halt-all / force-fail-task / retry-task /
 * retry-validation / re-review.
 *
 * <p>Reaches the orchestrator's irreducible handles (the {@code activeRuns} interrupt map, the
 * resume entry into the staying {@code runPipeline}, and {@code terminate}) through the narrow
 * {@link RunControlPort} — a one-directional seam, so there is no bean cycle. Controllers ({@code
 * RunController} / {@code SuperAdminController} / {@code ProjectChatActionTools}) and {@code
 * StartupRunResumer} call this service directly. Behaviour is byte-identical to the originals.
 *
 * <p>Increment 6 lands in two gated batches: this commit moves the control-plane methods (cancel /
 * pause / force-fail / halt-all — no pipeline-execution coupling); the recovery/retry methods
 * (resume / resumeAfterCrash / retryTask / retryValidation / reReview + their pipeline-execution
 * bodies) follow in the second batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunLifecycleService {

  /**
   * Non-terminal status set used by the halt-all admin tool (M-010). Cheaper to query than scanning
   * every row; covers every status that {@link RunPipelineSupport#isTerminal(String)} rejects.
   *
   * <p>{@code PAUSED} is intentionally excluded from halt-all (V168) — paused runs are stable
   * checkpoints, not stuck work, and the operator should resume + cancel deliberately rather than
   * sweep them alongside genuinely-active runs.
   */
  static final List<String> NON_TERMINAL_STATUSES =
      List.of("QUEUED", "PLANNING", "AWAITING_APPROVAL", "WORKING", "REVIEWING");

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final SseBroadcaster broadcaster;
  private final Metrics metrics;
  private final GateStore gateStore;
  private final RunControlPort controlPort;

  public boolean cancel(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return false;
    var run = opt.get();
    String status = run.getStatus();
    if (isTerminal(status)) return false;

    log.info("RunOrchestrator cancel: run={} prev={}", runId, status);

    // Wake a thread blocked on the plan gate (reject path).
    try {
      gateStore.setPlanGate(runId, false);
    } catch (Exception e) {
      log.debug("cancel: plan gate signal failed (likely no gate open): {}", e.getMessage());
    }

    // Persist + SSE so the UI reflects the cancel immediately, even if the
    // pipeline thread hasn't unwound yet.
    run.setStatus("CANCELLED");
    run.setCompletedAt(Instant.now());
    if (run.getFailureReason() == null) {
      run.setFailureReason("Cancelled by user from status " + status);
    }
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "CANCELLED"));
    broadcaster.emit(runId, RunEvent.complete(runId, "CANCELLED"));
    broadcaster.complete(runId);
    metrics.recordRunOutcome("CANCELLED");

    // Interrupt the pipeline thread as a backstop. The pipeline itself is
    // not cancellation-aware at every step, so it may continue briefly —
    // but its terminal writes are no-ops because the run is already terminal.
    java.util.concurrent.Future<?> future = controlPort.removeActiveRun(runId);
    if (future != null) future.cancel(true);
    return true;
  }

  /**
   * Request a pause. Sets {@code pause_requested = true} on the row + emits the SSE so the UI flips
   * immediately. The actual transition to {@code PAUSED} happens cooperatively at the next task or
   * iteration boundary inside {@code SequentialExecutor.executeOne} or {@code WorkerAgent.run}; see
   * those for the checkpoint shape. The in-flight LLM round-trip is NOT cancelled (mid-call abort
   * is the deferred chunk #4 of the design — provider-specific work).
   *
   * <p>Returns true iff the pause request was accepted (run is in {@code WORKING} or {@code
   * REVIEWING}). Returns false for any other status — the controller maps this to HTTP 409.
   * Idempotent: a second pause request while the run is already PAUSED returns false (no churn on
   * paused runs).
   */
  public boolean pause(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return false;
    var run = opt.get();
    String status = run.getStatus();
    // Pause is only meaningful while work is happening. Other states either
    // already act as pause-equivalents (AWAITING_APPROVAL = plan gate; PAUSED
    // = already paused) or are too short / terminal to bother (PLANNING,
    // QUEUED, COMPLETE/FAILED/CANCELLED).
    if (!"WORKING".equals(status) && !"REVIEWING".equals(status)) return false;

    log.info("RunOrchestrator pause: run={} prev={}", runId, status);

    // Persist checkpoint state. paused_task_id + paused_at_iteration are best-effort here —
    // the executor's mid-task checkpoint write (deferred follow-up) would populate them
    // precisely. Today we record the timestamp + bump pause_count so support tooling can
    // diagnose state, and let resume re-plan + pick up from disk state.
    run.setPauseRequested(true);
    run.setStatus("PAUSED");
    run.setPausedAt(java.time.Instant.now());
    run.setPauseCount(run.getPauseCount() + 1);
    runRepo.save(run);

    // Interrupt the pipeline thread. The pipeline isn't fully cancellation-aware at every step,
    // but its terminal writes are no-ops because the run is already PAUSED. Files already written
    // to <workspace>/<runId>/ stay on disk — that's what the resume path picks up.
    java.util.concurrent.Future<?> future = controlPort.removeActiveRun(runId);
    if (future != null) future.cancel(true);

    broadcaster.emit(runId, RunEvent.runStatus(runId, "PAUSED"));
    broadcaster.emit(
        runId,
        RunEvent.log(runId, "pause", "Run paused. Workspace files preserved. Resume to continue."));
    return true;
  }

  /**
   * M-010 (Wave 2) — halt every non-terminal run on the platform. Used by SUPER_ADMIN during
   * incident response (e.g. provider outage, runaway cost, planner storm) to stop the bleeding
   * without cancelling runs one-by-one. Idempotent: re-running on a clean platform is a no-op.
   *
   * @param reason short rationale persisted on each run's {@code failureReason} (overrides the
   *     default "Cancelled by user" message {@link #cancel(String)} would write). If {@code
   *     null}/blank, the per-run default applies.
   * @return list of run IDs that were transitioned to CANCELLED.
   */
  public List<String> haltAllActiveRuns(String reason) {
    List<com.navneet.executespec.state.entity.PipelineRun> active =
        runRepo.findByStatusIn(NON_TERMINAL_STATUSES);
    List<String> halted = new java.util.ArrayList<>(active.size());
    for (var run : active) {
      String runId = run.getRunId();
      // Stamp a custom reason BEFORE cancel() so cancel's own
      // default doesn't overwrite the operator's message.
      if (reason != null && !reason.isBlank() && run.getFailureReason() == null) {
        run.setFailureReason("Halted by SUPER_ADMIN: " + reason);
        runRepo.save(run);
      }
      if (cancel(runId)) {
        halted.add(runId);
      }
    }
    log.warn(
        "RunOrchestrator HALT-ALL by SUPER_ADMIN: {} runs cancelled, reason={}",
        halted.size(),
        reason == null ? "(none)" : reason);
    return halted;
  }

  /**
   * 2026-05-04 — admin escape hatch for stuck tasks (companion to the @Retryable persist that
   * prevents the bug class going forward). Flips {@code agent_task.status} from {@code WORKING} or
   * {@code PENDING} to {@code FAILED}, stamps {@code last_llm_failure} with the supplied reason for
   * the audit trail, and emits the SSE event so the dashboard updates immediately. Refuses to touch
   * tasks already in a terminal status — those finished legitimately and force- failing them would
   * lose information. Returns a small report for the admin endpoint.
   *
   * @throws IllegalArgumentException if the run/task isn't found
   * @throws IllegalStateException if the task is already in a terminal status
   */
  public java.util.Map<String, Object> forceFailTask(String runId, String taskId, String reason) {
    if (runRepo.findById(runId).isEmpty()) {
      throw new IllegalArgumentException("Run not found: " + runId);
    }
    String key = runId.substring(0, 8) + "_" + taskId;
    var task =
        taskRepo
            .findById(key)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    String current = task.getStatus();
    if ("COMPLETE".equals(current)
        || "FAILED".equals(current)
        || "PARTIAL".equals(current)
        || "BLOCKED".equals(current)
        || "COMPLETED_WITH_WARNING".equals(current)) {
      throw new IllegalStateException(
          "Task already terminal (status=" + current + "). Won't overwrite legitimate completion.");
    }
    task.setStatus("FAILED");
    task.setLastLlmFailure("FORCE_FAILED_BY_ADMIN: " + reason);
    if (task.getCompletedAt() == null) task.setCompletedAt(java.time.Instant.now());
    taskRepo.save(task);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "FAILED"));
    log.warn(
        "RunOrchestrator forceFailTask: run={} task={} prevStatus={} -> FAILED reason={}",
        runId,
        taskId,
        current,
        reason);
    return java.util.Map.of(
        "runId", runId,
        "taskId", taskId,
        "previousStatus", current,
        "newStatus", "FAILED",
        "reason", reason);
  }
}
