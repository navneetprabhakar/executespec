package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.deserializeRepoIds;
import static com.navneet.executespec.integration.RunPipelineSupport.isTerminal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.RunKeyContextStore;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.config.OperationalReleasePolicy;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.cost.RunTokenAttributor;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.recovery.RecoveryCheckpointLease;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.ReviewerAgent;
import com.navneet.executespec.reviewer.ReviewerRequest;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.remote.RemoteToolDispatcher;

import org.springframework.stereotype.Component;

import lombok.AllArgsConstructor;
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
@AllArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
@Slf4j
public class RunLifecycleService {

  /** A heartbeat newer than this means another JVM still owns the run. */
  private static final java.time.Duration CRASH_RECOVERY_HEARTBEAT_STALE_AFTER =
      java.time.Duration.ofMinutes(2);

  /** Task-level freshness window used to fence a worker that is still making progress. */
  private static final java.time.Duration CRASH_RECOVERY_TASK_STALE_AFTER =
      java.time.Duration.ofMinutes(2);

  /** Stable, non-secret owner label persisted with a recovery claim for operator diagnostics. */
  private static final String RECOVERY_INSTANCE_ID = ExecutionHostIdentity.current();

  /** Compatibility constructor retained for focused lifecycle tests and older embedders. */
  public RunLifecycleService(
      PipelineRunRepository runRepo,
      AgentTaskRepository taskRepo,
      SseBroadcaster broadcaster,
      Metrics metrics,
      GateStore gateStore,
      RunControlPort controlPort,
      AgentProperties agentProps,
      ObjectMapper objectMapper,
      PipelineExecutorPool pool,
      SequentialExecutor executor,
      ReviewerAgent reviewer,
      RunCostTracker costTracker,
      RunTokenAttributor tokenAttributor,
      RunKeyContextStore keyContextStore,
      WorkspaceRestoreService workspaceRestoreService,
      RunPersistenceWriter persistenceWriter,
      RunContextComposer contextComposer,
      RunValidationKickoff validationKickoff,
      ModelResolver modelResolver,
      ValidationReportRepository validationReportRepository) {
    this(
        runRepo,
        taskRepo,
        broadcaster,
        metrics,
        gateStore,
        controlPort,
        agentProps,
        objectMapper,
        pool,
        executor,
        reviewer,
        costTracker,
        tokenAttributor,
        keyContextStore,
        workspaceRestoreService,
        persistenceWriter,
        contextComposer,
        validationKickoff,
        modelResolver,
        validationReportRepository,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * Non-terminal status set used by the halt-all admin tool (M-010). Cheaper to query than scanning
   * every row; covers every status that {@link RunPipelineSupport#isTerminal(String)} rejects.
   *
   * <p>{@code PAUSED} is intentionally excluded from halt-all (V168) — paused runs are stable
   * checkpoints, not stuck work, and the operator should resume + cancel deliberately rather than
   * sweep them alongside genuinely-active runs.
   */
  static final List<String> NON_TERMINAL_STATUSES =
      List.of(
          "QUEUED", "PLANNING", "AWAITING_APPROVAL", "APPROVAL_RESUMING", "WORKING", "REVIEWING");

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final SseBroadcaster broadcaster;
  private final Metrics metrics;
  private final GateStore gateStore;
  private final RunControlPort controlPort;

  // Recovery/retry-path collaborators (inc-6b) — the resume / crash-resume / retry / re-review
  // bodies are pipeline-execution, so they carry the executor + reviewer + report deps directly.
  private final AgentProperties agentProps;
  private final ObjectMapper objectMapper;
  private final PipelineExecutorPool pool;
  private final SequentialExecutor executor;
  private final ReviewerAgent reviewer;
  private final RunCostTracker costTracker;
  private final RunTokenAttributor tokenAttributor;
  private final RunKeyContextStore keyContextStore;
  private final WorkspaceRestoreService workspaceRestoreService;
  private final RunPersistenceWriter persistenceWriter;
  private final RunContextComposer contextComposer;
  private final RunValidationKickoff validationKickoff;
  private final ModelResolver modelResolver;
  private final ValidationReportRepository validationReportRepository;
  private final FeatureFlagService featureFlagService;
  private final RemoteToolDispatcher remoteToolDispatcher;
  private final OrgModelOverrideService orgModelOverrideService;
  private final ModelRegistry modelRegistry;
  private final OperationalReleasePolicy operationalReleasePolicy;
  private final LlmExecutionPolicyFactory llmExecutionPolicyFactory;
  private final PlanGateDecisionService planGateDecisionService;
  private final PlanGateResumeStore planGateResumeStore;
  private final RunLlmOperationLifecycleService runOperationLifecycle;
  private final RunEffectiveRouteService effectiveRoutes;
  private final com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard
      brownfieldHttpContractGuard;
  private final CorrectionWorkspaceTransaction correctionWorkspaceTransaction;
  private final WorkflowConfigEvidenceInspector workflowConfigEvidenceInspector;
  private final ApprovedContinuationRouteAdmissionService approvedRouteAdmission;
  private final CorrectionValidationAdmission correctionValidationAdmission;

  public enum PlanApprovalOutcome {
    RESUME_QUEUED,
    REJECTED,
    ALREADY_RESOLVED,
    CONFLICTING_DECISION,
    NOT_FOUND,
    INVALID_STATE,
    WORKSPACE_GONE,
    PLAN_MISSING
  }

  public boolean cancel(String runId) {
    return cancel(runId, null);
  }

  public boolean cancel(String runId, AppUserDetails actor) {
    return cancel(
        runId,
        actor,
        actor == null
            ? PlanGateDecisionService.ResolutionSource.SYSTEM_CANCEL
            : PlanGateDecisionService.ResolutionSource.RUN_CANCEL_API);
  }

  public boolean cancel(
      String runId,
      AppUserDetails actor,
      PlanGateDecisionService.ResolutionSource resolutionSource) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return false;
    var run = opt.get();
    String status = run.getStatus();
    if (isTerminal(status)) return false;

    log.info("RunOrchestrator cancel: run={} prev={}", runId, status);

    if ("AWAITING_APPROVAL".equals(status)) {
      if (planGateDecisionService == null) return false;
      PlanGateDecisionService.DecisionResult decision =
          planGateDecisionService.cancel(runId, actor, resolutionSource, "Cancelled by user");
      if (decision.decision() == PlanGateDecisionService.DecisionKind.CANCEL) {
        emitCancellation(runId);
        return true;
      }
      // Approval may have committed after the optimistic status read but before the decision
      // service acquired its run/gate/outbox locks. Reload and continue through the fenced
      // post-approval or normal WORKING cancellation path; never report a false conflict or make
      // the caller retry.
      PipelineRun current = runRepo.findById(runId).orElse(null);
      if (current == null || isTerminal(current.getStatus())) return false;
      status = current.getStatus();
    }
    if ("APPROVAL_RESUMING".equals(status)) {
      if (planGateResumeStore != null
          && planGateResumeStore.cancelApprovedRun(
              runId, "Cancelled by user after approval", Instant.now())) {
        emitCancellation(runId);
        return true;
      }
      // Handoff may have won between our initial read and the fenced cancellation transaction.
      // Reload and continue through normal WORKING cancellation rather than making the user retry.
      PipelineRun current = runRepo.findById(runId).orElse(null);
      if (current == null || isTerminal(current.getStatus())) return false;
      status = current.getStatus();
    }

    // Claim cancellation before touching transient state. A reviewer/validation thread may reach
    // terminal at the same time; only one terminal writer is allowed to win, and this narrow query
    // avoids saving a stale PipelineRun entity over concurrent usage/evidence fields.
    if (runRepo.claimTerminalTransitionWithReasons(
            runId, "CANCELLED", null, "Cancelled by user from status " + status, Instant.now())
        != 1) {
      return false;
    }

    // Interrupt before snapshotting so an in-flight provider request is asked to unwind first.
    // The tracker snapshot is an immediate floor; the pipeline's late terminate path raises it
    // again after all already-recorded calls have settled.
    java.util.concurrent.Future<?> future = controlPort.removeActiveRun(runId);
    if (future != null) future.cancel(true);
    tokenAttributor.reconcileRunTotalsFromLedger(runId);
    RunCostTracker.Snapshot cost = costTracker.snapshot(runId);
    persistenceWriter.raiseUsageFloor(runId, cost);

    // SSE makes the UI reflect the already-persisted cancellation immediately.
    emitCancellation(runId);
    return true;
  }

  private void emitCancellation(String runId) {
    broadcaster.emit(runId, RunEvent.runStatus(runId, "CANCELLED"));
    broadcaster.emit(runId, RunEvent.complete(runId, "CANCELLED"));
    broadcaster.complete(runId);
    metrics.recordRunOutcome("CANCELLED");
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

  /**
   * Resume a previously-paused run. See {@code RunOrchestrator.ResumeOutcome} for return shape.
   *
   * <p>Same-JVM only: checks that {@code <agent.workspace>/<runId>/} still exists. When the
   * pause-side workspace is gone returns {@code WORKSPACE_GONE} and leaves the run {@code PAUSED}.
   * Resubmits {@code runPipeline} (via {@link RunControlPort#runPipelineForResume}) on the worker
   * pool with {@code startFromTaskId} from {@code pipeline_run.paused_task_id}.
   */
  public RunOrchestrator.ResumeOutcome resume(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return RunOrchestrator.ResumeOutcome.NOT_FOUND;
    var run = opt.get();
    if (!"PAUSED".equals(run.getStatus())) return RunOrchestrator.ResumeOutcome.NOT_PAUSED;

    // Same-JVM check — workspace dir present?
    java.nio.file.Path workspace =
        java.nio.file.Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(workspace)) {
      log.warn(
          "RunOrchestrator resume rejected: workspace not on this node (cross-JVM resume needs"
              + " GCS shipping — chunk #3 of pause/resume). run={} workspace={}",
          runId,
          workspace);
      return RunOrchestrator.ResumeOutcome.WORKSPACE_GONE;
    }

    log.info(
        "RunOrchestrator resume: run={} prev=PAUSED pausedTaskId={} pausedAtIteration={}",
        runId,
        run.getPausedTaskId(),
        run.getPausedAtIteration());

    // Reset the pause flag so the executor doesn't immediately re-pause; bump
    // counters; flip status back to WORKING so the UI un-greys.
    run.setPauseRequested(false);
    run.setResumedAt(java.time.Instant.now());
    run.setStatus("WORKING");
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
    broadcaster.emit(runId, RunEvent.log(runId, "resume", "Resuming from checkpoint"));

    // Resubmit on the pool. The pipeline reads pausedTaskId from the row and
    // skips earlier tasks; ContextStore's saved snapshot rehydrates the
    // worker conversation for the partly-completed task.
    final RunOrchestrator.RunPipelineRequest reReq =
        RunOrchestrator.RunPipelineRequest.fromPausedRow(run, persistedPlanGateContinuation(run));
    dispatchRegistered(runId, () -> controlPort.runPipelineForResume(reReq));
    return RunOrchestrator.ResumeOutcome.OK;
  }

  /**
   * Crash-recovery resume — runs interrupted by a JVM SIGTERM / OOM / crash mid-flight. Called from
   * {@link com.navneet.executespec.recovery.StartupRunResumer}. Strategy by status: {@code
   * PLANNING} re-runs the planner; {@code WORKING}/{@code REVIEWING} load the persisted plan +
   * reuse the workspace and dispatch to the executor/reviewer via {@link
   * #runPipelineFromExecuteCheckpoint}. Per-run cap via {@code
   * StartupRunResumer.MAX_RESUME_ATTEMPTS}; increments {@code resume_attempts} BEFORE submitting so
   * a crash inside resume can't loop forever.
   */
  public RunOrchestrator.CrashResumeOutcome resumeAfterCrash(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return RunOrchestrator.CrashResumeOutcome.NOT_FOUND;
    var run = opt.get();
    String interruptedInstance = run.getExecutingInstance();
    String status = run.getStatus();
    if (!"PLANNING".equals(status) && !"WORKING".equals(status) && !"REVIEWING".equals(status)) {
      return RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE;
    }

    int attempts = run.getResumeAttempts() == null ? 0 : run.getResumeAttempts();
    if (attempts >= com.navneet.executespec.recovery.StartupRunResumer.MAX_RESUME_ATTEMPTS) {
      log.warn(
          "resumeAfterCrash run={} status={} attempts={} EXHAUSTED — failing run",
          runId,
          status,
          attempts);
      controlPort.terminate(
          runId, "FAILED", "resume cap reached after " + attempts + " attempts", null);
      return RunOrchestrator.CrashResumeOutcome.EXHAUSTED;
    }

    // A run heartbeat can be stale while an individual task is still making progress (for
    // example during a scheduler pause or a long provider turn).  Do not let boot recovery queue a
    // second executor in that case.  The task timestamp is refreshed at every durable task-status
    // write and is therefore a useful, independent signal from the run-level lease.
    Instant taskCutoff = Instant.now().minus(CRASH_RECOVERY_TASK_STALE_AFTER);
    if (hasFreshActiveTask(runId, taskCutoff)) {
      log.info(
          "resumeAfterCrash run={} has a fresh WORKING/LOCKED task; leaving live worker untouched",
          runId);
      return RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE;
    }

    // Claim before touching the workspace or submitting to the pool. Startup recovery may run
    // concurrently on two JVMs during a rolling restart; only the caller that advances the
    // durable attempt counter may proceed. A recent heartbeat means the original worker is still
    // alive, so this caller must leave the run untouched and let that worker finish.
    Instant claimAt = Instant.now();
    int nextAttempt = attempts + 1;
    int claimed =
        runRepo.claimCrashResume(
            runId,
            attempts,
            nextAttempt,
            claimAt,
            claimAt.minus(CRASH_RECOVERY_HEARTBEAT_STALE_AFTER),
            RECOVERY_INSTANCE_ID);
    if (claimed != 1) {
      log.info(
          "resumeAfterCrash run={} recovery claim lost (attempt={} or live heartbeat); leaving run"
              + " untouched",
          runId,
          attempts);
      emitRecoveryEvidence(runId, "CROSS_INSTANCE_LEASE_LOST", "REFUSED", 0);
      return RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE;
    }
    // Keep the in-memory row coherent for any downstream diagnostics; the database update above
    // is the source of truth and deliberately avoids a stale full-entity save.
    run.setResumeAttempts(nextAttempt);
    run.setResumedAt(claimAt);
    run.setExecutingInstance(RECOVERY_INSTANCE_ID);
    run.setExecutionHeartbeat(claimAt);

    // A local workspace is trustworthy after a same-host process restart. A different host may
    // proceed only when operators have explicitly configured a shared persistent filesystem. The
    // existing asynchronous GCS task checkpoint has no persisted acknowledged task frontier, so
    // it is deliberately not treated as sufficient proof: restoring it could pair T(n-1) files
    // with a database row that already marks T(n) terminal.
    boolean sameHostRestart =
        interruptedInstance != null && interruptedInstance.equals(RECOVERY_INSTANCE_ID);
    boolean sharedWorkspace =
        operationalReleasePolicy != null
            && operationalReleasePolicy.sharedPersistentWorkspaceConfigured();
    if (operationalReleasePolicy != null && !sameHostRestart && !sharedWorkspace) {
      String reason =
          "cross-node recovery refused: no shared persistent workspace or acknowledged checkpoint"
              + " frontier";
      log.warn("resumeAfterCrash run={} {}", runId, reason);
      emitRecoveryEvidence(runId, "UNTRUSTED_WORKSPACE_FRONTIER", "REFUSED", 0);
      controlPort.terminate(runId, "FAILED", reason, null);
      return RunOrchestrator.CrashResumeOutcome.WORKSPACE_GONE;
    }

    // Fence each stale in-flight task before submitting the resumed pipeline.  This is deliberately
    // fail-closed: if another worker changed the row between the read above and this CAS, do not
    // queue a duplicate recovery attempt.  The run claim remains durable and the next scanner can
    // reconcile it using the normal heartbeat/attempt cap.
    if (!claimStaleActiveTasks(runId, claimAt, taskCutoff)) {
      log.info(
          "resumeAfterCrash run={} task-level recovery claim lost; not queueing duplicate work",
          runId);
      emitRecoveryEvidence(runId, "TASK_LEASE_LOST", "REFUSED", 0);
      return RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE;
    }

    // Workspace must be on this node. Cross-node resume needs GCS shipping (chunk #3 of the
    // pause/resume design — same constraint as the existing PAUSED resume path).
    java.nio.file.Path workspace =
        java.nio.file.Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(workspace)) {
      // RL-2026-05-041 (audit #2) — mid-run durability. Before giving up, try to restore the latest
      // GCS checkpoint (written at each task boundary by GcsTaskCheckpointService).
      boolean restored = false;
      try {
        workspaceRestoreService.ensureWorkspaceAvailable(runId);
        restored = java.nio.file.Files.isDirectory(workspace);
      } catch (Exception restoreEx) {
        log.warn(
            "resumeAfterCrash run={} GCS workspace restore failed: {}",
            runId,
            restoreEx.getMessage());
      }
      if (restored) {
        log.info(
            "resumeAfterCrash run={} restored workspace from GCS checkpoint — resuming", runId);
      } else {
        log.warn(
            "resumeAfterCrash run={} workspace not on this node + no restorable GCS checkpoint —"
                + " failing cleanly (credits refunded via RunFailed)",
            runId);
        emitRecoveryEvidence(runId, "WORKSPACE_UNAVAILABLE", "REFUSED", 0);
        controlPort.terminate(runId, "FAILED", "workspace gone (" + workspace + ")", null);
        return RunOrchestrator.CrashResumeOutcome.WORKSPACE_GONE;
      }
    }

    RecoveryCheckpointLease.AcquireResult checkpointLease =
        RecoveryCheckpointLease.acquire(
            workspace, runId, RECOVERY_INSTANCE_ID, run.getPlanJson(), claimAt);
    if (!checkpointLease.acquired()) {
      log.warn(
          "resumeAfterCrash run={} checkpoint lease refused reason={}; not queueing takeover",
          runId,
          checkpointLease.reason());
      emitRecoveryEvidence(runId, "CHECKPOINT_LEASE_" + checkpointLease.reason(), "REFUSED", 0);
      return RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE;
    }

    // PLANNING crash → just re-run the planner (cheap) by calling the standard runPipeline.
    if ("PLANNING".equals(status)) {
      log.info(
          "resumeAfterCrash run={} status=PLANNING attempt={} re-running planner",
          runId,
          attempts + 1);
      broadcaster.emit(runId, RunEvent.runStatus(runId, "PLANNING"));
      broadcaster.emit(
          runId, RunEvent.log(runId, "resume", "Resuming after crash — re-running planner"));
      RunOrchestrator.RunPipelineRequest req =
          RunOrchestrator.RunPipelineRequest.fromPausedRow(run, persistedPlanGateContinuation(run));
      dispatchRegistered(runId, () -> controlPort.runPipelineForResume(req));
      return RunOrchestrator.CrashResumeOutcome.OK;
    }

    // WORKING / REVIEWING — load persisted plan and skip planning phase entirely.
    String planJson = run.getPlanJson();
    if (planJson == null || planJson.isBlank()) {
      log.warn(
          "resumeAfterCrash run={} status={} planJson missing — can't resume mid-execute, failing",
          runId,
          status);
      controlPort.terminate(runId, "FAILED", "plan missing on resume", null);
      return RunOrchestrator.CrashResumeOutcome.PLAN_MISSING;
    }
    final com.navneet.executespec.agent.Plan plan;
    try {
      plan = objectMapper.readValue(planJson, com.navneet.executespec.agent.Plan.class);
    } catch (Exception e) {
      log.warn(
          "resumeAfterCrash run={} status={} planJson deserialize failed: {}",
          runId,
          status,
          e.getMessage());
      controlPort.terminate(
          runId,
          "FAILED",
          RunOrchestrator.INTERNAL_ERROR_REASON,
          RunOrchestrator.INTERNAL_ERROR_REASON,
          null);
      return RunOrchestrator.CrashResumeOutcome.PLAN_MISSING;
    }

    broadcaster.emit(runId, RunEvent.runStatus(runId, status));
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            "resume",
            "Resuming after crash from " + status + " (attempt " + (attempts + 1) + ")"));

    log.info(
        "resumeAfterCrash run={} status={} attempt={} dispatching to pool",
        runId,
        status,
        attempts + 1);

    final Path resumeWorkspace = workspace;
    final boolean resumeFromReviewing = "REVIEWING".equals(status);
    final PlanGateContinuation durableContinuation = persistedPlanGateContinuation(run);
    dispatchRegistered(
        runId,
        () ->
            runPipelineFromExecuteCheckpoint(
                runId, plan, resumeWorkspace, resumeFromReviewing, durableContinuation));
    return RunOrchestrator.CrashResumeOutcome.OK;
  }

  /**
   * Registers a recovery task before the executor can run its first instruction. Using {@code
   * submitting a task and registering the returned future afterwards left a real window in which an
   * inline/fast executor could finish (and remove itself) before the stale future was put into the
   * active-run map. Rejection removes the pre-registered handle as well.
   */
  void dispatchRegistered(String runId, Runnable operation) {
    AtomicReference<FutureTask<Void>> registeredTask = new AtomicReference<>();
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              try {
                operation.run();
              } finally {
                controlPort.removeActiveRun(runId, registeredTask.get());
              }
              return null;
            });
    registeredTask.set(task);
    controlPort.putActiveRun(runId, task);
    try {
      pool.executor().execute(task);
    } catch (RuntimeException e) {
      controlPort.removeActiveRun(runId, task);
      throw e;
    }
  }

  private boolean hasFreshActiveTask(String runId, Instant staleBefore) {
    if (taskRepo == null) return false; // compatibility constructors used by focused unit tests
    return taskRepo.findByRunIdAndStatusIn(runId, List.of("WORKING", "LOCKED")).stream()
        .anyMatch(
            task ->
                task.getLastActivityAt() != null && task.getLastActivityAt().isAfter(staleBefore));
  }

  /**
   * Publish a typed, replayable recovery outcome for cross-instance safety decisions. The event is
   * deliberately additive to the durable lease/CAS: it never grants ownership and is best effort so
   * an SSE outage cannot change the recovery result.
   */
  private void emitRecoveryEvidence(String runId, String classification, String action, int locks) {
    if (broadcaster == null) return;
    try {
      broadcaster.emit(
          runId, RunEvent.recoveryAttempted(runId, null, classification, action, locks));
    } catch (RuntimeException eventFailure) {
      log.debug(
          "resumeAfterCrash run={} recovery evidence emit failed: {}",
          runId,
          eventFailure.getMessage());
    }
  }

  private boolean claimStaleActiveTasks(String runId, Instant now, Instant staleBefore) {
    if (taskRepo == null) return true; // compatibility constructors used by focused unit tests
    for (AgentTask task : taskRepo.findByRunIdAndStatusIn(runId, List.of("WORKING", "LOCKED"))) {
      if (taskRepo.claimStaleForRecovery(task.getTaskId(), now, staleBefore) != 1) {
        return false;
      }
    }
    return true;
  }

  /**
   * Inner crash-resume orchestration — assumes plan is loaded, workspace is present. Skips clone /
   * planning entirely; dispatches straight to executor (with a {@code
   * SequentialExecutor.ResumeRequest} so terminal AgentTasks are skipped) or — for {@code
   * REVIEWING} — straight to the reviewer.
   */
  private void runPipelineFromExecuteCheckpoint(
      String runId,
      com.navneet.executespec.agent.Plan plan,
      Path workspace,
      boolean fromReviewing,
      PlanGateContinuation continuation) {
    Instant startedAt = Instant.now();
    boolean localRun = false;
    RunLlmOperationLifecycleService.PhaseLease operation = null;
    try {
      // Re-resolve BYOK keys for this resumed run — same pattern as runPipeline().
      try {
        PipelineRun pre = runRepo.findById(runId).orElse(null);
        if (pre != null && pre.getCreatedByUserId() != null) {
          keyContextStore.resolveAndPut(runId, pre.getCreatedByUserId());
        }
      } catch (Exception keyEx) {
        log.warn("resume run={} BYOK resolution failed: {}", runId, keyEx.getMessage());
      }

      ExecutionContext rootCtx = durableRunRootContext(runId, workspace);

      PipelineRun row = runRepo.findById(runId).orElse(null);
      if (row == null) {
        log.warn("resume run={} row vanished mid-resume", runId);
        return;
      }
      bindRunModelOverrides(row);
      // Local-hands intent is durable; never fall back to server-side tools after a JVM restart.
      // A missing client therefore remains a bounded remote-tool wait/error, not an unsafe shell
      // execution. The feature flag is still an operator kill switch.
      boolean localHandsEnabled = featureFlagService.isEnabled("worker.local-hands.enabled");
      if (row.isLocalExecutionRequested() && !localHandsEnabled) {
        log.error(
            "resume run={} local-hands intent cannot be honoured: feature flag disabled", runId);
        controlPort.terminate(runId, "FAILED", "LOCAL_HANDS_DISABLED", startedAt);
        return;
      }
      localRun = row.isLocalExecutionRequested() && localHandsEnabled;
      if (continuation != null && continuation.localRun() != row.isLocalExecutionRequested()) {
        throw new IllegalStateException("Persisted plan continuation local-run intent mismatch");
      }
      if (localRun) {
        remoteToolDispatcher.registerAfterWorkerRestart(runId);
        log.info("resume run={} restoring LOCAL-hANDS transport; awaiting client reconnect", runId);
      }
      String provider = row.getAiProvider() == null ? "anthropic" : row.getAiProvider();
      String model =
          continuation != null && continuation.pinnedModel() != null
              ? continuation.pinnedModel()
              : row.isRoutePinned() ? row.getRequestedModel() : row.getWorkerModel();
      String upperProvider = provider.toUpperCase();
      String projectId = row.getProjectId();
      List<UUID> selectedRepoIds = deserializeRepoIds(row.getSelectedRepoIds());

      ExecutionResult execResult = null;
      RunCostTracker.Snapshot afterExec = null;
      if (!fromReviewing) {
        operation = requireRunOperationLifecycle().claimExecution(rootCtx, runId, provider, model);
        ExecutionContext executionContext = operation.context();
        persistenceWriter.updateStatus(runId, "WORKING");
        try {
          execResult =
              executor.execute(
                  ExecutionRequest.builder()
                      .plan(plan)
                      .provider(provider)
                      .model(model)
                      .runContext(executionContext)
                      .maxIterationsPerTask(null)
                      .enableSmokeTests(!localRun)
                      .localHandsMode(localRun)
                      .requireConfirmationBeforeExpensiveContinuation(
                          continuation != null && continuation.requireContinuationConfirmation())
                      .requireBenchmarkPriorForAutomaticContinuation(
                          continuation != null && continuation.requireContinuationBenchmarkPrior())
                      .routePinned(row.isRoutePinned())
                      .lowComplexityWorkerStartTier(
                          continuation == null
                              ? null
                              : continuation.lowComplexityWorkerStartTierOverride())
                      .originalGoal(row.getUserRequest())
                      .designBlock(null) // not persisted on the row — runs without it on resume
                      .projectId(projectId)
                      .workspaceSummary(
                          contextComposer.buildMultiRepoSummary(projectId, selectedRepoIds))
                      .build(),
                  new com.navneet.executespec.harness.SequentialExecutor.ResumeRequest(null));
        } catch (Exception e) {
          if (Thread.currentThread().isInterrupted() || pool.isShuttingDown()) {
            log.info(
                "resume run={} executor interrupted for application shutdown; preserving WORKING"
                    + " checkpoint",
                runId);
            return;
          }
          log.error("resume run={} executor failed", runId, e);
          controlPort.terminate(
              runId,
              "FAILED",
              RunOrchestrator.INTERNAL_ERROR_REASON,
              RunOrchestrator.INTERNAL_ERROR_REASON,
              startedAt);
          return;
        }
        persistenceWriter.applyExecutionResultToTasks(runId, execResult);
        operation = requireRunOperationLifecycle().completeAndClear(operation);
        afterExec = costTracker.snapshot(runId);
      }

      if (localRun) {
        // Nothing server-side reviewed or validated this run: the edits happened on the client and
        // the server workspace is not the delivered artefact. Terminalize with the same honest
        // LOCAL_EXECUTION contract the original submission used instead of running a reviewer over
        // a workspace that does not hold the customer's changes.
        var localTerminal = RunOrchestrator.localExecutionTerminalStatus();
        controlPort.terminate(
            runId,
            localTerminal.status(),
            localTerminal.reason(),
            "resume final status: " + localTerminal.reason(),
            startedAt);
        return;
      }

      persistenceWriter.updateStatus(runId, "REVIEWING");
      // RR-484 — the reviewer is the final authority check and must run on the reviewer's canonical
      // route, not the worker model. `model` above is the WORKER route (requestedModel when pinned,
      // else workerModel); passing it to the reviewer let a cheap/incompatible worker model perform
      // the review after a crash. Resolve the reviewer through the same policy an uninterrupted run
      // uses; RunModelOverrides is already bound (org overrides + exact pin) so a pinned route is
      // still honoured. Fail closed to the worker model only if resolution is impossible.
      String reviewerModel = model;
      try {
        com.navneet.executespec.router.LlmProvider reviewerProviderEnum =
            com.navneet.executespec.router.LlmProvider.valueOf(upperProvider);
        reviewerModel =
            modelResolver
                .resolve("ReviewerAgent", reviewerProviderEnum, AgentTier.PLANNING)
                .getModel();
      } catch (Exception reviewerRouteEx) {
        log.warn(
            "resume run={} reviewer route resolution failed ({}); using worker model {}",
            runId,
            reviewerRouteEx.getMessage(),
            model);
      }
      ReviewerResult reviewResult = null;
      operation =
          requireRunOperationLifecycle().claimReview(rootCtx, runId, provider, reviewerModel);
      ExecutionContext reviewContext = operation.context();
      try {
        reviewResult =
            reviewer.review(
                ReviewerRequest.builder()
                    .provider(provider)
                    .model(reviewerModel)
                    .context(reviewContext)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(false)
                    .originalGoal(row.getUserRequest())
                    .requirements(plan.requirements())
                    .build());
        persistenceWriter.persistReview(runId, reviewResult);
        operation = requireRunOperationLifecycle().completeAndClear(operation);
      } catch (Exception e) {
        if (Thread.currentThread().isInterrupted() || pool.isShuttingDown()) {
          log.info(
              "resume run={} reviewer interrupted for application shutdown; preserving REVIEWING"
                  + " checkpoint",
              runId);
          return;
        }
        log.warn("resume run={} reviewer failed — recording partial review", runId, e);
        persistenceWriter.persistReviewFailure(runId, e);
        operation = requireRunOperationLifecycle().finishReviewAndClear(operation, true);
      }

      contextComposer.ensureReadme(
          runId, workspace, row.getUserRequest(), plan, execResult, reviewResult, startedAt);
      var validationReport =
          validationKickoff.generateValidationReport(runId, execResult, reviewResult, plan);

      String userRequest = row.getUserRequest();
      String prReviewComments = null; // not threaded for resumed runs
      finalizeRunAfterResume(
          runId,
          plan,
          execResult,
          reviewResult,
          validationReport.orElse(null),
          startedAt,
          upperProvider,
          model);
    } catch (Exception e) {
      if (Thread.currentThread().isInterrupted() || pool.isShuttingDown()) {
        log.info(
            "resume run={} unwound for application shutdown; preserving resumable state", runId);
        return;
      }
      log.error("resume run={} pipeline failed unexpectedly", runId, e);
      controlPort.terminate(
          runId,
          "FAILED",
          RunOrchestrator.INTERNAL_ERROR_REASON,
          RunOrchestrator.INTERNAL_ERROR_REASON,
          startedAt);
    } finally {
      if (runOperationLifecycle != null) {
        runOperationLifecycle.closeQuietly(runId, operation);
      }
      RunModelOverrides.clear();
      keyContextStore.evict(runId);
      if (localRun) {
        try {
          remoteToolDispatcher.unregister(runId);
        } catch (Exception ignored) {
          log.debug("resume run={} local-hands unregister failed", runId);
        }
      }
    }
  }

  /**
   * Rehydrates the exact continuation that crossed the approval boundary. Absence is valid only for
   * runs that never had a plan gate; a present but non-COMPLETED/corrupt row fails closed.
   */
  PlanGateContinuation persistedPlanGateContinuation(PipelineRun run) {
    if (planGateResumeStore == null) return null;
    var persisted = planGateResumeStore.completedContinuation(run.getRunId()).orElse(null);
    if (persisted == null) return null;
    if (persisted.handlerContractVersion()
            != PlanGateResumeDispatcher.SUPPORTED_HANDLER_CONTRACT_VERSION
        || persisted.payload() == null
        || persisted.payloadSha256() == null
        || !Objects.equals(
            persisted.payloadSha256(), DecisionEvidenceV1.sha256(persisted.payload()))) {
      throw new IllegalStateException("Persisted plan continuation failed identity validation");
    }
    try {
      PlanGateContinuation continuation =
          objectMapper.readValue(persisted.payload(), PlanGateContinuation.class);
      if (continuation.schemaVersion() != persisted.handlerContractVersion()) {
        throw new IllegalStateException("Persisted plan continuation schema mismatch");
      }
      return continuation;
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Persisted plan continuation is invalid", e);
    }
  }

  /**
   * Rebind the original immutable customer route and budget before resumed run work calls an LLM.
   */
  ExecutionContext durableRunRootContext(String runId, Path workspace) {
    if (llmExecutionPolicyFactory == null) {
      throw new IllegalStateException(
          "LLM execution policy factory unavailable for resumed run work");
    }
    try {
      return new ExecutionContext(
          runId, "__root__", workspace.toRealPath(), llmExecutionPolicyFactory.forRun(runId));
    } catch (java.io.IOException workspaceFailure) {
      throw new IllegalStateException(
          "Run workspace cannot be resolved for durable LLM dispatch: " + runId, workspaceFailure);
    }
  }

  private RunLlmOperationLifecycleService requireRunOperationLifecycle() {
    if (runOperationLifecycle == null) {
      throw new IllegalStateException(
          "Run LLM operation lifecycle is unavailable for retry/recovery work");
    }
    return runOperationLifecycle;
  }

  /**
   * True only when the local-hands transport can actually be rebound. A missing collaborator (the
   * compatibility constructor) or a disabled flag is treated as unavailable, so a local-hands run
   * is refused rather than quietly re-executed server-side.
   */
  private boolean localHandsTransportAvailable() {
    return featureFlagService != null
        && remoteToolDispatcher != null
        && featureFlagService.isEnabled("worker.local-hands.enabled");
  }

  /**
   * Compact terminal-status decision for crash-resumed runs. Mirrors the tail of {@code
   * runPipeline} but works against the already-persisted execResult / reviewerResult.
   */
  private void finalizeRunAfterResume(
      String runId,
      com.navneet.executespec.agent.Plan plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      com.navneet.executespec.report.entity.ValidationReport validationReport,
      Instant startedAt,
      String upperProvider,
      String model) {
    // RR-482 — a REVIEWING-phase crash resume skips the executor, leaving execResult null.
    // Replacing
    // null with a synthetic ALL_SUCCESS/0-tasks result let a run whose worker tasks had actually
    // failed/partial before the crash be finalized COMPLETE on the reviewer alone, contradicting
    // the
    // durable task rows. Reconstruct the real aggregate from the persisted task evidence instead;
    // that helper fails closed (no rows / only failures / only skipped ⇒ FAILED).
    ExecutionResult effectiveExec =
        execResult == null ? persistenceWriter.reconstructExecResultFromTasks(runId) : execResult;
    var terminal = RunOrchestrator.resolveTerminalStatus(effectiveExec, reviewResult);
    if (RunOrchestrator.validationReportProvenanceFailed(validationReport)) {
      terminal = new RunOrchestrator.StatusAndReason("FAILED", "VALIDATION_EVIDENCE_FAILED");
    }
    controlPort.terminate(
        runId,
        terminal.status(),
        terminal.reason(),
        "resume final status: " + terminal.reason(),
        startedAt);
  }

  /**
   * Retry a single task within a terminal V2 run. Re-executes the task (not just a status flip):
   * resets the target to {@code PENDING}, reuses the persisted plan, calls {@code
   * executor.execute(req, ResumeRequest(targetTaskId))} (skips other terminal tasks), then
   * re-resolves the run's terminal status.
   *
   * @throws IllegalArgumentException if the run/task is not found
   * @throws IllegalStateException if the run is not in a retryable state
   */
  public void retryTask(String runId, String taskId) {
    retryTaskInternal(runId, taskId, null);
  }

  /**
   * Approves a previously blocked model/provider continuation proposal for one task, then
   * dispatches the normal retry path with the proposed provider/model. This only accepts proposals
   * that were explicitly stopped by the continuation-confirmation guard; generic blocked tasks
   * still require a normal retry or a fresh run.
   */
  public ContinuationApproval approveContinuation(String runId, String taskId) {
    var task = findTask(runId, taskId);
    ContinuationApproval approval = parseContinuationApproval(task);
    String requestedApprovalId = UUID.randomUUID().toString();
    RetryAdmission admission =
        retryTaskInternal(
            runId,
            taskId,
            new RetryProviderOverride(
                approval.provider(),
                approval.model(),
                approval.proposedAction(),
                approval.benchmarkPriorRequired(),
                approval.postReview(),
                approval.proposalVersion(),
                approval.proposalDigest(),
                requestedApprovalId));
    return new ContinuationApproval(
        approval.provider(),
        approval.model(),
        approval.proposedAction(),
        approval.benchmarkPriorRequired(),
        approval.postReview(),
        approval.proposalVersion(),
        approval.proposalDigest(),
        admission.approvalId(),
        admission.replayed(),
        admission.status());
  }

  private RetryAdmission retryTaskInternal(
      String runId, String taskId, RetryProviderOverride override) {
    var run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    var task = findTask(runId, taskId);
    if (override != null) {
      RetryAdmission replay = replayedContinuationApproval(task, override);
      if (replay != null) {
        return replay;
      }
    }
    String status = run.getStatus();
    if (!java.util.List.of("PARTIAL", "FAILED", "COMPLETE").contains(status)) {
      throw new IllegalStateException(
          "Task retry requires PARTIAL / FAILED / COMPLETE run (current: " + status + ")");
    }
    // Execution locality is run authority, not a per-dispatch default. A run the customer submitted
    // as local-hands must never be retried through the server-side worker/tool path: that would
    // execute commands and mutate files on ExecuteSpec's machine after the customer approved only
    // local execution. Either the local transport is restored (and the retry waits for the client,
    // failing visibly if it never reconnects) or the retry is refused.
    final boolean localRun = run.isLocalExecutionRequested();
    if (localRun && override != null) {
      throw new IllegalStateException(
          "Approved continuation corrections require the server-side checkpoint and validation"
              + " lifecycle; submit a fresh local-hands run instead.");
    }
    if (localRun && !localHandsTransportAvailable()) {
      throw new IllegalStateException(
          "This run executed on your machine (local hands), and local execution is currently"
              + " unavailable — retrying it here would run the task on ExecuteSpec's servers"
              + " instead. Submit a fresh run once local execution is available.");
    }

    final com.navneet.executespec.state.entity.AgentTask taskBeforeCorrection =
        override != null
            ? objectMapper.convertValue(task, com.navneet.executespec.state.entity.AgentTask.class)
            : null;
    final String runStatusBeforeCorrection = run.getStatus();
    final String runReasonBeforeCorrection = run.getStatusReason();
    final Instant runCompletedBeforeCorrection = run.getCompletedAt();
    final String runReviewBeforeCorrection = run.getReviewJson();
    // RR-1564: snapshot the run's already-consumed continuation ledger BEFORE the approved retry is
    // appended, so the seeded escalation counters accumulate prior usage instead of resetting to a
    // constant 1 on every human approval.
    final String priorContinuationActionsJson = task.getContinuationActionsJson();

    java.nio.file.Path workspace =
        java.nio.file.Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(workspace)) {
      throw new IllegalStateException(
          "Workspace gone — retry is same-JVM only. Submit a fresh run instead.");
    }
    String planJson = run.getPlanJson();
    if (planJson == null || planJson.isBlank()) {
      throw new IllegalStateException(
          "Plan JSON missing on the run row — can't retry a single task without it.");
    }
    final com.navneet.executespec.agent.Plan plan;
    try {
      plan = objectMapper.readValue(planJson, com.navneet.executespec.agent.Plan.class);
    } catch (Exception e) {
      throw new IllegalStateException("Plan JSON corrupted: " + e.getMessage());
    }
    String retryProvider =
        override == null
            ? (run.getAiProvider() == null ? "anthropic" : run.getAiProvider())
            : override.provider();
    String retryModel =
        override == null
            ? (run.isRoutePinned() ? run.getRequestedModel() : run.getWorkerModel())
            : override.model();
    if (override != null) {
      validateApprovedContinuationRoute(run, override.provider(), override.model());
    }
    RetryAdmission retryAdmission =
        override == null
            ? RetryAdmission.normalRetry()
            : claimContinuationApproval(runId, task, override);
    if (retryAdmission.replayed()) {
      return retryAdmission;
    }
    ExecutionContext retryBaseContext;
    try {
      retryBaseContext = durableRunRootContext(runId, workspace);
    } catch (RuntimeException contextFailure) {
      // The exact approval CAS above is already durable. A policy/context reconstruction failure
      // happens before an operation lease, provider call, or workspace mutation, so retaining
      // CLAIMED would strand the user until the four-hour stale sweeper runs. Release only this
      // approval id+proposal digest and let the unchanged proposal be retried immediately.
      releaseContinuationApprovalClaimPreservingFailure(task, override, contextFailure);
      throw contextFailure;
    }
    RunLlmOperationLifecycleService.PhaseLease retryLease;
    try {
      retryLease =
          requireRunOperationLifecycle()
              .beginExecutionRetry(retryBaseContext, runId, retryProvider, retryModel);
    } catch (RuntimeException admissionFailure) {
      releaseContinuationApprovalClaimPreservingFailure(task, override, admissionFailure);
      throw admissionFailure;
    }

    log.info(
        "RunOrchestrator retryTask: run={} task={} override={}/{} dispatching real re-execution"
            + " via SequentialExecutor.execute(req, ResumeRequest)",
        runId,
        taskId,
        override == null ? "-" : override.provider(),
        override == null ? "-" : override.model());

    task.setStatus("PENDING");
    task.setStartedAt(null);
    task.setCompletedAt(null);
    taskRepo.save(task);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "PENDING"));

    String prevRunStatus = runStatusBeforeCorrection;
    run.setStatus("WORKING");
    run.setCompletedAt(null);
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
    broadcaster.emit(
        runId, RunEvent.log(runId, "retry", retryMessage(taskId, prevRunStatus, override)));

    final String fullTargetTaskId = task.getTaskId(); // already prefixed with runId-short
    AtomicReference<FutureTask<Void>> registeredRetry = new AtomicReference<>();
    AtomicReference<RunLlmOperationLifecycleService.PhaseLease> retryOperation =
        new AtomicReference<>(retryLease);
    FutureTask<Void> future =
        new FutureTask<>(
            () -> {
              CorrectionWorkspaceTransaction.Checkpoint correctionCheckpoint = null;
              ValidationEvidenceCheckpoint correctionEvidenceCheckpoint = null;
              CorrectionWorkspaceTransaction.DiffEvidence correctionDiff = null;
              try {
                if (override != null) {
                  assertContinuationProposalUnchanged(runId, taskId, override);
                }
                PipelineRun row = runRepo.findById(runId).orElse(null);
                String provider =
                    row == null || row.getAiProvider() == null ? "anthropic" : row.getAiProvider();
                String model =
                    row == null
                        ? null
                        : row.isRoutePinned() ? row.getRequestedModel() : row.getWorkerModel();
                if (override != null) {
                  if (row != null && row.isRoutePinned()) {
                    throw new IllegalStateException(
                        "An exact-route run cannot approve a different continuation route.");
                  }
                  provider = override.provider();
                  model = override.model();
                }
                bindRunModelOverrides(row);
                if (row != null && row.getCreatedByUserId() != null) {
                  keyContextStore.resolveAndPut(runId, row.getCreatedByUserId());
                }
                ContinuationUsage approvedUsage =
                    continuationUsageFor(row, override, priorContinuationActionsJson);
                if (localRun) {
                  remoteToolDispatcher.registerAfterWorkerRestart(runId);
                  log.info(
                      "retry run={} restoring LOCAL-HANDS transport; awaiting client reconnect",
                      runId);
                }

                ExecutionContext rootCtx = retryOperation.get().context();
                if (!localRun && override != null) {
                  correctionCheckpoint =
                      correctionWorkspaceTransaction.begin(rootCtx.workspaceRoot());
                  correctionEvidenceCheckpoint = invalidateValidationEvidence(runId);
                }
                List<UUID> selectedRepoIds =
                    row == null ? List.of() : deserializeRepoIds(row.getSelectedRepoIds());
                RunCostTracker.Snapshot beforeRetry = costTracker.snapshot(runId);
                String logicalTargetTaskId = logicalTaskId(runId, fullTargetTaskId);
                RunEffectiveRouteService.AttemptFence approvedRouteFence =
                    override == null || effectiveRoutes == null
                        ? null
                        : effectiveRoutes.captureAttemptFence(
                            runId,
                            logicalTargetTaskId,
                            beforeRetry == null ? 0L : beforeRetry.calls());

                ExecutionResult result =
                    executor.execute(
                        ExecutionRequest.builder()
                            .plan(plan)
                            .provider(provider)
                            .model(model)
                            .runContext(rootCtx)
                            .maxIterationsPerTask(null)
                            .enableSmokeTests(!localRun)
                            .localHandsMode(localRun)
                            .routePinned(row != null && row.isRoutePinned())
                            .userConfirmedExpensiveContinuation(override != null)
                            .userConfirmedContinuationAction(
                                override == null ? null : override.proposedAction())
                            .userConfirmedContinuationProvider(
                                override == null ? null : override.provider())
                            .userConfirmedContinuationModel(
                                override == null ? null : override.model())
                            .requireBenchmarkPriorForAutomaticContinuation(
                                override == null ? null : override.benchmarkPriorRequired())
                            .initialProviderSwitchesUsed(approvedUsage.providerSwitches())
                            .initialModelStepUpsUsed(approvedUsage.modelStepUps())
                            .initialModelStepDownsUsed(approvedUsage.modelStepDowns())
                            .originalGoal(row == null ? null : row.getUserRequest())
                            .designBlock(null)
                            .projectId(row == null ? null : row.getProjectId())
                            .workspaceSummary(
                                contextComposer.buildMultiRepoSummary(
                                    row == null ? null : row.getProjectId(), selectedRepoIds))
                            .build(),
                        new com.navneet.executespec.harness.SequentialExecutor.ResumeRequest(
                            fullTargetTaskId,
                            override == null ? null : override.provider(),
                            override == null ? null : override.model(),
                            override == null ? null : override.proposedAction(),
                            override == null ? null : override.proposalDigest()));
                if (override != null && brownfieldHttpContractGuard.hasBaseline(runId)) {
                  brownfieldHttpContractGuard.assertCompatible(runId);
                }
                RunEffectiveRouteService.AttemptRouteEvidence approvedWorkerAttempt = null;
                if (override != null) {
                  approvedWorkerAttempt =
                      assertApprovedContinuationRoute(
                          runId,
                          fullTargetTaskId,
                          override.provider(),
                          override.model(),
                          result,
                          approvedRouteFence);
                  persistApprovedAttemptEvidence(
                      fullTargetTaskId,
                      override.proposalDigest(),
                      "workerAttempt",
                      approvedWorkerAttempt,
                      approvedRouteFence.callIndexFloor());
                }
                requireRunOperationLifecycle().complete(retryOperation.getAndSet(null));
                RunCostTracker.Snapshot afterRetry = costTracker.snapshot(runId);
                // An approved continuation is attributed from the same fenced physical-attempt
                // evidence that admitted it. Requested provider/model strings are only a fallback
                // for ordinary, non-approved retries; they must never become the accounting source
                // for a human-approved exact route.
                String attributedWorkerProvider =
                    approvedWorkerAttempt == null
                        ? provider
                        : approvedWorkerAttempt.route().provider();
                String attributedWorkerModel =
                    approvedWorkerAttempt == null ? model : approvedWorkerAttempt.route().model();
                tokenAttributor.attributeWorkerAdditionalTask(
                    runId,
                    fullTargetTaskId,
                    afterRetry.inputTokens() - beforeRetry.inputTokens(),
                    afterRetry.outputTokens() - beforeRetry.outputTokens(),
                    attributedWorkerProvider.toUpperCase(java.util.Locale.ROOT),
                    attributedWorkerModel);

                String preReviewerGeneration =
                    correctionCheckpoint == null
                        ? null
                        : correctionWorkspaceTransaction.currentGeneration(correctionCheckpoint);
                if (correctionCheckpoint != null) {
                  correctionValidationAdmission.assertPreReview(
                      runId,
                      plan,
                      row == null ? null : row.getUserRequest(),
                      rootCtx.workspaceRoot());
                }
                ApprovedReviewerPass approvedReviewerPass =
                    !localRun && override != null
                        ? runReviewerAfterApprovedPostReviewContinuation(
                            runId, plan, rootCtx, provider, model, result, afterRetry)
                        : null;
                ReviewerResult reviewResult =
                    approvedReviewerPass == null ? null : approvedReviewerPass.result();
                if (approvedReviewerPass != null) {
                  persistApprovedAttemptEvidence(
                      fullTargetTaskId,
                      override.proposalDigest(),
                      "reviewerAttempt",
                      approvedReviewerPass.attempt(),
                      approvedReviewerPass.callIndexFloor());
                }
                if (correctionCheckpoint != null) {
                  String postReviewerGeneration =
                      correctionWorkspaceTransaction.currentGeneration(correctionCheckpoint);
                  if (!preReviewerGeneration.equals(postReviewerGeneration)) {
                    throw new IllegalStateException(
                        "CORRECTION_REVIEWER_MUTATED_WORKSPACE: read-only reviewer changed the"
                            + " correction generation");
                  }
                  if (brownfieldHttpContractGuard.hasBaseline(runId)) {
                    brownfieldHttpContractGuard.assertCompatible(runId);
                  }
                  correctionDiff =
                      correctionWorkspaceTransaction.diffEvidence(correctionCheckpoint);
                  com.navneet.executespec.report.model.WorkflowConfigEvidence correctionWorkflow =
                      workflowConfigEvidenceInspector.inspect(rootCtx.workspaceRoot());
                  CorrectionAcceptancePolicy.Decision acceptance =
                      CorrectionAcceptancePolicy.evaluate(
                          taskId,
                          result,
                          reviewResult,
                          correctionWorkflow,
                          correctionCheckpoint.generation(),
                          postReviewerGeneration);
                  if (!acceptance.accepted()) {
                    CorrectionResolutionEvidence.ValidationSnapshot attemptValidation =
                        CorrectionResolutionEvidence.attemptSnapshot(
                            postReviewerGeneration,
                            result,
                            reviewResult,
                            correctionWorkflow,
                            inspectCorrectionContracts(runId));
                    CorrectionResolutionEvidence.ValidationDelta correctionValidationDelta =
                        captureValidationDelta(correctionEvidenceCheckpoint, attemptValidation);
                    correctionWorkspaceTransaction.rollback(correctionCheckpoint);
                    restoreValidationEvidence(correctionEvidenceCheckpoint);
                    restoreRejectedCorrection(
                        runId,
                        taskBeforeCorrection,
                        runStatusBeforeCorrection,
                        runReasonBeforeCorrection,
                        runCompletedBeforeCorrection,
                        runReviewBeforeCorrection,
                        acceptance.code(),
                        acceptance.reason(),
                        correctionCheckpoint.generation(),
                        correctionDiff,
                        correctionValidationDelta);
                    broadcaster.emit(
                        runId,
                        RunEvent.log(
                            runId,
                            taskId,
                            "Approved correction rolled back: "
                                + acceptance.code()
                                + " — "
                                + acceptance.reason()));
                    updateContinuationApprovalStatus(task, override, "REJECTED");
                    return null;
                  }
                  correctionValidationAdmission.regenerateAndAssert(
                      runId,
                      result,
                      reviewResult,
                      plan,
                      correctionCheckpoint,
                      postReviewerGeneration);
                  CorrectionResolutionEvidence.ValidationDelta correctionValidationDelta =
                      captureValidationDelta(correctionEvidenceCheckpoint);
                  CorrectionAcceptancePolicy.Decision comparativeAdmission =
                      CorrectionAcceptancePolicy.evaluateRegression(correctionValidationDelta);
                  if (!comparativeAdmission.accepted()) {
                    correctionWorkspaceTransaction.rollback(correctionCheckpoint);
                    restoreValidationEvidence(correctionEvidenceCheckpoint);
                    restoreRejectedCorrection(
                        runId,
                        taskBeforeCorrection,
                        runStatusBeforeCorrection,
                        runReasonBeforeCorrection,
                        runCompletedBeforeCorrection,
                        runReviewBeforeCorrection,
                        comparativeAdmission.code(),
                        comparativeAdmission.reason(),
                        correctionCheckpoint.generation(),
                        correctionDiff,
                        correctionValidationDelta);
                    updateContinuationApprovalStatus(task, override, "REJECTED");
                    return null;
                  }
                  CorrectionWorkspaceTransaction.Accepted generations =
                      correctionWorkspaceTransaction.accept(correctionCheckpoint);
                  appendCorrectionResolution(
                      fullTargetTaskId,
                      "ACCEPTED",
                      acceptance.code(),
                      acceptance.reason(),
                      generations.beforeGeneration(),
                      generations.afterGeneration(),
                      correctionDiff,
                      correctionValidationDelta,
                      true);
                }
                persistenceWriter.applyExecutionResultToTasks(runId, result);
                if (reviewResult != null) {
                  persistenceWriter.persistReview(runId, reviewResult);
                }

                // A local-hands retry never produced server-verified evidence: no server smoke, no
                // reviewer, no validation. It terminalizes exactly like the original local run.
                var terminal =
                    localRun
                        ? RunOrchestrator.localExecutionTerminalStatus()
                        : RunOrchestrator.resolveTerminalStatus(result, reviewResult);
                controlPort.terminate(
                    runId,
                    terminal.status(),
                    terminal.reason(),
                    (override != null
                            ? "approved continuation final status: "
                            : "retry final status: ")
                        + terminal.reason(),
                    null);
                updateContinuationApprovalStatus(task, override, "COMPLETED");
              } catch (Exception e) {
                if (correctionCheckpoint != null) {
                  try {
                    if (correctionDiff == null) {
                      try {
                        correctionDiff =
                            correctionWorkspaceTransaction.diffEvidence(correctionCheckpoint);
                      } catch (RuntimeException diffFailure) {
                        e.addSuppressed(diffFailure);
                      }
                    }
                    String failedGeneration;
                    try {
                      failedGeneration =
                          correctionWorkspaceTransaction.currentGeneration(correctionCheckpoint);
                    } catch (RuntimeException generationFailure) {
                      e.addSuppressed(generationFailure);
                      failedGeneration = correctionCheckpoint.generation();
                    }
                    CorrectionResolutionEvidence.ValidationDelta correctionValidationDelta =
                        captureValidationDelta(
                            correctionEvidenceCheckpoint,
                            CorrectionResolutionEvidence.failedAttemptSnapshot(
                                failedGeneration,
                                e.getMessage(),
                                inspectCorrectionContracts(runId)));
                    correctionWorkspaceTransaction.rollback(correctionCheckpoint);
                    restoreValidationEvidence(correctionEvidenceCheckpoint);
                    restoreRejectedCorrection(
                        runId,
                        taskBeforeCorrection,
                        runStatusBeforeCorrection,
                        runReasonBeforeCorrection,
                        runCompletedBeforeCorrection,
                        runReviewBeforeCorrection,
                        "CORRECTION_EXECUTION_FAILED",
                        e.getMessage(),
                        correctionCheckpoint.generation(),
                        correctionDiff,
                        correctionValidationDelta);
                    log.warn(
                        "Approved correction failed and was rolled back run={} task={}: {}",
                        runId,
                        taskId,
                        e.getMessage());
                    updateContinuationApprovalStatus(task, override, "REJECTED");
                    return null;
                  } catch (RuntimeException rollbackFailure) {
                    rollbackFailure.addSuppressed(e);
                    e = rollbackFailure;
                  }
                }
                log.error(
                    "RunOrchestrator retryTask dispatch failed run={} task={}", runId, taskId, e);
                controlPort.terminate(
                    runId,
                    "FAILED",
                    RunOrchestrator.INTERNAL_ERROR_REASON,
                    RunOrchestrator.INTERNAL_ERROR_REASON,
                    null);
                updateContinuationApprovalStatus(task, override, "FAILED");
              } finally {
                if (runOperationLifecycle != null) {
                  runOperationLifecycle.closeQuietly(runId, retryOperation.getAndSet(null));
                }
                RunModelOverrides.clear();
                keyContextStore.evict(runId);
                if (localRun) {
                  try {
                    remoteToolDispatcher.unregister(runId);
                  } catch (Exception ignored) {
                    log.debug("retry run={} local-hands unregister failed", runId);
                  }
                }
                controlPort.removeActiveRun(runId, registeredRetry.get());
              }
              return null;
            });
    registeredRetry.set(future);
    try {
      if (override != null) {
        appendApprovedContinuationAction(task, override);
      }
      controlPort.putActiveRun(runId, future);
      updateContinuationApprovalStatus(task, override, "QUEUED");
      if (override != null && !"QUEUED".equals(task.getContinuationApprovalStatus())) {
        throw new IllegalStateException(
            "Continuation approval lost its durable claim before queueing; retry the approval.");
      }
      pool.executor().execute(future);
    } catch (RuntimeException dispatchFailure) {
      controlPort.removeActiveRun(runId, future);
      requireRunOperationLifecycle().fail(retryOperation.getAndSet(null), "RETRY_DISPATCH_FAILED");
      restoreRetryAdmissionState(
          task,
          taskBeforeCorrection,
          run,
          runStatusBeforeCorrection,
          runReasonBeforeCorrection,
          runCompletedBeforeCorrection);
      releaseContinuationApprovalClaimPreservingFailure(task, override, dispatchFailure);
      throw dispatchFailure;
    }
    return override == null
        ? retryAdmission
        : new RetryAdmission(retryAdmission.approvalId(), false, "QUEUED");
  }

  private ApprovedReviewerPass runReviewerAfterApprovedPostReviewContinuation(
      String runId,
      Plan plan,
      ExecutionContext rootCtx,
      String approvedProvider,
      String approvedModel,
      ExecutionResult result,
      RunCostTracker.Snapshot beforeReview) {
    persistenceWriter.updateStatus(runId, "REVIEWING");
    RunOrchestrator.ProviderModel reviewerRoute =
        RunOrchestrator.reviewerRouteFor(approvedProvider, approvedModel, result);
    RunLlmOperationLifecycleService.PhaseLease reviewOperation =
        requireRunOperationLifecycle()
            .beginReviewRetry(rootCtx, runId, reviewerRoute.provider(), reviewerRoute.model());
    if (effectiveRoutes == null) {
      requireRunOperationLifecycle().fail(reviewOperation, "REVIEW_ROUTE_EVIDENCE_UNAVAILABLE");
      throw new IllegalStateException(
          "Approved correction reviewer requires physical route evidence");
    }
    long callIndexFloor = beforeReview == null ? 0L : beforeReview.calls();
    RunEffectiveRouteService.ReviewerAttemptFence reviewerFence =
        effectiveRoutes.captureReviewerAttemptFence(runId, callIndexFloor);
    try {
      ReviewerResult reviewResult =
          reviewer.review(
              ReviewerRequest.builder()
                  .provider(reviewerRoute.provider())
                  .model(reviewerRoute.model())
                  .strictProvider(true)
                  .context(reviewOperation.context())
                  .buildCommand(plan.buildCommand())
                  .skipWiringPass(false)
                  .readOnly(true)
                  .originalGoal(
                      runRepo.findById(runId).map(PipelineRun::getUserRequest).orElse(null))
                  .requirements(plan.requirements())
                  .build());
      RunCostTracker.Snapshot afterReview = costTracker.snapshot(runId);
      RunEffectiveRouteService.AttemptRouteEvidence reviewerAttempt =
          effectiveRoutes.successfulReviewerAttemptAfter(reviewerFence);
      if (!reviewerAttempt.isExact(reviewerRoute.provider(), reviewerRoute.model())) {
        throw new IllegalStateException(
            "Approved continuation reviewer physical route mismatch: expected "
                + reviewerRoute.provider()
                + "/"
                + reviewerRoute.model()
                + " but ledger recorded "
                + reviewerAttempt.route().attribution()
                + " "
                + reviewerAttempt.route().provider()
                + "/"
                + reviewerAttempt.route().model());
      }
      requireRunOperationLifecycle().complete(reviewOperation);
      reviewOperation = null;
      tokenAttributor.attributeReviewerAdditionalFromAttempt(
          runId,
          afterReview.inputTokens() - beforeReview.inputTokens(),
          afterReview.outputTokens() - beforeReview.outputTokens(),
          reviewerAttempt);
      return new ApprovedReviewerPass(reviewResult, reviewerAttempt, callIndexFloor);
    } catch (Exception e) {
      log.warn(
          "approved post-review continuation reviewer failed run={}: {}", runId, e.getMessage());
      requireRunOperationLifecycle().fail(reviewOperation, "REVIEW_FAILED");
      reviewOperation = null;
      return new ApprovedReviewerPass(
          RunOrchestrator.reviewerFailedResult(e),
          RunEffectiveRouteService.AttemptRouteEvidence.UNATTRIBUTED,
          callIndexFloor);
    } finally {
      if (runOperationLifecycle != null) {
        runOperationLifecycle.closeQuietly(runId, reviewOperation);
      }
    }
  }

  /**
   * A continuation approval is an exact route contract. Check both the executor's requested route
   * and, when the physical-attempt ledger is available, the latest provider response before any
   * attribution or reviewer attestation can describe the retry as approved work.
   */
  private RunEffectiveRouteService.AttemptRouteEvidence assertApprovedContinuationRoute(
      String runId,
      String targetTaskId,
      String approvedProvider,
      String approvedModel,
      ExecutionResult result,
      RunEffectiveRouteService.AttemptFence attemptFence) {
    String logicalTaskId = logicalTaskId(runId, targetTaskId);
    var target =
        result == null
            ? null
            : result.perTask().stream()
                .filter(outcome -> Objects.equals(logicalTaskId, outcome.taskId()))
                .findFirst()
                .orElse(null);
    if (target == null
        || !approvedProvider.equalsIgnoreCase(target.dispatchedProvider())
        || !approvedModel.equals(target.dispatchedModel())) {
      throw new IllegalStateException(
          "Approved continuation route mismatch before attribution/review: expected "
              + approvedProvider
              + "/"
              + approvedModel
              + " but executor dispatched "
              + (target == null
                  ? "no target outcome"
                  : target.dispatchedProvider() + "/" + target.dispatchedModel()));
    }
    if (effectiveRoutes == null) {
      throw new IllegalStateException(
          "Approved continuation route evidence is unavailable before attribution/review");
    }
    RunEffectiveRouteService.AttemptRouteEvidence physical =
        effectiveRoutes.successfulTaskAttemptAfter(attemptFence);
    if (!physical.isExact(approvedProvider, approvedModel)) {
      throw new IllegalStateException(
          "Approved continuation physical route mismatch before attribution/review: expected "
              + approvedProvider
              + "/"
              + approvedModel
              + " but ledger recorded "
              + physical.route().attribution()
              + " "
              + physical.route().provider()
              + "/"
              + physical.route().model());
    }
    return physical;
  }

  private record ApprovedReviewerPass(
      ReviewerResult result,
      RunEffectiveRouteService.AttemptRouteEvidence attempt,
      long callIndexFloor) {}

  static String logicalTaskId(String runId, String targetTaskId) {
    if (targetTaskId == null) {
      return null;
    }
    String prefix = runId != null && runId.length() >= 8 ? runId.substring(0, 8) + "_" : "";
    return !prefix.isBlank() && targetTaskId.startsWith(prefix)
        ? targetTaskId.substring(prefix.length())
        : targetTaskId;
  }

  private void bindRunModelOverrides(PipelineRun run) {
    if (run == null) {
      RunModelOverrides.clear();
      return;
    }
    java.util.Map<String, String> orgOverrides =
        orgModelOverrideService == null
            ? java.util.Map.of()
            : orgModelOverrideService.overridesForRun(run.getOrgId());
    LlmProvider pinProvider = null;
    String pinnedModel = null;
    if (run.isRoutePinned()) {
      if (run.getRequestedProvider() == null
          || run.getRequestedModel() == null
          || run.getRequestedModel().isBlank()) {
        throw new IllegalStateException(
            "Persisted exact route is incomplete for run " + run.getRunId());
      }
      pinProvider = LlmProvider.valueOf(run.getRequestedProvider().toUpperCase());
      pinnedModel =
          modelRegistry == null
              ? run.getRequestedModel()
              : modelRegistry.canonicalize(run.getRequestedModel());
      if (modelRegistry != null) {
        String owner = modelRegistry.providerFor(pinnedModel);
        if (owner == null
            || !com.navneet.executespec.router.ModelAccessService.canRouteModel(
                pinProvider, pinnedModel, owner)) {
          throw new IllegalStateException(
              "Persisted exact route is not dispatchable: " + pinProvider + "/" + pinnedModel);
        }
      }
    }
    RunModelOverrides.bind(RunModelOverrides.withRunPin(orgOverrides, pinProvider, pinnedModel));
  }

  private com.navneet.executespec.state.entity.AgentTask findTask(String runId, String taskId) {
    if (runId == null || runId.length() < 8 || taskId == null || taskId.isBlank()) {
      throw new IllegalArgumentException("Task not found: " + taskId);
    }
    String runPrefix = runId.substring(0, 8) + "_";
    String key = taskId.startsWith(runPrefix) ? taskId : runPrefix + taskId;
    return taskRepo
        .findById(key)
        .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
  }

  private ContinuationApproval parseContinuationApproval(
      com.navneet.executespec.state.entity.AgentTask task) {
    String json = task.getContinuationDecisionJson();
    if (json == null || json.isBlank()) {
      throw new IllegalStateException("No continuation proposal is recorded for this task.");
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      String decision = root.path("decision").asText("");
      String source = root.path("policy").path("source").asText(root.path("source").asText(""));
      boolean explicitConfirmationRequired =
          root.path("policy").path("requireConfirmationBeforeExpensiveContinuation").asBoolean();
      JsonNode candidate = root.path("selectedCandidate");
      String provider = candidate.path("provider").asText("");
      String model = candidate.path("model").asText("");
      RunContinuationDecision proposedAction = proposedContinuationAction(root);
      boolean benchmarkPriorRequired =
          root.path("policy").path("requireBenchmarkPriorForAutomaticContinuation").asBoolean();
      boolean candidateHasBenchmarkPrior =
          candidate.path("benchmarkQualityPriorAvailable").asBoolean(false);
      boolean benchmarkConfidenceBlocked = benchmarkPriorRequired && !candidateHasBenchmarkPrior;
      if (!"STOP_BLOCKED".equals(decision)
          || (!explicitConfirmationRequired && !benchmarkConfidenceBlocked)) {
        throw new IllegalStateException(
            "Continuation approval requires an approval-blocked continuation proposal.");
      }
      if (provider.isBlank() || model.isBlank()) {
        throw new IllegalStateException(
            "Continuation approval requires a proposed provider and model.");
      }
      int proposalVersion = root.path("schemaVersion").asInt(1);
      return new ContinuationApproval(
          provider,
          model,
          proposedAction,
          benchmarkPriorRequired,
          heavyContinuationApprovalSource(source),
          proposalVersion,
          continuationProposalDigest(proposalVersion, json),
          null,
          false,
          null);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Continuation proposal JSON is corrupted: " + e.getMessage());
    }
  }

  static boolean heavyContinuationApprovalSource(String source) {
    return "POST_REVIEW".equalsIgnoreCase(source)
        || "POST_VALIDATION_REPORT".equalsIgnoreCase(source);
  }

  private void assertContinuationProposalUnchanged(
      String runId, String taskId, RetryProviderOverride override) {
    AgentTask current = findTask(runId, taskId);
    String currentJson = current.getContinuationDecisionJson();
    String currentDigest = continuationProposalDigest(override.proposalVersion(), currentJson);
    if (!override.proposalDigest().equals(currentDigest)) {
      throw new IllegalStateException(
          "Approved continuation proposal changed before dispatch; submit a new approval.");
    }
  }

  private RetryAdmission claimContinuationApproval(
      String runId, AgentTask task, RetryProviderOverride override) {
    String proposalJson = task.getContinuationDecisionJson();
    String currentDigest = continuationProposalDigest(override.proposalVersion(), proposalJson);
    if (!override.proposalDigest().equals(currentDigest)) {
      throw new IllegalStateException(
          "Continuation proposal changed before approval; submit a new approval.");
    }
    String action =
        override.proposedAction() == null ? "UNKNOWN" : override.proposedAction().name();
    int claimed =
        taskRepo.claimContinuationApproval(
            task.getTaskId(),
            runId,
            proposalJson,
            override.proposalDigest(),
            override.approvalId(),
            override.provider(),
            override.model(),
            action,
            override.postReview());
    if (claimed == 1) {
      // Repository bulk updates clear the persistence context. Mirror the exact durable claim on
      // the detached test/runtime object so the subsequent action-history save cannot null it.
      task.setContinuationApprovalId(override.approvalId());
      task.setContinuationApprovalProposalDigest(override.proposalDigest());
      task.setContinuationApprovalStatus("CLAIMED");
      task.setContinuationApprovalProvider(override.provider());
      task.setContinuationApprovalModel(override.model());
      task.setContinuationApprovalAction(action);
      task.setContinuationApprovalPostReview(override.postReview());
      return new RetryAdmission(override.approvalId(), false, "CLAIMED");
    }

    AgentTask current = findTask(runId, task.getTaskId());
    RetryAdmission replay = replayedContinuationApproval(current, override);
    if (replay != null) {
      return replay;
    }
    String persistedDigest = current.getContinuationApprovalProposalDigest();
    if (persistedDigest != null && !persistedDigest.isBlank()) {
      throw new IllegalStateException(
          "A different continuation proposal is already admitted for this task.");
    }
    throw new IllegalStateException(
        "Continuation proposal changed while approval was being admitted; submit a new approval.");
  }

  private RetryAdmission replayedContinuationApproval(
      AgentTask task, RetryProviderOverride override) {
    if (task == null
        || override == null
        || !override.proposalDigest().equals(task.getContinuationApprovalProposalDigest())) {
      return null;
    }
    String expectedAction =
        override.proposedAction() == null ? "UNKNOWN" : override.proposedAction().name();
    if (!Objects.equals(override.provider(), task.getContinuationApprovalProvider())
        || !Objects.equals(override.model(), task.getContinuationApprovalModel())
        || !Objects.equals(expectedAction, task.getContinuationApprovalAction())
        || !Objects.equals(override.postReview(), task.getContinuationApprovalPostReview())
        || task.getContinuationApprovalId() == null
        || task.getContinuationApprovalId().isBlank()) {
      throw new IllegalStateException(
          "Persisted continuation approval conflicts with the exact proposal route.");
    }
    String status = task.getContinuationApprovalStatus();
    if ("CLAIMED".equals(status)) {
      throw new IllegalStateException(
          "This exact continuation approval is still being admitted; retry after it is queued.");
    }
    if ("FAILED".equals(status) || "REJECTED".equals(status)) {
      throw new IllegalStateException(
          "This continuation approval is "
              + status.toLowerCase(java.util.Locale.ROOT)
              + "; submit a new continuation proposal.");
    }
    if (!"QUEUED".equals(status) && !"COMPLETED".equals(status)) {
      throw new IllegalStateException(
          "Persisted continuation approval has an invalid replay status.");
    }
    return new RetryAdmission(task.getContinuationApprovalId(), true, status);
  }

  private void updateContinuationApprovalStatus(
      AgentTask task, RetryProviderOverride override, String status) {
    if (task == null || override == null) {
      return;
    }
    int updated =
        taskRepo.updateContinuationApprovalStatus(
            task.getTaskId(), override.approvalId(), override.proposalDigest(), status);
    if (updated == 1) {
      task.setContinuationApprovalStatus(status);
    }
  }

  private void releaseContinuationApprovalClaim(AgentTask task, RetryProviderOverride override) {
    if (task == null || override == null) {
      return;
    }
    int released =
        taskRepo.releaseContinuationApprovalClaim(
            task.getTaskId(), override.approvalId(), override.proposalDigest());
    if (released != 1) {
      throw new IllegalStateException(
          "CONTINUATION_APPROVAL_CLAIM_RELEASE_FAILED: expected one exact claimed/queued receipt"
              + " for task "
              + task.getTaskId()
              + " but released "
              + released);
    }
    task.setContinuationApprovalId(null);
    task.setContinuationApprovalProposalDigest(null);
    task.setContinuationApprovalStatus(null);
    task.setContinuationApprovalProvider(null);
    task.setContinuationApprovalModel(null);
    task.setContinuationApprovalAction(null);
    task.setContinuationApprovalPostReview(null);
  }

  private void releaseContinuationApprovalClaimPreservingFailure(
      AgentTask task, RetryProviderOverride override, RuntimeException originalFailure) {
    try {
      releaseContinuationApprovalClaim(task, override);
    } catch (RuntimeException releaseFailure) {
      originalFailure.addSuppressed(releaseFailure);
      log.error(
          "Continuation approval claim release failed after pre-queue failure task={} approval={}",
          task == null ? "-" : task.getTaskId(),
          override == null ? "-" : override.approvalId(),
          releaseFailure);
    }
  }

  private void restoreRetryAdmissionState(
      AgentTask task,
      AgentTask taskBefore,
      PipelineRun run,
      String runStatus,
      String runReason,
      Instant runCompletedAt) {
    if (task != null && taskBefore != null) {
      task.setStatus(taskBefore.getStatus());
      task.setStartedAt(taskBefore.getStartedAt());
      task.setCompletedAt(taskBefore.getCompletedAt());
      task.setContinuationActionsJson(taskBefore.getContinuationActionsJson());
      taskRepo.saveAndFlush(task);
    }
    if (run != null) {
      run.setStatus(runStatus);
      run.setStatusReason(runReason);
      run.setCompletedAt(runCompletedAt);
      runRepo.saveAndFlush(run);
    }
  }

  private void validateApprovedContinuationRoute(PipelineRun run, String provider, String model) {
    if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
      throw new IllegalStateException(
          "Approved continuation route requires an exact provider and model.");
    }
    if (approvedRouteAdmission != null) {
      approvedRouteAdmission.assertAvailable(run, provider, model);
      return;
    }
    if (modelRegistry == null) {
      return;
    }
    LlmProvider requested;
    try {
      requested = LlmProvider.valueOf(provider.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException invalidProvider) {
      throw new IllegalStateException(
          "Approved continuation provider is not registered: " + provider);
    }
    String canonicalModel = modelRegistry.canonicalize(model);
    String owner = modelRegistry.providerFor(canonicalModel);
    if (owner == null
        || !com.navneet.executespec.router.ModelAccessService.canRouteModel(
            requested, canonicalModel, owner)) {
      throw new IllegalStateException(
          "Approved continuation route is not dispatchable: " + provider + "/" + model);
    }
  }

  private static String continuationProposalDigest(int version, String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalStateException("Continuation proposal is missing.");
    }
    try {
      byte[] bytes =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest((version + "\n" + json).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(bytes);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private void appendApprovedContinuationAction(
      com.navneet.executespec.state.entity.AgentTask task, RetryProviderOverride override) {
    if (task == null || override == null) {
      return;
    }
    try {
      ArrayNode actions = readContinuationActions(task.getContinuationActionsJson());
      ObjectNode action = actions.addObject();
      action.put("status", "APPROVED");
      action.put(
          "decision",
          override.proposedAction() == null ? "UNKNOWN" : override.proposedAction().name());
      action.put("provider", override.provider());
      action.put("model", override.model());
      action.put("approvalId", override.approvalId());
      action.put("proposalVersion", override.proposalVersion());
      action.put("proposalDigest", override.proposalDigest());
      action.put("reason", "user approved blocked continuation");
      action.put("postReview", override.postReview());
      action.put("limitName", "userApproval");
      action.put("usedLimit", 1);
      action.put("maxLimit", 1);
      action.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepo.saveAndFlush(task);
      broadcaster.emit(
          task.getRunId(),
          RunEvent.continuationAction(
              task.getRunId(),
              task.getTaskId(),
              "Continuation action approved: "
                  + (override.proposedAction() == null ? "UNKNOWN" : override.proposedAction())
                  + " -> "
                  + override.provider()
                  + "/"
                  + override.model(),
              override.proposedAction() == null ? "UNKNOWN" : override.proposedAction().name(),
              override.provider(),
              override.model(),
              "user approved blocked continuation",
              actions.size(),
              1,
              "userApproval",
              1,
              1,
              "APPROVED"));
    } catch (Exception ex) {
      throw new IllegalStateException(
          "Could not persist the continuation approval receipt for task " + task.getTaskId(), ex);
    }
  }

  private void persistApprovedAttemptEvidence(
      String taskKey,
      String proposalDigest,
      String field,
      RunEffectiveRouteService.AttemptRouteEvidence evidence,
      long callIndexFloor) {
    if (taskKey == null
        || proposalDigest == null
        || proposalDigest.isBlank()
        || evidence == null
        || evidence.route().attribution()
            != com.navneet.executespec.cost.EffectiveRouteProjection.Attribution.LEDGER
        || evidence.physicalAttemptIds().isEmpty()) {
      throw new IllegalStateException(
          "Approved continuation attempt evidence is incomplete for " + taskKey);
    }
    var task =
        taskRepo
            .findById(taskKey)
            .orElseThrow(
                () -> new IllegalStateException("Continuation task disappeared: " + taskKey));
    try {
      ArrayNode actions = readContinuationActions(task.getContinuationActionsJson());
      ObjectNode approved = null;
      for (int i = actions.size() - 1; i >= 0; i--) {
        JsonNode candidate = actions.get(i);
        if ("APPROVED".equalsIgnoreCase(candidate.path("status").asText())
            && proposalDigest.equals(candidate.path("proposalDigest").asText())
            && Objects.equals(
                task.getContinuationApprovalId(), candidate.path("approvalId").asText(null))) {
          approved = (ObjectNode) candidate;
          break;
        }
      }
      if (approved == null) {
        throw new IllegalStateException(
            "Approved continuation action no longer matches proposal " + proposalDigest);
      }
      ObjectNode attempt = approved.putObject(field);
      attempt.put("provider", evidence.route().provider());
      attempt.put("model", evidence.route().model());
      attempt.put("attribution", evidence.route().attribution().name());
      attempt.put("callIndexFloor", callIndexFloor);
      attempt.put("firstCallIndex", evidence.firstCallIndex());
      attempt.put("lastCallIndex", evidence.lastCallIndex());
      ArrayNode ids = attempt.putArray("physicalAttemptIds");
      evidence.physicalAttemptIds().forEach(ids::add);
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepo.saveAndFlush(task);
    } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
      throw new IllegalStateException(
          "Could not persist approved continuation attempt evidence", malformed);
    }
  }

  private ValidationEvidenceCheckpoint invalidateValidationEvidence(String runId) {
    ValidationReport previous =
        validationReportRepository
            .findByRunId(runId)
            .map(RunLifecycleService::copyReport)
            .orElse(null);
    if (previous != null) {
      ValidationReport stale = copyReport(previous);
      AutomaticCorrectionLifecycle.markIndependentEvidenceStale(stale);
      validationReportRepository.saveAndFlush(stale);
    }
    return new ValidationEvidenceCheckpoint(runId, previous, inspectCorrectionContracts(runId));
  }

  private void restoreValidationEvidence(ValidationEvidenceCheckpoint checkpoint) {
    if (checkpoint == null || checkpoint.runId() == null) {
      return;
    }
    if (checkpoint.previous() != null) {
      validationReportRepository.saveAndFlush(copyReport(checkpoint.previous()));
      return;
    }
    validationReportRepository
        .findByRunId(checkpoint.runId())
        .ifPresent(validationReportRepository::delete);
    validationReportRepository.flush();
  }

  private static ValidationReport copyReport(ValidationReport source) {
    ValidationReport target = new ValidationReport();
    org.springframework.beans.BeanUtils.copyProperties(source, target);
    return target;
  }

  private record ValidationEvidenceCheckpoint(
      String runId,
      ValidationReport previous,
      CorrectionResolutionEvidence.ContractValidation contractsBefore) {}

  private CorrectionResolutionEvidence.ValidationDelta captureValidationDelta(
      ValidationEvidenceCheckpoint checkpoint) {
    return captureValidationDelta(checkpoint, null);
  }

  private CorrectionResolutionEvidence.ValidationDelta captureValidationDelta(
      ValidationEvidenceCheckpoint checkpoint,
      CorrectionResolutionEvidence.ValidationSnapshot fallbackAfter) {
    if (checkpoint == null || checkpoint.runId() == null) return null;
    CorrectionResolutionEvidence.ValidationSnapshot before =
        CorrectionResolutionEvidence.snapshot(
            objectMapper, checkpoint.previous(), checkpoint.contractsBefore());
    ValidationReport current =
        validationReportRepository.findByRunId(checkpoint.runId()).orElse(null);
    CorrectionResolutionEvidence.ValidationSnapshot after =
        current == null || "STALE".equalsIgnoreCase(current.getStatus())
            ? fallbackAfter
            : CorrectionResolutionEvidence.snapshot(
                objectMapper, current, inspectCorrectionContracts(checkpoint.runId()));
    return after == null ? null : CorrectionResolutionEvidence.delta(before, after);
  }

  private CorrectionResolutionEvidence.ContractValidation inspectCorrectionContracts(String runId) {
    if (!brownfieldHttpContractGuard.hasBaseline(runId)) {
      return CorrectionResolutionEvidence.ContractValidation.notApplicable();
    }
    try {
      return CorrectionResolutionEvidence.contracts(
          brownfieldHttpContractGuard.inspectCompatibility(runId));
    } catch (RuntimeException unavailable) {
      log.warn(
          "correction: could not snapshot protected contracts for run {}: {}",
          runId,
          unavailable.getMessage());
      return CorrectionResolutionEvidence.ContractValidation.unknown();
    }
  }

  private void restoreRejectedCorrection(
      String runId,
      com.navneet.executespec.state.entity.AgentTask taskBeforeCorrection,
      String runStatus,
      String runReason,
      Instant runCompletedAt,
      String reviewJson,
      String code,
      String reason,
      String generation,
      CorrectionWorkspaceTransaction.DiffEvidence diffEvidence,
      CorrectionResolutionEvidence.ValidationDelta validationDelta) {
    if (taskBeforeCorrection == null) {
      throw new IllegalStateException("Rejected correction has no durable task checkpoint");
    }
    String currentActions =
        taskRepo
            .findById(taskBeforeCorrection.getTaskId())
            .map(com.navneet.executespec.state.entity.AgentTask::getContinuationActionsJson)
            .orElse(taskBeforeCorrection.getContinuationActionsJson());
    taskBeforeCorrection.setContinuationActionsJson(currentActions);
    taskRepo.saveAndFlush(taskBeforeCorrection);
    appendCorrectionResolution(
        taskBeforeCorrection.getTaskId(),
        "CORRECTION_EXECUTION_FAILED".equals(code) ? "ROLLED_BACK" : "REJECTED",
        code,
        reason,
        generation,
        generation,
        diffEvidence,
        validationDelta,
        true);

    PipelineRun current =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalStateException("Run disappeared during correction"));
    current.setStatus(runStatus);
    current.setStatusReason(runReason);
    current.setCompletedAt(runCompletedAt);
    current.setReviewJson(reviewJson);
    runRepo.saveAndFlush(current);
    broadcaster.emit(runId, RunEvent.runStatus(runId, runStatus));
    broadcaster.emit(
        runId,
        RunEvent.taskStatus(
            runId, taskBeforeCorrection.getTaskId(), taskBeforeCorrection.getStatus()));
  }

  private void appendCorrectionResolution(
      String taskKey,
      String status,
      String code,
      String reason,
      String beforeGeneration,
      String afterGeneration) {
    appendCorrectionResolution(
        taskKey, status, code, reason, beforeGeneration, afterGeneration, null, null, false);
  }

  private void appendCorrectionResolution(
      String taskKey,
      String status,
      String code,
      String reason,
      String beforeGeneration,
      String afterGeneration,
      CorrectionWorkspaceTransaction.DiffEvidence diffEvidence,
      CorrectionResolutionEvidence.ValidationDelta validationDelta,
      boolean evidenceInvalidated) {
    var task =
        taskRepo
            .findById(taskKey)
            .orElseThrow(
                () -> new IllegalStateException("Correction task disappeared: " + taskKey));
    try {
      ArrayNode actions = readContinuationActions(task.getContinuationActionsJson());
      CorrectionResolutionEvidence.append(
          objectMapper,
          actions,
          status,
          code,
          reason,
          beforeGeneration,
          afterGeneration,
          diffEvidence,
          validationDelta,
          evidenceInvalidated);
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepo.saveAndFlush(task);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("Could not persist correction resolution", e);
    }
  }

  private ArrayNode readContinuationActions(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (json == null || json.isBlank()) {
      return objectMapper.createArrayNode();
    }
    JsonNode parsed = objectMapper.readTree(json);
    if (parsed != null && parsed.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      parsed.forEach(copy::add);
      return copy;
    }
    return objectMapper.createArrayNode();
  }

  private static String retryMessage(
      String taskId, String prevRunStatus, RetryProviderOverride override) {
    String base = "Retrying task " + taskId + " (run was " + prevRunStatus + " → now WORKING)";
    if (override == null) {
      return base;
    }
    return base
        + " with approved continuation model "
        + override.provider()
        + "/"
        + override.model();
  }

  private static RunContinuationDecision proposedContinuationAction(JsonNode root) {
    String explicit = root.path("policy").path("proposedAction").asText("");
    if (explicit.isBlank()) {
      explicit = root.path("proposedAction").asText("");
    }
    RunContinuationDecision parsed = parseContinuationAction(explicit);
    if (parsed != null) {
      return parsed;
    }
    JsonNode reasons = root.path("reasons");
    if (reasons.isArray()) {
      for (JsonNode reason : reasons) {
        parsed = proposedContinuationActionFromText(reason.asText(""));
        if (parsed != null) {
          return parsed;
        }
      }
    }
    return null;
  }

  private static RunContinuationDecision proposedContinuationActionFromText(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    for (RunContinuationDecision candidate :
        List.of(
            RunContinuationDecision.STEP_UP_MODEL,
            RunContinuationDecision.STEP_DOWN_MODEL,
            RunContinuationDecision.CHANGE_PROVIDER)) {
      if (text.contains("proposed action " + candidate.name())) {
        return candidate;
      }
    }
    return null;
  }

  private static RunContinuationDecision parseContinuationAction(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      RunContinuationDecision decision = RunContinuationDecision.valueOf(value.trim());
      return switch (decision) {
        case STEP_UP_MODEL, STEP_DOWN_MODEL, CHANGE_PROVIDER -> decision;
        case CONTINUE_SAME_MODEL_PROVIDER, STOP_DONE, STOP_BLOCKED, STOP_FAILED, STOP_WASTE_GUARD ->
            null;
      };
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  /**
   * RR-1564: seed the resumed executor's escalation counters from the run's already-consumed
   * continuation usage in the durable {@code continuation_actions_json} ledger, THEN add the newly
   * approved action's own contribution. Returning a constant {@code (1,0,0)}/{@code (0,1,0)}/{@code
   * (0,0,1)} from the action kind alone reset the escalation budget on every human approval — an
   * unbounded approve-loop spend path the moment an operator raises the per-run
   * switch/step-up/step-down limits above 1. {@code priorActionsJson} is snapshotted before the
   * approved row is appended, so the approved action is counted exactly once.
   */
  private ContinuationUsage continuationUsageFor(
      PipelineRun row, RetryProviderOverride override, String priorActionsJson) {
    if (override == null) {
      return ContinuationUsage.none();
    }
    return priorLedgerUsage(priorActionsJson).plus(usageForApprovedAction(row, override));
  }

  private ContinuationUsage priorLedgerUsage(String priorActionsJson) {
    int switches = 0;
    int stepUps = 0;
    int stepDowns = 0;
    try {
      ArrayNode actions = readContinuationActions(priorActionsJson);
      for (JsonNode node : actions) {
        String status = node.path("status").asText("");
        if (!"EXECUTED".equalsIgnoreCase(status) && !"APPROVED".equalsIgnoreCase(status)) {
          continue;
        }
        RunContinuationDecision decision =
            parseContinuationAction(node.path("decision").asText(""));
        if (decision == RunContinuationDecision.CHANGE_PROVIDER) {
          switches++;
        } else if (decision == RunContinuationDecision.STEP_UP_MODEL) {
          stepUps++;
        } else if (decision == RunContinuationDecision.STEP_DOWN_MODEL) {
          stepDowns++;
        }
      }
    } catch (Exception e) {
      // A corrupt ledger must not under-count the escalation budget; fail closed by treating the
      // prior usage as unknown-nonzero only where we can, but at minimum log and continue with 0 so
      // the approved action still adds its own contribution.
      log.warn("RR-1564: failed to read prior continuation ledger for seeding: {}", e.getMessage());
    }
    return new ContinuationUsage(switches, stepUps, stepDowns);
  }

  private static ContinuationUsage usageForApprovedAction(
      PipelineRun row, RetryProviderOverride override) {
    RunContinuationDecision action = override.proposedAction();
    if (action == RunContinuationDecision.CHANGE_PROVIDER) {
      return new ContinuationUsage(1, 0, 0);
    }
    if (action == RunContinuationDecision.STEP_UP_MODEL) {
      return new ContinuationUsage(0, 1, 0);
    }
    if (action == RunContinuationDecision.STEP_DOWN_MODEL) {
      return new ContinuationUsage(0, 0, 1);
    }
    String currentProvider = row == null ? "" : blankToEmpty(row.getAiProvider());
    if (!currentProvider.isBlank()
        && !currentProvider.equalsIgnoreCase(blankToEmpty(override.provider()))) {
      return new ContinuationUsage(1, 0, 0);
    }
    String currentModel = row == null ? "" : blankToEmpty(row.getWorkerModel());
    if (!currentModel.isBlank() && !currentModel.equals(blankToEmpty(override.model()))) {
      return new ContinuationUsage(0, 1, 0);
    }
    return ContinuationUsage.none();
  }

  private static String blankToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  public record ContinuationApproval(
      String provider,
      String model,
      RunContinuationDecision proposedAction,
      boolean benchmarkPriorRequired,
      boolean postReview,
      int proposalVersion,
      String proposalDigest,
      String approvalId,
      boolean replayed,
      String approvalStatus) {
    /** Compatibility shape used by deterministic continuation fixtures that do not dispatch. */
    public ContinuationApproval(
        String provider,
        String model,
        RunContinuationDecision proposedAction,
        boolean benchmarkPriorRequired,
        boolean postReview) {
      this(
          provider,
          model,
          proposedAction,
          benchmarkPriorRequired,
          postReview,
          1,
          "0".repeat(64),
          "fixture",
          false,
          "QUEUED");
    }
  }

  private record RetryProviderOverride(
      String provider,
      String model,
      RunContinuationDecision proposedAction,
      boolean benchmarkPriorRequired,
      boolean postReview,
      int proposalVersion,
      String proposalDigest,
      String approvalId) {}

  private record RetryAdmission(String approvalId, boolean replayed, String status) {
    static RetryAdmission normalRetry() {
      return new RetryAdmission(null, false, null);
    }
  }

  private record ContinuationUsage(int providerSwitches, int modelStepUps, int modelStepDowns) {
    static ContinuationUsage none() {
      return new ContinuationUsage(0, 0, 0);
    }

    ContinuationUsage plus(ContinuationUsage other) {
      return new ContinuationUsage(
          providerSwitches + other.providerSwitches,
          modelStepUps + other.modelStepUps,
          modelStepDowns + other.modelStepDowns);
    }
  }

  /**
   * Regenerate the validation report for a COMPLETE run whose background validation failed or needs
   * a refresh. Synchronous — caller returns 202.
   */
  public void retryValidation(String runId) {
    var run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!java.util.List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
      throw new IllegalStateException(
          "retry-validation requires terminal run (current: " + run.getStatus() + ")");
    }
    log.info("RunOrchestrator retryValidation: run={}", runId);
    pool.submit(
        () -> {
          try {
            validationKickoff.generateValidationReport(runId, null, null, null);
          } catch (Exception e) {
            log.error("RunOrchestrator retryValidation failed run={}", runId, e);
          }
        });
  }

  /**
   * 2026-05-02 — full re-review flow for SUPER_ADMINs. Deletes the existing {@code
   * validation_report}, resets the run to {@code REVIEWING}, then submits an async job that re-runs
   * the reviewer from scratch + regenerates the report + re-resolves the terminal status.
   *
   * @throws IllegalArgumentException run not found
   * @throws IllegalStateException run is still in a non-terminal state
   */
  public void reReview(String runId) {
    var run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!java.util.List.of("COMPLETE", "PARTIAL", "FAILED").contains(run.getStatus())) {
      throw new IllegalStateException(
          "re-review requires a terminal run (current: " + run.getStatus() + ")");
    }
    log.info("RunOrchestrator reReview: run={} previous status={}", runId, run.getStatus());
    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) {
      throw new IllegalStateException("re-review workspace is unavailable: " + workspace);
    }
    String operationProvider =
        run.isRoutePinned() && run.getRequestedProvider() != null
            ? run.getRequestedProvider()
            : run.getAiProvider();
    String operationModel =
        run.isRoutePinned() && run.getRequestedModel() != null
            ? run.getRequestedModel()
            : run.getReviewerModel();
    RunLlmOperationLifecycleService.PhaseLease reviewOperation =
        requireRunOperationLifecycle()
            .beginReviewRetry(
                durableRunRootContext(runId, workspace), runId, operationProvider, operationModel);

    validationReportRepository
        .findByRunId(runId)
        .ifPresent(
            existing -> {
              log.info(
                  "RunOrchestrator reReview: deleting existing validation_report id={} for"
                      + " run={}",
                  existing.getId(),
                  runId);
              validationReportRepository.delete(existing);
            });
    run.setStatus("REVIEWING");
    run.setStatusReason(null);
    run.setCompletedAt(null);
    run.setReviewJson(null);
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));

    try {
      pool.submit(
          () -> {
            RunLlmOperationLifecycleService.PhaseLease activeReview = reviewOperation;
            try {
              runReReviewBlocking(runId, activeReview);
              activeReview = null;
            } catch (Exception e) {
              log.error("RunOrchestrator reReview run={} failed unexpectedly", runId, e);
              try {
                var stuck = runRepo.findById(runId).orElse(null);
                if (stuck != null && "REVIEWING".equals(stuck.getStatus())) {
                  stuck.setStatus("PARTIAL");
                  stuck.setStatusReason("RE_REVIEW_FAILED");
                  stuck.setCompletedAt(Instant.now());
                  runRepo.save(stuck);
                  broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL", "RE_REVIEW_FAILED"));
                }
              } catch (Exception ignored) {
                // give up — operator can hit the endpoint again
              }
            } finally {
              runOperationLifecycle.closeQuietly(runId, activeReview);
              // RR-486 — backstop: pool threads are reused, so an uncleared bound route (e.g. an
              // unchecked throw before runReReviewBlocking's own clear) must not leak an exact pin
              // onto the next task that runs on this thread.
              RunModelOverrides.clear();
            }
          });
    } catch (RuntimeException dispatchFailure) {
      requireRunOperationLifecycle().fail(reviewOperation, "RE_REVIEW_DISPATCH_FAILED");
      throw dispatchFailure;
    }
  }

  /**
   * Synchronous body of the {@link #reReview(String)} async task. Resolves provider/model, builds a
   * fresh {@code ExecutionContext}, runs reviewer + validation report generation, then derives +
   * persists the new terminal status.
   */
  private void runReReviewBlocking(
      String runId, RunLlmOperationLifecycleService.PhaseLease reviewOperation) {
    var run = runRepo.findById(runId).orElse(null);
    if (run == null) {
      throw new IllegalStateException("re-review run disappeared mid-flight: " + runId);
    }
    // RR-486 — reconstruct the run's immutable routing authority instead of silently defaulting to
    // OpenAI, and bind org overrides + the exact pin so the refreshed reviewer spends the SAME
    // provider/model the customer authorized. A pinned run resolves its provider from the pinned
    // requestedProvider; otherwise from aiProvider. A null/unknown provider is fail-closed (throws,
    // caught by reReview() → PARTIAL/RE_REVIEW_FAILED) rather than rerouted to a provider the run
    // never selected. bindRunModelOverrides additionally throws if a persisted pin is incomplete or
    // no longer dispatchable. RunModelOverrides is cleared in the finally below.
    bindRunModelOverrides(run);
    String providerSource =
        run.isRoutePinned() && run.getRequestedProvider() != null
            ? run.getRequestedProvider()
            : run.getAiProvider();
    if (providerSource == null || providerSource.isBlank()) {
      RunModelOverrides.clear();
      throw new IllegalStateException(
          "re-review cannot resolve the run's authorized provider (none persisted) for " + runId);
    }
    final LlmProvider provider;
    try {
      provider = LlmProvider.valueOf(providerSource.toUpperCase());
    } catch (IllegalArgumentException e) {
      RunModelOverrides.clear();
      throw new IllegalStateException(
          "re-review provider not recognised: " + providerSource + " for run " + runId, e);
    }
    ResolvedModel reviewerModel;
    Path workspace;
    try {
      // modelResolver honours the just-bound RunModelOverrides (org matrix + exact pin).
      reviewerModel = modelResolver.resolve("ReviewerAgent", provider, AgentTier.PLANNING);
      workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    } catch (RuntimeException e) {
      RunModelOverrides.clear();
      throw e;
    }
    if (!Files.isDirectory(workspace)) {
      log.warn(
          "RunOrchestrator reReview: workspace {} missing for run {}, marking PARTIAL",
          workspace,
          runId);
      run.setStatus("PARTIAL");
      run.setStatusReason("RE_REVIEW_NO_WORKSPACE");
      run.setCompletedAt(Instant.now());
      runRepo.save(run);
      broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL", "RE_REVIEW_NO_WORKSPACE"));
      requireRunOperationLifecycle().fail(reviewOperation, "RE_REVIEW_NO_WORKSPACE");
      RunModelOverrides.clear(); // RR-486
      return;
    }
    ExecutionContext rootCtx = reviewOperation.context().child("_re_review_");

    String buildCommand = null;
    Plan parsedPlan = null;
    if (run.getPlanJson() != null && !run.getPlanJson().isBlank()) {
      try {
        parsedPlan = objectMapper.readValue(run.getPlanJson(), Plan.class);
        buildCommand = parsedPlan.buildCommand();
      } catch (Exception e) {
        log.debug(
            "RunOrchestrator reReview run={} could not parse planJson; relying on auto-detect",
            runId);
      }
    }

    ReviewerResult reviewResult = null;
    boolean reviewFailed = false;
    try {
      log.info(
          "RunOrchestrator reReview run={} provider={} model={} buildCmd={}",
          runId,
          provider,
          reviewerModel.getModel(),
          buildCommand == null ? "(auto)" : buildCommand);
      reviewResult =
          reviewer.review(
              ReviewerRequest.builder()
                  .provider(provider.name().toLowerCase())
                  .model(reviewerModel.getModel())
                  .context(rootCtx)
                  .buildCommand(buildCommand)
                  .skipWiringPass(false)
                  .originalGoal(run.getUserRequest())
                  .requirements(parsedPlan == null ? List.of() : parsedPlan.requirements())
                  .build());
      persistenceWriter.persistReview(runId, reviewResult);
    } catch (Exception e) {
      reviewFailed = true;
      log.warn("RunOrchestrator reReview run={} reviewer failed: {}", runId, e.getMessage());
      persistenceWriter.persistReviewFailure(runId, e);
    } finally {
      requireRunOperationLifecycle().finishReviewAndClear(reviewOperation, reviewFailed);
    }

    ExecutionResult execResult = persistenceWriter.reconstructExecResultFromTasks(runId);

    try {
      validationKickoff.generateValidationReport(runId, null, reviewResult, null);
    } catch (Exception e) {
      log.warn(
          "RunOrchestrator reReview run={} report generation failed: {}", runId, e.getMessage());
    }

    RunOrchestrator.StatusAndReason terminal =
        RunOrchestrator.resolveTerminalStatus(execResult, reviewResult);
    var refreshed = runRepo.findById(runId).orElse(run);
    refreshed.setStatus(terminal.status());
    refreshed.setStatusReason(terminal.reason());
    refreshed.setCompletedAt(Instant.now());
    runRepo.save(refreshed);
    broadcaster.emit(runId, RunEvent.complete(runId, terminal.status(), terminal.reason()));
    RunModelOverrides.clear(); // RR-486 — release the bound route on this pool thread
    log.info(
        "RunOrchestrator reReview run={} done status={} reason={} reviewerVerdict={}",
        runId,
        terminal.status(),
        terminal.reason(),
        reviewResult == null ? "n/a" : reviewResult.overall());
  }
}
