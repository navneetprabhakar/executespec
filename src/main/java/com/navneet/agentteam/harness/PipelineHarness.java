package com.navneet.agentteam.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.ApiKeyResolver;
import com.navneet.agentteam.agent.CostEstimator;
import com.navneet.agentteam.agent.PlannerAgent;
import com.navneet.agentteam.agent.ReviewerAgent;
import com.navneet.agentteam.agent.RunKeyStore;
import com.navneet.agentteam.agent.model.ReviewIssue;
import com.navneet.agentteam.agent.model.ReviewReport;
import com.navneet.agentteam.agent.model.TaskPlan;
import com.navneet.agentteam.agent.model.TaskReview;
import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.deployment.PreviewAgent;
import com.navneet.agentteam.harness.event.PipelineEvent;
import com.navneet.agentteam.recovery.RecoveryProperties;
import com.navneet.agentteam.recovery.RunRecoveryService;
import com.navneet.agentteam.sse.RunEvent;
import com.navneet.agentteam.sse.SseBroadcaster;
import com.navneet.agentteam.state.StateStore;
import com.navneet.agentteam.state.entity.AgentTask;
import com.navneet.agentteam.state.entity.PipelineRun;
import com.navneet.agentteam.repo.GitHubRepoService;
import com.navneet.agentteam.tool.FileLockTool;
import com.navneet.agentteam.tool.FileSystemTool;
import com.navneet.agentteam.tool.GitTool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineHarness {

    /**
     * Task IDs handled by system agents (not WorkerAgent).
     *
     * <p>{@code generate_preview} — handled by {@link PreviewAgent} which generates Dockerfile /
     * docker-compose.yml deterministically (no LLM).
     *
     * <p>{@code generate_openapi_spec} — previously a mandatory planner task, now retired.
     * The spec is generated post-merge by ApiTestSuiteAgent.  Old plans in the DB may still
     * carry this task; filtering it here prevents the worker from deleting or corrupting the
     * existing {@code api-docs/openapi.yaml}.
     */
    private static final String OPENAPI_TASK_ID = "generate_openapi_spec";

    private final PlannerAgent plannerAgent;
    private final WorkerPool workerPool;
    private final ReviewerAgent reviewerAgent;
    private final CostEstimator costEstimator;
    private final ApprovalGate approvalGate;
    private final GitMergeService mergeService;
    private final StateStore stateStore;
    private final FileLockTool lockTool;
    private final FileSystemTool fsTool;
    private final GitTool gitTool;
    private final GitHubRepoService gitHubRepoService;
    private final WorkspaceInitService workspaceInitService;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final SseBroadcaster broadcaster;

    // ── Tier 5: Recovery service + event publisher ────────────────────────────
    private final RunRecoveryService recoveryService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Tier 8: Compile agent + Preview agent ─────────────────────────────────
    private final com.navneet.agentteam.compiler.CompilerAgent compilerAgent;
    private final PreviewAgent previewAgent;

    // ── Multi-tenant API key routing ──────────────────────────────────────────
    private final ApiKeyResolver apiKeyResolver;
    private final RunKeyStore runKeyStore;

    // ── Async infrastructure ──────────────────────────────────────────────────

    /**
     * Redis-backed gate store — replaces in-memory CompletableFuture maps so that
     * plan/merge approval REST calls work regardless of which MIG instance receives them.
     */
    private final GateStore gateStore;

    /** Limits simultaneous active pipeline runs. */
    private Semaphore runSlots;
    /** Thread pool for async pipeline execution (unbounded — threads block on semaphore). */
    private ExecutorService executor;

    /** Single scanner for CLI stdin path — avoids buffering issues. */
    private final Scanner stdin = new Scanner(System.in);

    @PostConstruct
    void init() {
        int maxRuns = props.getConcurrency() != null ? props.getConcurrency().getMaxRuns() : 3;
        runSlots = new Semaphore(maxRuns);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "pipeline-run");
            t.setDaemon(true);
            return t;
        });
        log.info("PipelineHarness ready — max concurrent runs: {}", maxRuns);
    }

    // ── CLI path ─────────────────────────────────────────────────────────────

    /** Synchronous CLI entry-point (blocking). Unchanged from Tier 1/2 logic. */
    public void run(String userRequest) {
        Optional<PipelineRun> resumable = stateStore.findResumableRun();
        if (resumable.isPresent()) {
            PipelineRun existing = resumable.get();
            log.info("Found resumable run {} in state [{}]", existing.getRunId(), existing.getStatus());
            log.info("Resume run {}? [y/n]: ", existing.getRunId());
            String answer = stdin.nextLine().trim();
            log.info("User input: {}", answer);
            if (answer.equalsIgnoreCase("y")) {
                resume(existing);
                return;
            }
        }

        PipelineRun run = stateStore.createRun(userRequest,
            props.getRoles().getPlanner(),
            props.getRoles().getWorker(),
            props.getRoles().getReviewer());

        log.info("Starting CLI run {}", run.getRunId());

        try {
            TaskPlan plan = plannerAgent.plan(userRequest, run.getRunId(), run.getPlannerModel());
            stateStore.savePlan(run.getRunId(), plan);

            boolean approved = approvalGate.requestApproval(plan, run);
            if (!approved) {
                stateStore.markRejected(run.getRunId(), "User rejected at plan approval gate");
                log.info("Plan rejected. Run cancelled.");
                return;
            }
            stateStore.markApproved(run.getRunId());

            List<AgentTask> tasks = stateStore.getPendingTasks(run.getRunId());
            workspaceInitService.ensureRunWorkspace(run.getRunId());
            initAgentContext(run.getRunId(), mapper.writeValueAsString(plan));
            WorkerPool.WorkerPoolResult poolResult = workerPool.runAll(tasks, run.getRunId(),
                props.getRoles().getWorker());
            List<WorkerReport> reports = poolResult.reports();

            stateStore.markReviewing(run.getRunId());
            run = stateStore.findRun(run.getRunId()).orElse(run);
            ReviewReport review = reviewerAgent.review(run, reports);
            stateStore.saveReview(run.getRunId(), review);

            boolean shouldMerge = approvalGate.requestMerge(review);
            if (shouldMerge) {
                mergeService.mergeAll(run.getRunId(), tasks);
                lockTool.releaseAll(run.getRunId());
                stateStore.markComplete(run.getRunId());
                log.info("Run complete! Changes merged to main.");
            } else {
                log.info("Merge declined. Branches preserved for inspection.");
                stateStore.markFailed(run.getRunId(), "User declined merge");
            }

        } catch (Exception e) {
            log.error("Run failed: {}", e.getMessage(), e);
            stateStore.markFailed(run.getRunId(), e.getMessage());
        }
    }

    // ── REST async path ───────────────────────────────────────────────────────

    /**
     * Submits a new run asynchronously.
     *
     * <p>Each run executes inside its own isolated workspace directory
     * ({@code <agent.workspace>/<runId>/}) with a fresh git repository, so
     * runs can never contaminate each other — no {@code freshWorkspace} flag
     * is needed any more.
     *
     * <p>If the maximum concurrent-run limit is already reached the run is
     * queued (status=QUEUED) and starts as soon as a slot is released.
     */
    public PipelineRun submitRun(String userRequest, String source,
                                  String repoUrl, String repoMode,
                                  String githubPat, String githubOwner) {
        PipelineRun run = stateStore.createRun(userRequest, source, repoUrl, repoMode,
            props.getRoles().getPlanner(),
            props.getRoles().getWorker(),
            props.getRoles().getReviewer());

        // Capture submitting user's GitHub credentials onto the run for later push/clone
        if (githubPat != null && !githubPat.isBlank()) {
            run.setGithubPat(githubPat);
        }
        if (githubOwner != null && !githubOwner.isBlank()) {
            run.setGithubOwner(githubOwner);
        }
        if ((githubPat != null && !githubPat.isBlank()) || (githubOwner != null && !githubOwner.isBlank())) {
            stateStore.save(run);
        }

        String runId = run.getRunId();

        if (!runSlots.tryAcquire()) {
            // No slot available right now — queue the run
            run.setStatus("QUEUED");
            run.setQueuePosition(countQueued() + 1);
            stateStore.save(run);
            broadcaster.emit(runId, RunEvent.queued(runId, run.getQueuePosition()));
            log.info("Run {} queued at position {}", runId, run.getQueuePosition());

            final PipelineRun queuedRun = run;
            executor.submit(() -> {
                boolean acquired = false;
                try {
                    runSlots.acquire();
                    acquired = true;
                    PipelineRun r = stateStore.findRun(runId).orElse(queuedRun);
                    r.setStatus("PLANNING");
                    r.setQueuePosition(0);
                    stateStore.save(r);
                    executeRunAsync(runId, userRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stateStore.markFailed(runId, "Interrupted while waiting for run slot");
                } finally {
                    if (acquired) runSlots.release();
                }
            });

        } else {
            // Slot available — start immediately
            executor.submit(() -> {
                try {
                    executeRunAsync(runId, userRequest);
                } finally {
                    runSlots.release();
                }
            });
        }

        return run;
    }

    /**
     * Resolves the plan-approval gate for a REST-submitted run.
     *
     * <p>Signals via Redis so the pipeline thread's polling loop picks it up within ~500 ms,
     * regardless of which MIG instance receives this HTTP call.
     *
     * @return false if the run is not in AWAITING_APPROVAL (unknown runId or wrong state → 404)
     */
    public boolean resolvePlanGate(String runId, boolean approved, String comment) {
        Optional<PipelineRun> optRun = stateStore.findRun(runId);
        if (optRun.isEmpty()) return false;
        if (!"AWAITING_APPROVAL".equals(optRun.get().getStatus())) return false;

        // Signal via Redis — pipeline thread on ANY instance picks it up within ~500 ms
        gateStore.setPlanGate(runId, approved);

        // Clear currentGate immediately so the UI banner disappears on next poll.
        // (markApproved() also clears it but runs asynchronously on the pipeline thread,
        //  causing a race where the frontend refetch still sees the old gate value.)
        clearGateSync(runId);
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("SSE emit failed on plan gate resolve for run {} — client likely disconnected", runId);
        }
        return true;
    }

    /**
     * Resolves the merge-approval gate for a REST-submitted run.
     *
     * <p>Signals via Redis so the pipeline thread's polling loop picks it up within ~500 ms,
     * regardless of which MIG instance receives this HTTP call.
     *
     * <p>Note: {@link #performMergeOnRestart} is preserved for the crash-recovery path
     * (triggered by {@code StuckRunMonitor} when a run is stuck in AWAITING_MERGE after an
     * instance replacement). It is no longer called from here — the Redis signal is
     * sufficient for the normal and cross-instance cases.
     *
     * @return false only if the runId is unknown or not in AWAITING_MERGE state (→ 404)
     */
    public boolean resolveMergeGate(String runId, boolean approved, String comment) {
        Optional<PipelineRun> optRun = stateStore.findRun(runId);
        if (optRun.isEmpty()) return false;
        if (!"AWAITING_MERGE".equals(optRun.get().getStatus())) return false;

        MergeDecision decision = approved ? MergeDecision.MERGE : MergeDecision.DECLINE;
        // Signal via Redis — pipeline thread on ANY instance picks it up within ~500 ms
        gateStore.setMergeGate(runId, decision);

        // Clear currentGate immediately — same race-condition fix as resolvePlanGate.
        clearGateSync(runId);
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("SSE emit failed on merge gate resolve for run {} — client likely disconnected", runId);
        }
        return true;
    }

    /**
     * Performs a full merge for a run whose in-memory gate was lost (server restart).
     * Reconstructs everything it needs from the database: review JSON, worker reports,
     * and the run itself.  Mirrors the post-{@code mergeGate.get()} logic in
     * {@link #executeRunAsync} exactly, including cost save, GitHub push, and
     * post-merge event publishing.
     */
    private void performMergeOnRestart(String runId, boolean approved, String comment) {
        PipelineRun run = stateStore.findRun(runId).orElse(null);
        if (run == null) {
            log.warn("[Gate] performMergeOnRestart: run {} not found in DB", runId);
            return;
        }

        // Clear the gate banner immediately so the UI no longer shows AWAITING_MERGE
        clearGateSync(runId);
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("[Gate] SSE emit failed on restart-merge gate resolve for run {}", runId);
        }

        if (!approved) {
            String declineReason = "Merge declined by user — run preserved. Use 'Reopen Merge Gate' to merge when ready.";
            stateStore.markPartial(runId, declineReason);
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "ℹ️ Merge declined — run is PARTIAL and preserved. "
                + "Use 'Reopen Merge Gate' to merge when ready."));
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, declineReason));
            log.info("[Gate] Restart-merge declined for run {}; marked PARTIAL", runId);
            broadcaster.complete(runId);
            return;
        }

        // Reconstruct review + worker reports from DB
        List<AgentTask> tasks    = stateStore.getAllTasks(runId);
        ReviewReport    review   = parseJson(run.getReviewJson(), ReviewReport.class);
        List<WorkerReport> reports = getWorkerReports(runId);

        try {
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "⟳ Merge gate resumed after server restart — merging code now…"));

            List<String> conflicted = mergeService.mergeAll(runId, tasks);
            lockTool.releaseAll(runId);

            if (!conflicted.isEmpty()) {
                List<AgentTask> allTasksForConflict = stateStore.getAllTasks(runId);
                allTasksForConflict.stream()
                    .filter(t -> conflicted.contains(t.getBranchName()))
                    .forEach(t -> {
                        stateStore.failTask(t.getTaskId(),
                            "Merge conflict — this task's output conflicted with another task's changes. "
                            + "Retry this task to regenerate the changes cleanly.");
                        broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "FAILED"));
                    });
                String conflictReason = "Merge conflict in " + conflicted.size() + " task branch(es): "
                    + String.join(", ", conflicted) + ". Retry the highlighted tasks.";
                stateStore.markPartial(runId, conflictReason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + conflictReason));
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, conflictReason));
                log.warn("[Gate] Restart-merge partial for run {}: {}", runId, conflictReason);
                return;
            }

            stateStore.markComplete(runId);
            saveActualCost(runId);
            broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
            log.info("[Gate] Restart-merge complete for run {}", runId);

            // GitHub push is non-blocking — failure does not revert COMPLETE
            try {
                pushToGitHubIfConfigured(run);
            } catch (Exception pushEx) {
                log.warn("[Gate] GitHub push failed for restart-merge of run {} (run is COMPLETE): {}",
                    runId, pushEx.getMessage());
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⚠️ GitHub push failed (run is COMPLETE, code was merged locally): "
                    + pushEx.getMessage()));
            }

            // Trigger post-merge agents (ContextAgent, PreviewService, ApiTestSuiteAgent)
            PipelineRun completedRun = stateStore.findRun(runId).orElse(run);
            boolean hadFailures = reports.stream()
                .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                               "partial".equalsIgnoreCase(r.getStatus()));
            eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                runId, completedRun, reports, review, hadFailures));
            eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

        } catch (Exception e) {
            log.error("[Gate] Restart-merge failed for run {}: {}", runId, e.getMessage(), e);
            stateStore.markFailed(runId, "Merge failed after server restart: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
        } finally {
            broadcaster.complete(runId);
        }
    }

    /** Cancels a run. Returns false if run is already terminal. */
    public boolean cancelRun(String runId) {
        // Signal open gates via Redis so the pipeline thread's polling loop unblocks.
        // The pipeline thread will read the rejection value and bail out normally.
        gateStore.setPlanGate(runId, false);
        gateStore.setMergeGate(runId, MergeDecision.DECLINE);

        boolean cancelled = stateStore.cancelRun(runId);
        if (cancelled) {
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            broadcaster.complete(runId);
        }
        return cancelled;
    }

    /**
     * Re-runs the reviewer and re-opens the merge gate for a PARTIAL run where all
     * worker tasks are already COMPLETE (e.g., after a "Merge declined" action).
     * Executes asynchronously. Validates that the run is PARTIAL and all tasks are COMPLETE
     * before submitting; throws a descriptive exception otherwise so the caller can return 4xx.
     */
    public void reopenMergeGate(String runId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!"PARTIAL".equals(run.getStatus()))
            throw new IllegalStateException("Run " + runId + " is not PARTIAL (status: " + run.getStatus() + ")");

        List<AgentTask> tasks = stateStore.getAllTasks(runId);
        boolean allComplete = tasks.stream()
            .filter(t -> !isSystemTask(t))
            .allMatch(t -> "COMPLETE".equals(t.getStatus()));
        if (!allComplete)
            throw new IllegalStateException(
                "Cannot reopen merge gate: some tasks are not COMPLETE. Retry them first.");

        final PipelineRun frozenRun = run;
        executor.submit(() -> doReviewAndMerge(frozenRun, false));
    }

    /**
     * Implements post-merge suggestions (MEDIUM/LOW reviewer issues) by re-running the
     * affected worker tasks with suggestion feedback injected, then re-running the reviewer
     * and re-opening the merge gate.
     *
     * <p>Two call paths:
     * <ul>
     *   <li><b>AWAITING_MERGE</b>: resolves the live merge gate with
     *       {@link MergeDecision#IMPLEMENT_SUGGESTIONS}; the pipeline thread handles the rest.</li>
     *   <li><b>PARTIAL</b> (suggestions-failed retry): dispatches
     *       {@link #doReviewAndMerge} on the executor directly.</li>
     * </ul>
     */
    public void implementSuggestions(String runId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        String status = run.getStatus();
        if ("AWAITING_MERGE".equals(status)) {
            // Signal via Redis so the pipeline thread's polling loop picks it up
            gateStore.setMergeGate(runId, MergeDecision.IMPLEMENT_SUGGESTIONS);
        } else if ("PARTIAL".equals(status)
                && run.getFailureReason() != null
                && run.getFailureReason().contains("Suggestions implementation failed")) {
            final PipelineRun frozenRun = run;
            executor.submit(() -> doReviewAndMerge(frozenRun, false));
        } else {
            throw new IllegalStateException(
                "Cannot implement suggestions in status: " + status
                + ". Run must be AWAITING_MERGE (with live gate) or PARTIAL after a "
                + "failed suggestion implementation.");
        }
    }

    /**
     * Re-runs the reviewer for a PARTIAL run and opens the merge gate.
     * Used by {@link #reopenMergeGate} and {@link #implementSuggestions} to resume runs.
     * Supports a while-loop to allow the user to click "Implement Suggestions" repeatedly.
     */
    /**
     * @param holdsComputeSlot true when called from {@link #executeRunAsync} (which holds a
     *   semaphore slot); false when called from standalone handlers or {@link #runRetryAsync}
     *   (which do not hold a slot). The gate wait releases the slot iff this flag is true.
     */
    private void doReviewAndMerge(PipelineRun run, boolean holdsComputeSlot) {
        String runId = run.getRunId();
        try {
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "🔄 Re-running reviewer before reopening merge gate…"));

            while (true) {
                // ── Review ────────────────────────────────────────────────
                List<WorkerReport> reports = getWorkerReports(runId);
                stateStore.markReviewing(runId);
                broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
                PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
                ReviewReport review = reviewerAgent.review(freshRun, reports);
                stateStore.saveReview(runId, review);

                // ── Merge gate ────────────────────────────────────────────
                broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_MERGE"));
                gateStore.clearMergeGate(runId); // discard any stale key from a previous loop iteration
                String mergeGateId = stateStore.recordGateOpened(runId, "MERGE_APPROVAL");
                broadcaster.emit(runId, RunEvent.gateOpened(runId, "MERGE_APPROVAL"));

                if (holdsComputeSlot) {
                    log.info("[PipelineHarness] Run {} entering reopen-merge gate — releasing compute slot", runId);
                    runSlots.release();
                }
                MergeDecision decision;
                try {
                    // Poll Redis every 500 ms — works across any MIG instance
                    decision = gateStore.awaitMergeGate(runId);  // throws InterruptedException → outer catch
                } finally {
                    if (holdsComputeSlot) {
                        try { runSlots.acquire(); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        log.info("[PipelineHarness] Run {} reopen-merge gate resolved — compute slot reacquired", runId);
                    }
                }
                // Record gate resolution on the pipeline thread (it owns the local mergeGateId)
                stateStore.recordGateResolved(mergeGateId, decision == MergeDecision.MERGE);

                if (decision == MergeDecision.MERGE) {
                    List<String> conflicted = mergeService.mergeAll(runId, stateStore.getAllTasks(runId));
                    lockTool.releaseAll(runId);

                    if (!conflicted.isEmpty()) {
                        stateStore.getAllTasks(runId).stream()
                            .filter(t -> conflicted.contains(t.getBranchName()))
                            .forEach(t -> {
                                stateStore.failTask(t.getTaskId(),
                                    "Merge conflict — retry this task to regenerate the changes cleanly.");
                                broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "FAILED"));
                            });
                        String conflictReason = "Merge conflict in " + conflicted.size()
                            + " task branch(es). Retry the highlighted tasks.";
                        stateStore.markPartial(runId, conflictReason);
                        broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + conflictReason));
                        broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                        eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, conflictReason));
                        return;
                    }

                    stateStore.markComplete(runId);
                    saveActualCost(runId);
                    broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
                    log.info("Reopen-merge for run {} complete — merged to main", runId);

                    try {
                        pushToGitHubIfConfigured(stateStore.findRun(runId).orElse(run));
                    } catch (Exception pushEx) {
                        log.warn("GitHub push failed for reopen-merge of run {} (run is COMPLETE): {}",
                            runId, pushEx.getMessage());
                    }

                    final PipelineRun completedRun = stateStore.findRun(runId).orElse(run);
                    boolean hadFailures = reports.stream()
                        .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                                       "partial".equalsIgnoreCase(r.getStatus()));
                    eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                        runId, completedRun, reports, review, hadFailures));
                    eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
                    return;

                } else if (decision == MergeDecision.DECLINE) {
                    String reason = "Merge declined by user — run preserved. Use 'Reopen Merge Gate' to merge when ready.";
                    stateStore.markPartial(runId, reason);
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                        "ℹ️ Merge declined — run is PARTIAL. Use 'Reopen Merge Gate' to try again."));
                    broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                    return;

                } else { // IMPLEMENT_SUGGESTIONS — re-run workers with suggestion feedback then loop
                    boolean ok = doSuggestionImplementation(run);
                    if (!ok) return; // workers failed → already PARTIAL, don't re-review
                    // Loop continues → re-review at top of while(true)
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stateStore.markFailed(runId, "Reopen-merge interrupted: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
        } catch (Exception e) {
            log.error("Reopen-merge for run {} failed: {}", runId, e.getMessage(), e);
            stateStore.markFailed(runId, "Reopen-merge failed: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
        } finally {
            broadcaster.complete(runId);
        }
    }

    // ── REST async path: iteration run (starts from a completed run's codebase) ──

    /**
     * Starts a new pipeline run that was created as an iteration of a completed run.
     * The workspace is populated by copying tracked files from the source run's workspace,
     * then the standard async pipeline executes as normal.
     *
     * @param runId      the new run's ID (already persisted via StateStore.createIterationRun())
     * @param newRequest the user's refined request
     */
    public void startIterationRun(String runId, String newRequest) {
        PipelineRun run = stateStore.findRun(runId).orElseThrow();
        if (!runSlots.tryAcquire()) {
            run.setStatus("QUEUED");
            run.setQueuePosition(countQueued() + 1);
            stateStore.save(run);
            broadcaster.emit(runId, RunEvent.queued(runId, run.getQueuePosition()));
            log.info("Iteration run {} queued at position {}", runId, run.getQueuePosition());
            final PipelineRun queuedRun = run;
            executor.submit(() -> {
                boolean acquired = false;
                try {
                    runSlots.acquire();
                    acquired = true;
                    PipelineRun r = stateStore.findRun(runId).orElse(queuedRun);
                    r.setStatus("PLANNING");
                    r.setQueuePosition(0);
                    stateStore.save(r);
                    executeRunAsync(runId, newRequest);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stateStore.markFailed(runId, "Interrupted while waiting for run slot");
                } finally {
                    if (acquired) runSlots.release();
                }
            });
        } else {
            executor.submit(() -> {
                try {
                    executeRunAsync(runId, newRequest);
                } finally {
                    runSlots.release();
                }
            });
        }
    }

    // ── Internal async pipeline execution ────────────────────────────────────

    private void executeRunAsync(String runId, String userRequest) {
        log.info("Executing async run {}", runId);

        // Initialise this run's isolated workspace (workspace/{runId}/) before doing anything else.
        // Priority order:
        //   1. ITERATION run (sourceRunId set)  — copy the source run's tracked files
        //   2. EXISTING mode (repoUrl set)      — clone the source GitHub repo
        //   3. Default                           — init a fresh empty git workspace
        PipelineRun run = stateStore.findRun(runId).orElseThrow();

        // ── Resolve per-user/org API keys and register for this run ──────────
        // Resolution hierarchy: user key → org key → system key (application.yml).
        // Keys are stored in RunKeyStore so LlmRouter can use them without any agent
        // signature changes. Cleaned up in the finally block below.
        runKeyStore.put(runId, apiKeyResolver.resolve(run.getCreatedByUserId()));

        if (run.getSourceRunId() != null && !run.getSourceRunId().isBlank()) {
            try {
                log.info("Copying workspace from source run {} to iteration run {}",
                    run.getSourceRunId(), runId);
                workspaceInitService.copyFromRun(run.getSourceRunId(), runId);
            } catch (Exception copyEx) {
                stateStore.markFailed(runId, "Failed to copy source workspace: " + copyEx.getMessage());
                log.error("Workspace copy failed for iteration run {} (source={}): {}",
                    runId, run.getSourceRunId(), copyEx.getMessage());
                return;
            }
        } else if ("EXISTING".equals(run.getRepoMode())
                && run.getRepoUrl() != null && !run.getRepoUrl().isBlank()) {
            try {
                workspaceInitService.cloneFromRepo(runId, run.getRepoUrl(), run.getGithubPat());
            } catch (Exception cloneEx) {
                stateStore.markFailed(runId, "Failed to clone source repo: " + cloneEx.getMessage());
                log.error("Clone failed for run {} from {}: {}", runId, run.getRepoUrl(), cloneEx.getMessage());
                return;
            }
        } else {
            workspaceInitService.ensureRunWorkspace(runId);
        }
        broadcaster.emit(runId, RunEvent.runStatus(runId, "PLANNING"));
        eventPublisher.publishEvent(new PipelineEvent.RunStarted(runId));

        try {
            // ── 1. Plan ────────────────────────────────────────────────────
            // Pass the stored plannerModel so UI model-overrides are honoured.
            // run was already fetched from DB above, no extra DB call needed.
            TaskPlan plan = plannerAgent.plan(userRequest, runId, run.getPlannerModel());
            stateStore.savePlan(runId, plan);
            broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_APPROVAL"));

            // ── 2. Plan approval gate ──────────────────────────────────────
            String planGateId = stateStore.recordGateOpened(runId, "PLAN_APPROVAL");
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "PLAN_APPROVAL"));

            boolean planApproved;
            // Tier 7: integration-triggered runs may skip the human approval gate
            if (run.isSkipApproval()) {
                planApproved = true;
                stateStore.recordGateResolved(planGateId, true);
                broadcaster.emit(runId, RunEvent.gateResolved(runId, true, "Auto-approved (integration run)"));
                log.info("Plan gate auto-approved for integration run {}", runId);
            } else {
                // Release the compute slot while the run is idle at the approval gate.
                // Gate-waiting runs must not block new submissions from starting.
                log.info("[PipelineHarness] Run {} entering plan-approval gate — releasing compute slot", runId);
                runSlots.release();
                try {
                    // Poll Redis every 500 ms — works across any MIG instance
                    planApproved = gateStore.awaitPlanGate(runId);  // throws InterruptedException → outer catch
                } finally {
                    try { runSlots.acquire(); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                log.info("[PipelineHarness] Run {} plan-approval gate resolved — compute slot reacquired", runId);
                // Record gate resolution on the pipeline thread (it owns the local planGateId)
                stateStore.recordGateResolved(planGateId, planApproved);
            }

            if (!planApproved) {
                stateStore.markRejected(runId, "Rejected at plan approval gate");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Rejected at plan approval gate"));
                broadcaster.complete(runId);
                return;
            }

            stateStore.markApproved(runId);
            broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

            // ── 3. Workers ─────────────────────────────────────────────────
            List<AgentTask> tasks = stateStore.getPendingTasks(runId);
            try {
                initAgentContext(runId, mapper.writeValueAsString(plan));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Cannot serialize plan", e);
            }

            // Filter out generate_preview — PreviewAgent handles it deterministically
            // after all worker tasks complete, so workers never see this task.
            List<AgentTask> workerTasks = tasks.stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            // Validate file ownership — detect overlap before workers start
            validateFileOwnership(workerTasks, runId);

            // Pre-claim file ownership based on planner declarations
            for (AgentTask wt : workerTasks) {
                try {
                    com.navneet.agentteam.agent.model.TaskDefinition def =
                        mapper.readValue(wt.getTaskJson(), com.navneet.agentteam.agent.model.TaskDefinition.class);
                    if (def.getOwnedFiles() != null && !def.getOwnedFiles().isEmpty()) {
                        lockTool.preClaimAll(runId, wt.getTaskId(), def.getOwnedFiles());
                    }
                } catch (JsonProcessingException ignore) { /* non-fatal */ }
            }

            WorkerPool.WorkerPoolResult poolResult = workerPool.runAll(workerTasks, runId,
                props.getRoles().getWorker());
            List<WorkerReport> reports = poolResult.reports();

            // Auto-complete generate_openapi_spec (backwards compat with old plans that still
            // carry this task in the DB).  ApiTestSuiteAgent generates the real spec post-merge.
            markOpenapiTaskComplete(runId, tasks);

            // ── Preview Agent (generates Dockerfile, docker-compose.yml, etc.) ──
            WorkerReport previewReport = previewAgent.run(runId, tasks);
            List<WorkerReport> allReports = new ArrayList<>(reports);
            allReports.add(previewReport);

            // ── Tier 8: Compile & Fix (non-fatal, sync before reviewer) ───
            compilerAgent.runCompileAndFix(runId);

            // ── Merge-gate eligibility: only open when all WORKER tasks complete ──
            // Worker tasks = all tasks except the preview task (its BLOCKED status
            // must not block the merge gate since it is non-fatal).
            List<AgentTask> workerTasksNow = stateStore.getAllTasks(runId).stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            boolean allWorkerTasksBlocked = workerTasksNow.stream()
                .allMatch(t -> "BLOCKED".equals(t.getStatus()) || "FAILED".equals(t.getStatus()));

            if (allWorkerTasksBlocked) {
                String reason = "All tasks blocked or failed — nothing to merge";
                log.warn("Auto-failing run {} — {}", runId, reason);
                stateStore.markFailed(runId, reason);
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                PipelineRun failedRun = stateStore.findRun(runId).orElse(run);
                recoveryService.autoRestartIfTransient(failedRun, allReports);
                return;
            }

            boolean allWorkerTasksComplete = workerTasksNow.stream()
                .allMatch(t -> "COMPLETE".equals(t.getStatus()));

            if (!allWorkerTasksComplete) {
                // Some tasks did not reach COMPLETE — mark run PARTIAL so the user
                // can retry individual tasks. The merge gate does NOT open here;
                // it opens after a successful retry that completes all remaining tasks.
                List<String> incompleteTitles = workerTasksNow.stream()
                    .filter(t -> !"COMPLETE".equals(t.getStatus()))
                    .map(t -> t.getTitle() + " [" + t.getStatus() + "]")
                    .toList();
                String partialReason = "Incomplete tasks: " + String.join("; ", incompleteTitles);
                log.warn("Run {} — {} — marking PARTIAL (no merge gate)", runId, partialReason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⚠️ Some tasks did not complete:\n  • " + String.join("\n  • ", incompleteTitles)
                    + "\n\nRun marked PARTIAL. Use 'Retry Task' on each incomplete task — "
                    + "the merge gate will open once all tasks are COMPLETE."));
                stateStore.markPartial(runId, partialReason);
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, partialReason));
                return;
            }

            // ── 4. Review + Auto-Remediation (up to 3 REWORK cycles) ──────
            // If the reviewer returns REWORK, automatically re-run all worker tasks
            // with the reviewer's issues injected into each task description so workers
            // know exactly what to fix. After MAX_REWORK_CYCLES exhausted, open the
            // merge gate so a human can approve or reject.
            final int MAX_REWORK_CYCLES = 3;
            int reworkCycle = 0;
            ReviewReport review = null;
            boolean reviewerRework = false;
            List<WorkerReport> currentReports = new ArrayList<>(allReports);
            PipelineRun currentRun = run;

            while (true) {
                stateStore.markReviewing(runId);
                broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
                currentRun = stateStore.findRun(runId).orElse(currentRun);
                review = reviewerAgent.review(currentRun, currentReports);
                stateStore.saveReview(runId, review);

                reviewerRework = "REWORK".equalsIgnoreCase(review.getRecommendation());
                if (!reviewerRework) break; // MERGE or PARTIAL_MERGE — proceed to merge gate

                reworkCycle++;
                if (reworkCycle > MAX_REWORK_CYCLES) {
                    // Auto-remediation exhausted; open merge gate and let human decide
                    log.warn("Run {} — REWORK after {} auto-remediation cycles; opening merge gate",
                        runId, MAX_REWORK_CYCLES);
                    broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                        "⚠️ Auto-remediation exhausted after " + MAX_REWORK_CYCLES + " cycles. "
                        + "Reviewer still recommends REWORK: " + review.getSummary()
                        + "\nOpening merge gate — approve to override, reject to cancel."));
                    break;
                }

                // Inject reviewer feedback and re-run ONLY the tasks the reviewer flagged.
                // Tasks that passed are left untouched — targeted remediation, not a full re-run.
                Set<String> failedTaskIds = getFailedTaskIds(review);
                log.info("Run {} — auto-remediation cycle {}/{}: REWORK — {} — failing tasks: {}",
                    runId, reworkCycle, MAX_REWORK_CYCLES, review.getSummary(), failedTaskIds);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "🔄 Auto-remediation cycle " + reworkCycle + "/" + MAX_REWORK_CYCLES
                    + " — Reviewer: " + review.getSummary()
                    + "\nApplying targeted fixes to tasks: " + failedTaskIds));
                broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

                List<AgentTask> workerTasksForRework = stateStore.getAllTasks(runId).stream()
                    .filter(t -> !isSystemTask(t))
                    .filter(t -> failedTaskIds.isEmpty() || failedTaskIds.contains(t.getTaskId()))
                    .toList();
                for (AgentTask t : workerTasksForRework) {
                    String feedback = buildRemediationFeedback(review, t.getTaskId());
                    stateStore.markRetryingWithFeedback(runId, t.getTaskId(), feedback);
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
                }
                // Reload refreshed task objects (markRetryingWithFeedback resets status fields)
                List<AgentTask> toRerun = workerTasksForRework.stream()
                    .map(t -> stateStore.getTask(t.getTaskId()))
                    .toList();
                workerPool.runAll(toRerun, runId, props.getRoles().getWorker());
                compilerAgent.runCompileAndFix(runId);

                // Re-collect worker reports for the next review cycle
                currentReports = stateStore.getAllTasks(runId).stream()
                    .filter(t -> t.getWorkerReportJson() != null)
                    .map(t -> {
                        try { return mapper.readValue(t.getWorkerReportJson(), WorkerReport.class); }
                        catch (Exception ex) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
            }

            // ── 4b. Auto-implement suggestions once before opening the gate ────
            // If the reviewer returned MERGE/PARTIAL_MERGE but still has post-merge
            // suggestions (MEDIUM/LOW issues), proactively implement them so the user's
            // first view of the merge gate already has a cleaner review.
            if (review != null
                    && review.getPostMergeSuggestions() != null
                    && !review.getPostMergeSuggestions().isEmpty()) {
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "💡 Auto-implementing " + review.getPostMergeSuggestions().size()
                    + " suggestion(s) before opening merge gate…"));
                boolean suggOk = doSuggestionImplementation(run);
                if (!suggOk) return; // workers failed → already PARTIAL
                // Re-run reviewer with the post-implementation state
                currentReports = getWorkerReports(runId);
                stateStore.markReviewing(runId);
                broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
                currentRun = stateStore.findRun(runId).orElse(currentRun);
                review = reviewerAgent.review(currentRun, currentReports);
                stateStore.saveReview(runId, review);
            }

            broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_MERGE"));

            // ── 5. Merge gate ──────────────────────────────────────────────
            gateStore.clearMergeGate(runId); // discard any stale key from a previous attempt
            String mergeGateId = stateStore.recordGateOpened(runId, "MERGE_APPROVAL");
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "MERGE_APPROVAL"));

            // Release the compute slot while the run is idle at the merge gate.
            // Gate-waiting runs must not block new submissions from starting.
            log.info("[PipelineHarness] Run {} entering merge gate — releasing compute slot", runId);
            runSlots.release();
            MergeDecision mergeDecision;
            try {
                // Poll Redis every 500 ms — works across any MIG instance
                mergeDecision = gateStore.awaitMergeGate(runId);  // throws InterruptedException → outer catch
            } finally {
                try { runSlots.acquire(); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            log.info("[PipelineHarness] Run {} merge gate resolved — compute slot reacquired", runId);
            // Record gate resolution on the pipeline thread (it owns the local mergeGateId)
            stateStore.recordGateResolved(mergeGateId, mergeDecision == MergeDecision.MERGE);

            if (mergeDecision == MergeDecision.MERGE) {
                // Merge first, then mark COMPLETE immediately so that any subsequent failure
                // (GitHub push, cost save) does NOT revert the run to FAILED — the code IS
                // successfully merged at this point.
                List<String> conflicted = mergeService.mergeAll(runId, stateStore.getAllTasks(runId));
                lockTool.releaseAll(runId);

                if (!conflicted.isEmpty()) {
                    // Some branches had merge conflicts — mark those tasks FAILED so the user
                    // can retry them via the TaskCard retry button, and mark the run PARTIAL.
                    List<AgentTask> allTasksForConflict = stateStore.getAllTasks(runId);
                    allTasksForConflict.stream()
                        .filter(t -> conflicted.contains(t.getBranchName()))
                        .forEach(t -> {
                            stateStore.failTask(t.getTaskId(),
                                "Merge conflict — this task's output conflicted with another task's changes. "
                                + "Retry this task to regenerate the changes cleanly.");
                            broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "FAILED"));
                        });
                    String conflictReason = "Merge conflict in " + conflicted.size() + " task branch(es): "
                        + String.join(", ", conflicted) + ". Retry the highlighted tasks.";
                    stateStore.markPartial(runId, conflictReason);
                    broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + conflictReason));
                    broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, conflictReason));
                    log.warn("Async run {} — partial merge: {}", runId, conflictReason);
                    return;
                }

                // ── Post-merge compile validation ──────────────────────────
                // Run compile-and-fix again after all branches are merged.
                // Catches semantic conflicts (e.g., renamed methods, conflicting imports)
                // that pass textual merge but break compilation.
                try {
                    var postMergeCompile = compilerAgent.runCompileAndFix(runId);
                    if (!postMergeCompile.passed() && !postMergeCompile.skipped()) {
                        broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                            "⚠️ Post-merge compile failed: " + postMergeCompile.errorSummary()
                            + " — proceeding with merge (non-blocking)."));
                    }
                } catch (Exception compileEx) {
                    log.warn("Post-merge compile failed for run {}: {}", runId, compileEx.getMessage());
                }

                stateStore.markComplete(runId);
                saveActualCost(runId);
                broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
                log.info("Async run {} complete — merged to main", runId);

                // GitHub push is non-blocking — failure does not revert COMPLETE status
                try {
                    pushToGitHubIfConfigured(currentRun);
                } catch (Exception pushEx) {
                    log.warn("GitHub push failed for run {} (run is COMPLETE): {}", runId, pushEx.getMessage());
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                        "⚠️ GitHub push failed (run is COMPLETE, code was merged locally): "
                        + pushEx.getMessage()));
                }

                // Publish MergeCompleted — PostMergeOrchestrator handles post-merge agents
                final PipelineRun completedRun = stateStore.findRun(runId).orElse(currentRun);
                boolean hadFailures = currentReports.stream()
                    .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                                   "partial".equalsIgnoreCase(r.getStatus()));
                eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                    runId, completedRun, currentReports, review, hadFailures));
                // Publish RunCompleted for integration outbound adapters
                eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

            } else if (mergeDecision == MergeDecision.IMPLEMENT_SUGGESTIONS) {
                // User clicked "Implement Suggestions" — run worker tasks with suggestion
                // feedback injected, then delegate to doReviewAndMerge for the next gate loop.
                boolean ok = doSuggestionImplementation(currentRun);
                if (ok) {
                    // All workers succeeded — re-review and open a new gate via doReviewAndMerge.
                    // Pass holdsComputeSlot=true: we're inside executeRunAsync which holds the slot.
                    final PipelineRun fr = stateStore.findRun(runId).orElse(currentRun);
                    doReviewAndMerge(fr, true);
                }
                // Whether ok or not, execution ends here — doReviewAndMerge handles its own
                // broadcaster.complete() in its finally; executeRunAsync's finally will also fire
                // but broadcaster.complete() is idempotent.

            } else {
                // DECLINE — always mark PARTIAL on decline, preserved for later merge.
                String reason = reviewerRework
                    ? "Merge declined after REWORK review — retry individual tasks or use 'Reopen Merge Gate' when ready"
                    : "Merge declined by user — run preserved. Use 'Reopen Merge Gate' to merge when ready.";
                stateStore.markPartial(runId, reason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "ℹ️ Merge declined — run is PARTIAL and preserved. "
                    + "Use 'Reopen Merge Gate' to merge when ready, or retry individual tasks if needed."));
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                log.info("Async run {} — merge declined; marked PARTIAL", runId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String reason = "Interrupted: " + e.getMessage();
            stateStore.markFailed(runId, reason);
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
        } catch (Exception e) {
            log.error("Async run {} failed: {}", runId, e.getMessage(), e);
            stateStore.markFailed(runId, e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
        } finally {
            broadcaster.complete(runId);
            runKeyStore.remove(runId);  // clean up per-run API keys
        }
    }

    // ── Resume (CLI) ──────────────────────────────────────────────────────────

    private void resume(PipelineRun run) {
        log.info("Resuming run {} from state [{}]", run.getRunId(), run.getStatus());
        switch (run.getStatus()) {
            case "AWAITING_APPROVAL" -> {
                TaskPlan plan = parseJson(run.getPlanJson(), TaskPlan.class);
                boolean approved = approvalGate.requestApproval(plan, run);
                if (approved) {
                    stateStore.markApproved(run.getRunId());
                    runWorkersAndReview(run);
                }
            }
            case "WORKING"       -> runWorkersAndReview(run);
            case "REVIEWING"     -> {
                List<WorkerReport> reports = getWorkerReports(run.getRunId());
                run = stateStore.findRun(run.getRunId()).orElse(run);
                ReviewReport review = reviewerAgent.review(run, reports);
                stateStore.saveReview(run.getRunId(), review);
                runMergeGate(run);
            }
            case "AWAITING_MERGE" -> runMergeGate(run);
            default -> log.warn("Cannot resume run in state: {}", run.getStatus());
        }
    }

    private void runWorkersAndReview(PipelineRun run) {
        List<AgentTask> incomplete = stateStore.getIncompleteTasks(run.getRunId());
        workspaceInitService.ensureRunWorkspace(run.getRunId());
        initAgentContext(run.getRunId(), run.getPlanJson());
        WorkerPool.WorkerPoolResult poolResult = workerPool.runAll(incomplete, run.getRunId(),
            props.getRoles().getWorker());
        List<WorkerReport> reports = poolResult.reports();
        stateStore.markReviewing(run.getRunId());
        PipelineRun fresh = stateStore.findRun(run.getRunId()).orElse(run);
        ReviewReport review = reviewerAgent.review(fresh, reports);
        stateStore.saveReview(run.getRunId(), review);
        runMergeGate(run);
    }

    private void runMergeGate(PipelineRun run) {
        ReviewReport review = parseJson(run.getReviewJson(), ReviewReport.class);
        boolean shouldMerge = approvalGate.requestMerge(review);
        if (shouldMerge) {
            List<String> conflicted = mergeService.mergeAll(run.getRunId(), stateStore.getAllTasks(run.getRunId()));
            lockTool.releaseAll(run.getRunId());
            if (!conflicted.isEmpty()) {
                stateStore.getAllTasks(run.getRunId()).stream()
                    .filter(t -> conflicted.contains(t.getBranchName()))
                    .forEach(t -> stateStore.failTask(t.getTaskId(),
                        "Merge conflict — retry this task to regenerate the changes cleanly."));
                stateStore.markPartial(run.getRunId(),
                    "Merge conflict in " + conflicted.size() + " task branch(es).");
            } else {
                pushToGitHubIfConfigured(run);
                stateStore.markComplete(run.getRunId());
            }
        }
    }

    // ── Actual cost computation ───────────────────────────────────────────────

    /**
     * Computes the cache-aware actual cost for a completed run and persists it.
     * Uses per-task provider/model pricing with Anthropic cache multipliers.
     * Non-fatal: a failure here does not affect run status.
     */
    private void saveActualCost(String runId) {
        try {
            double cost = costEstimator.computeCostForRun(runId);
            stateStore.setActualCost(runId, cost);
            log.info("[PipelineHarness] Actual cost for run {}: ${}", runId, String.format("%.4f", cost));
        } catch (Exception e) {
            log.warn("[PipelineHarness] Could not compute actual cost for run {}: {}", runId, e.getMessage());
        }
    }

    // ── GitHub push ───────────────────────────────────────────────────────────

    /**
     * After a successful merge, pushes main to GitHub if the run has repo config.
     * - repoMode=NEW  + no repoUrl → creates a new repo first, then pushes.
     * - repoMode=EXISTING or repoUrl set → pushes to the existing remote.
     * Non-fatal: logs a warning but does not fail the run.
     */
    private void pushToGitHubIfConfigured(PipelineRun run) {
        String repoMode = run.getRepoMode();
        String repoUrl  = run.getRepoUrl();

        // Nothing configured — skip silently
        if ((repoMode == null || "NONE".equals(repoMode)) && repoUrl == null) return;

        // Prefer the run's per-user PAT; fall back to system-level PAT
        String pat = (run.getGithubPat() != null && !run.getGithubPat().isBlank())
                ? run.getGithubPat()
                : (props.getGithub() != null ? props.getGithub().getPat() : null);
        String owner = (run.getGithubOwner() != null && !run.getGithubOwner().isBlank())
                ? run.getGithubOwner()
                : (props.getGithub() != null ? props.getGithub().getDefaultOwner() : null);

        if (pat == null || pat.isBlank()) {
            log.warn("GitHub PAT not configured — skipping push for run {}", run.getRunId());
            return;
        }

        String remote = repoUrl;
        if ("NEW".equals(repoMode) && (repoUrl == null || repoUrl.isBlank())) {
            log.info("Creating new GitHub repo for run {} (owner={})", run.getRunId(), owner);
            remote = gitHubRepoService.createRepo(run.getUserRequest(), null, "private", pat, owner);
            if (remote != null) {
                run.setRepoUrl(remote);
                stateStore.save(run);
                log.info("Created GitHub repo: {}", remote);
            }
        }

        if (remote == null || remote.isBlank()) {
            log.warn("No remote URL available — skipping push for run {}", run.getRunId());
            return;
        }

        log.info("Pushing workspace to GitHub: {}", remote);
        com.navneet.agentteam.tool.ToolResult result = gitTool.pushToRemote(run.getRunId(), remote, pat);
        if (!result.isOk()) {
            log.warn("GitHub push failed for run {}: {}", run.getRunId(), result.value());
        }
    }

    // ── Force-retry of PARTIAL/BLOCKED task ──────────────────────────────────

    /**
     * Validates and kicks off a force-retry of a PARTIAL or BLOCKED task.
     * The run must currently be PARTIAL or FAILED (already finished).
     * Returns {@code true} if the retry was accepted and started asynchronously.
     */
    public boolean retryPartialTask(String runId, String taskId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        AgentTask task = stateStore.getTask(taskId);

        if (!runId.equals(task.getRunId())) {
            throw new IllegalArgumentException("Task " + taskId + " does not belong to run " + runId);
        }
        String ts = task.getStatus();
        if (!"PARTIAL".equals(ts) && !"BLOCKED".equals(ts) && !"FAILED".equals(ts)
                && !"PENDING".equals(ts) && !"LOCKED".equals(ts)) {
            throw new IllegalStateException(
                "Task " + taskId + " is not retryable (status: " + ts
                + "). Only PARTIAL, BLOCKED, FAILED, PENDING, or LOCKED tasks can be retried.");
        }

        // ── Special case: generate_openapi_spec is auto-completed by the harness (ApiTestSuiteAgent
        // handles the real spec post-merge). Just re-run the auto-complete and return.
        if (OPENAPI_TASK_ID.equals(taskId)) {
            log.info("Retrying {} — auto-completing (spec generated post-merge) for run {}", OPENAPI_TASK_ID, runId);
            markOpenapiTaskComplete(runId, stateStore.getAllTasks(runId));
            return true;
        }

        // ── Special case: generate_preview is always handled by PreviewAgent, never WorkerAgent.
        // Allow retry from AWAITING_MERGE (reviewer already said MERGE, just re-generate the
        // Dockerfile), PARTIAL, or FAILED. PreviewAgent.run() manages its own task state
        // (lockTask → WORKING → completeTask → COMPLETE/BLOCKED) so we don't touch run status.
        if (PreviewAgent.PREVIEW_TASK_ID.equals(taskId)) {
            String rs = run.getStatus();
            if (!"PARTIAL".equals(rs) && !"FAILED".equals(rs) && !"AWAITING_MERGE".equals(rs)) {
                throw new IllegalStateException(
                    "Run " + runId + " cannot retry generate_preview in state " + rs
                    + ". Allowed states: PARTIAL, FAILED, AWAITING_MERGE.");
            }
            log.info("Retrying generate_preview via PreviewAgent for run {} (run state: {})", runId, rs);
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "⟳ Re-running deployment file generation via PreviewAgent..."));
            List<AgentTask> allTasksForPreview = stateStore.getAllTasks(runId);
            Thread.ofVirtual().name("preview-retry-" + runId.substring(0, 8))
                .start(() -> previewAgent.run(runId, allTasksForPreview));
            return true;
        }

        String rs = run.getStatus();
        if (!"PARTIAL".equals(rs) && !"FAILED".equals(rs)) {
            throw new IllegalStateException(
                "Run " + runId + " cannot be retried in state " + rs
                + ". Run must be PARTIAL or FAILED before retrying individual tasks.");
        }

        log.info("Force-retrying task [{}] in run {}", taskId, runId);
        stateStore.markRetrying(runId, taskId);
        broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "PENDING"));
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "⟳ Force-retrying task: " + task.getTitle()));

        final PipelineRun frozenRun = stateStore.findRun(runId).orElse(run);
        final AgentTask frozenTask  = stateStore.getTask(taskId);
        Thread.ofVirtual().name("force-retry-" + taskId).start(() -> runRetryAsync(frozenRun, frozenTask));
        return true;
    }

    private void runRetryAsync(PipelineRun run, AgentTask task) {
        String runId  = run.getRunId();
        String taskId = task.getTaskId();

        // Re-resolve API keys so retries pick up any keys the user may have added since the initial run.
        runKeyStore.put(runId, apiKeyResolver.resolve(run.getCreatedByUserId()));

        try {
            // ── 1. Lock + run the retried worker task ──────────────────────
            String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;
            stateStore.lockTask(taskId, props.getRoles().getWorker(), branchName);
            WorkerReport retryReport = workerPool.executeTask(task, runId);

            broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId,
                "complete".equalsIgnoreCase(retryReport.getStatus())
                    ? "COMPLETE" : retryReport.getStatus().toUpperCase()));

            // ── 2. Auto-run any other incomplete worker tasks ──────────────
            // After one task is retried, check whether other worker tasks are
            // still incomplete (PARTIAL, BLOCKED, FAILED, PENDING, or LOCKED).
            // Reset and run them all before proceeding to reviewer — otherwise
            // the merge gate would open prematurely with missing work.
            List<AgentTask> otherIncomplete = stateStore.getAllTasks(runId).stream()
                .filter(t -> !taskId.equals(t.getTaskId()))
                .filter(t -> !isSystemTask(t))
                .filter(t -> {
                    String s = t.getStatus();
                    return "PARTIAL".equals(s) || "BLOCKED".equals(s) || "FAILED".equals(s)
                        || "PENDING".equals(s) || "LOCKED".equals(s);
                })
                .toList();

            if (!otherIncomplete.isEmpty()) {
                log.info("[Harness] {} additional incomplete task(s) found after retry — re-running: {}",
                    otherIncomplete.size(),
                    otherIncomplete.stream().map(AgentTask::getTaskId).toList());
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⟳ Running " + otherIncomplete.size() + " remaining incomplete task(s)..."));
                // Reset each to PENDING so the worker pool can pick them up
                for (AgentTask t : otherIncomplete) {
                    stateStore.markRetrying(runId, t.getTaskId());
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
                }
                // Reload refreshed task objects (markRetrying clears fields)
                List<AgentTask> toRerun = otherIncomplete.stream()
                    .map(t -> stateStore.getTask(t.getTaskId()))
                    .toList();
                workerPool.runAll(toRerun, runId, props.getRoles().getWorker());
            }

            // Ensure generate_openapi_spec is marked COMPLETE before eligibility checks
            markOpenapiTaskComplete(runId, stateStore.getAllTasks(runId));

            // ── 3. Collect all task reports ────────────────────────────────
            List<AgentTask> allTasks = stateStore.getAllTasks(runId);
            List<WorkerReport> allReports = allTasks.stream()
                .filter(t -> t.getWorkerReportJson() != null)
                .map(t -> {
                    try { return mapper.readValue(t.getWorkerReportJson(), WorkerReport.class); }
                    catch (Exception e) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .toList();

            // ── Tier 8: Compile & Fix (non-fatal, sync before reviewer) ───
            compilerAgent.runCompileAndFix(runId);

            // ── 4. Merge-gate eligibility check ────────────────────────────
            List<AgentTask> workerTasksNow = allTasks.stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            boolean allBlocked = workerTasksNow.stream()
                .allMatch(t -> "BLOCKED".equals(t.getStatus()) || "FAILED".equals(t.getStatus()));
            if (allBlocked) {
                stateStore.markFailed(runId, "All tasks blocked after force-retry");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                broadcaster.complete(runId);
                return;
            }

            boolean allComplete = workerTasksNow.stream()
                .allMatch(t -> "COMPLETE".equals(t.getStatus()));
            if (!allComplete) {
                List<String> stillIncomplete = workerTasksNow.stream()
                    .filter(t -> !"COMPLETE".equals(t.getStatus()))
                    .map(t -> t.getTitle() + " [" + t.getStatus() + "]")
                    .toList();
                String partialReason = "Incomplete tasks: " + String.join("; ", stillIncomplete);
                log.warn("Run {} still has incomplete tasks after retry — {}", runId, partialReason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⚠️ Some tasks still incomplete:\n  • " + String.join("\n  • ", stillIncomplete)
                    + "\n\nRun marked PARTIAL. Use 'Retry Task' on remaining incomplete tasks."));
                stateStore.markPartial(runId, partialReason);
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                broadcaster.complete(runId);
                return;
            }

            // ── 5. Reviewer + Auto-Remediation (up to 3 REWORK cycles) ────
            // Same loop as executeRunAsync: auto-retry all tasks with reviewer feedback
            // injected, repeating up to MAX_REWORK_CYCLES before opening the merge gate.
            final int MAX_RETRY_REWORK_CYCLES = 3;
            int retryReworkCycle = 0;
            ReviewReport review = null;
            boolean retryRework = false;
            List<WorkerReport> currentRetryReports = new ArrayList<>(allReports);
            PipelineRun freshRun = stateStore.findRun(runId).orElse(run);

            while (true) {
                stateStore.markReviewing(runId);
                broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
                freshRun = stateStore.findRun(runId).orElse(freshRun);
                review = reviewerAgent.review(freshRun, currentRetryReports);
                stateStore.saveReview(runId, review);

                retryRework = "REWORK".equalsIgnoreCase(review.getRecommendation());
                if (!retryRework) break; // MERGE or PARTIAL_MERGE — proceed to merge gate

                retryReworkCycle++;
                if (retryReworkCycle > MAX_RETRY_REWORK_CYCLES) {
                    log.warn("Force-retry run {} — REWORK after {} cycles; opening merge gate",
                        runId, MAX_RETRY_REWORK_CYCLES);
                    broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                        "⚠️ Auto-remediation exhausted after " + MAX_RETRY_REWORK_CYCLES + " cycles. "
                        + "Reviewer still recommends REWORK: " + review.getSummary()
                        + "\nOpening merge gate — approve to override, reject to re-queue tasks."));
                    break;
                }

                // Same targeted approach: only re-run tasks the reviewer actually flagged.
                Set<String> failedRetryTaskIds = getFailedTaskIds(review);
                log.info("Force-retry run {} — auto-remediation cycle {}/{}: REWORK — {} — failing tasks: {}",
                    runId, retryReworkCycle, MAX_RETRY_REWORK_CYCLES, review.getSummary(), failedRetryTaskIds);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "🔄 Auto-remediation cycle " + retryReworkCycle + "/" + MAX_RETRY_REWORK_CYCLES
                    + " — Reviewer: " + review.getSummary()
                    + "\nApplying targeted fixes to tasks: " + failedRetryTaskIds));
                broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

                List<AgentTask> workerTasksForRework = stateStore.getAllTasks(runId).stream()
                    .filter(t -> !isSystemTask(t))
                    .filter(t -> failedRetryTaskIds.isEmpty() || failedRetryTaskIds.contains(t.getTaskId()))
                    .toList();
                for (AgentTask t : workerTasksForRework) {
                    String feedback = buildRemediationFeedback(review, t.getTaskId());
                    stateStore.markRetryingWithFeedback(runId, t.getTaskId(), feedback);
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
                }
                List<AgentTask> toRerun = workerTasksForRework.stream()
                    .map(t -> stateStore.getTask(t.getTaskId()))
                    .toList();
                workerPool.runAll(toRerun, runId, props.getRoles().getWorker());
                compilerAgent.runCompileAndFix(runId);

                currentRetryReports = stateStore.getAllTasks(runId).stream()
                    .filter(t -> t.getWorkerReportJson() != null)
                    .map(t -> {
                        try { return mapper.readValue(t.getWorkerReportJson(), WorkerReport.class); }
                        catch (Exception ex) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toList());
            }

            // ── 6. Merge gate ──────────────────────────────────────────────
            broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_MERGE"));
            gateStore.clearMergeGate(runId); // discard any stale key from a previous attempt
            String mergeGateId = stateStore.recordGateOpened(runId, "MERGE_APPROVAL");
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "MERGE_APPROVAL"));

            // Poll Redis every 500 ms — works across any MIG instance
            // InterruptedException propagates to the outer catch block of runRetryAsync
            MergeDecision retryDecision = gateStore.awaitMergeGate(runId);
            stateStore.recordGateResolved(mergeGateId, retryDecision == MergeDecision.MERGE);

            if (retryDecision == MergeDecision.MERGE) {
                // Mark COMPLETE immediately after merge — GitHub push is non-blocking
                List<String> conflictedRetry = mergeService.mergeAll(runId, stateStore.getAllTasks(runId));
                lockTool.releaseAll(runId);

                if (!conflictedRetry.isEmpty()) {
                    stateStore.getAllTasks(runId).stream()
                        .filter(t -> conflictedRetry.contains(t.getBranchName()))
                        .forEach(t -> {
                            stateStore.failTask(t.getTaskId(),
                                "Merge conflict — retry this task to regenerate the changes cleanly.");
                            broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "FAILED"));
                        });
                    String conflictReason = "Merge conflict in " + conflictedRetry.size()
                        + " task branch(es). Retry the highlighted tasks.";
                    stateStore.markPartial(runId, conflictReason);
                    broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + conflictReason));
                    broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, conflictReason));
                    return;
                }

                stateStore.markComplete(runId);
                saveActualCost(runId);
                broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
                log.info("Force-retry run {} complete — merged to main", runId);

                try {
                    pushToGitHubIfConfigured(freshRun);
                } catch (Exception pushEx) {
                    log.warn("GitHub push failed for run {} (run is COMPLETE): {}", runId, pushEx.getMessage());
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                        "⚠️ GitHub push failed (run is COMPLETE): " + pushEx.getMessage()));
                }

                // Publish events — PostMergeOrchestrator and integration adapters handle the rest
                final PipelineRun completedRun = stateStore.findRun(runId).orElse(freshRun);
                boolean hadFailures = currentRetryReports.stream()
                    .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                                   "partial".equalsIgnoreCase(r.getStatus()));
                eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                    runId, completedRun, currentRetryReports, review, hadFailures));
                eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

            } else if (retryDecision == MergeDecision.IMPLEMENT_SUGGESTIONS) {
                // Delegate to doReviewAndMerge which handles the suggestions loop.
                // runRetryAsync does NOT hold a semaphore slot — pass holdsComputeSlot=false.
                boolean ok = doSuggestionImplementation(freshRun);
                if (ok) {
                    final PipelineRun fr = stateStore.findRun(runId).orElse(freshRun);
                    doReviewAndMerge(fr, false);
                }

            } else {
                // DECLINE — always PARTIAL on decline — run is preserved for later merge
                String reason = retryRework
                    ? "Merge declined after REWORK — retry tasks or use 'Reopen Merge Gate' when ready"
                    : "Merge declined by user — run preserved. Use 'Reopen Merge Gate' to merge when ready.";
                stateStore.markPartial(runId, reason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "ℹ️ Merge declined — run is PARTIAL and preserved. "
                    + "Use 'Reopen Merge Gate' to merge when ready."));
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stateStore.markFailed(runId, "Force-retry interrupted: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
        } catch (Exception e) {
            log.error("Force-retry of task {} in run {} failed: {}", taskId, runId, e.getMessage(), e);
            stateStore.markFailed(runId, "Force-retry failed: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
        } finally {
            broadcaster.complete(runId);
            runKeyStore.remove(runId);  // clean up per-run API keys
        }
    }

    // ── Tier 5: Run restart ───────────────────────────────────────────────────

    /**
     * Handles restart requests published by {@link com.navneet.agentteam.recovery.RunRecoveryService}.
     * Submits a new run with the same goal and carries forward the restart counter.
     */
    @EventListener
    public void onRestartRun(PipelineEvent.RestartRun event) {
        log.info("[Recovery] RestartRun event received — original={} attempt={}",
            event.originalRunId(), event.autoRestartCount());
        PipelineRun newRun = submitRun(
            event.userRequest(),
            event.sourceType(),
            event.repoUrl(),
            event.repoMode(),
            null, null   // github credentials not available for auto-restarts
        );
        if (event.autoRestartCount() > 0) {
            newRun.setAutoRestartCount(event.autoRestartCount());
            stateStore.save(newRun);
        }
        broadcaster.emit(event.originalRunId(), RunEvent.log(
            event.originalRunId(), null,
            "🔄 Auto-restart #" + event.autoRestartCount() + " — new run: " + newRun.getRunId()
        ));
    }

    /**
     * Creates and submits a fresh run with the same goal as the original.
     * Called by RunRecoveryService after escalation is exhausted.
     * Returns the new run ID.
     */
    public String restartRun(PipelineRun original) {
        log.info("Restarting run {} (auto-recovery)", original.getRunId());
        PipelineRun newRun = submitRun(
            original.getUserRequest(),
            original.getSourceType() != null ? original.getSourceType() : "RECOVERY",
            original.getRepoUrl(),
            original.getRepoMode(),
            original.getGithubPat(), original.getGithubOwner()   // carry credentials forward
        );
        broadcaster.emit(original.getRunId(), RunEvent.log(
            original.getRunId(), null,
            "🔄 New run started: " + newRun.getRunId()
        ));
        return newRun.getRunId();
    }

    /**
     * Creates and submits a fresh run, carrying the {@code autoRestartCount} forward
     * from the original so the chain terminates at {@code maxAutoRestarts}.
     *
     * <p>Called by {@link com.navneet.agentteam.recovery.RunRecoveryService#autoRestartIfTransient}
     * when a run fails due to transient provider failures (rate-limits, outages, etc.).
     *
     * @param original the failed run whose goal is being retried
     * @return the new run ID
     */
    public String restartRunWithCount(PipelineRun original) {
        int newCount = original.getAutoRestartCount() + 1;
        log.info("Auto-restarting run {} due to transient failure (restart #{})",
            original.getRunId(), newCount);
        PipelineRun newRun = submitRun(
            original.getUserRequest(),
            original.getSourceType() != null ? original.getSourceType() : "RECOVERY",
            original.getRepoUrl(),
            original.getRepoMode(),
            original.getGithubPat(), original.getGithubOwner()   // carry credentials forward
        );
        // Propagate the restart counter so we don't loop forever
        newRun.setAutoRestartCount(newCount);
        stateStore.save(newRun);

        broadcaster.emit(original.getRunId(), RunEvent.log(
            original.getRunId(), null,
            "🔄 Auto-restart #" + newCount + " — new run: " + newRun.getRunId()
        ));
        return newRun.getRunId();
    }

    // ── Tier 7: Integration-triggered run submission ──────────────────────────

    /**
     * Submits a run triggered by an external integration (Jira, Confluence, ServiceNow, PagerDuty).
     * Same as submitRun but sets skipApproval and source on the PipelineRun.
     */
    public PipelineRun submitIntegrationRun(String userRequest, String source, boolean skipApproval) {
        PipelineRun run = submitRun(userRequest, source, null, null, null, null);
        // Set skipApproval flag so executeRunAsync() auto-approves the plan gate
        run.setSkipApproval(skipApproval);
        stateStore.save(run);
        return run;
    }

    // ── File-ownership validation ────────────────────────────────────────────

    /**
     * Validates that no two tasks claim the same file in their owned_files lists.
     * If conflicts are detected, auto-injects dependencies to serialize the conflicting tasks
     * (the task appearing later in the plan depends on the earlier one).
     * This is non-fatal — logs warnings and auto-corrects.
     */
    private void validateFileOwnership(List<AgentTask> workerTasks, String runId) {
        Map<String, List<String>> fileToOwners = new java.util.HashMap<>();
        Map<String, com.navneet.agentteam.agent.model.TaskDefinition> taskDefs = new java.util.HashMap<>();

        for (AgentTask wt : workerTasks) {
            try {
                com.navneet.agentteam.agent.model.TaskDefinition def =
                    mapper.readValue(wt.getTaskJson(), com.navneet.agentteam.agent.model.TaskDefinition.class);
                taskDefs.put(wt.getTaskId(), def);
                if (def.getOwnedFiles() != null) {
                    for (String file : def.getOwnedFiles()) {
                        fileToOwners.computeIfAbsent(file, k -> new java.util.ArrayList<>())
                                    .add(wt.getTaskId());
                    }
                }
            } catch (JsonProcessingException ignore) { /* non-fatal */ }
        }

        List<String> conflicts = fileToOwners.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> e.getKey() + " claimed by: " + e.getValue())
            .toList();

        if (!conflicts.isEmpty()) {
            log.warn("File ownership conflicts detected in plan for run {}: {}", runId, conflicts);
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "⚠️ File ownership overlap detected: " + String.join("; ", conflicts)
                + " — tasks with shared files will run sequentially."));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void initAgentContext(String runId, String planJson) {
        fsTool.writeFile(runId, ".agent-context/task-assignments.json", planJson);
        fsTool.writeFile(runId, ".agent-context/file-locks.json", "{}");
        fsTool.writeFile(runId, ".agent-context/shared-contracts.json", "{}");
        fsTool.writeFile(runId, ".agent-context/CODEBASE_CONTEXT.md", """
            # Workspace Context

            This is a **fresh, isolated workspace** for this agent run. No project exists yet —
            create all files from scratch unless the task says otherwise.

            ## Environment

            - Git is initialised on branch `main` with one empty commit.
            - You are on your own feature branch (shown in your task assignment).
            - The git identity is pre-configured — `git_commit` will work immediately.
            - Available build tools: `mvn` (Maven 3.x), `npm`, `python`.
            - Java version: 21. Spring Boot: 3.3.x (use for Java/Spring projects).

            ## Coding conventions (Java / Spring Boot)

            - Package root: `com.example` (match pom.xml groupId if one exists).
            - Use Java 21 features: records, sealed classes, pattern matching, `var`.
            - Use Lombok: `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`.
            - SLF4J for all logging — no `System.out.println`.
            - REST controllers: `@RestController`, `@RequestMapping`.
            - Tests: JUnit 5 + `@SpringBootTest`.

            ## Git workflow

            - Commit after every file write: git_commit with message "feat(task_id): ..."
            - Do NOT run `git push` — the harness handles pushing.

            ## Constraints

            - Do NOT touch files owned by other tasks (check file-locks.json).
            - Do NOT add dependencies unless the task explicitly requires it.
            - Updating README.md is mandatory before reporting DONE.
            """);
        log.info("Agent context initialized for run {} (.agent-context/)", runId);
    }

    private List<WorkerReport> getWorkerReports(String runId) {
        return stateStore.getAllTasks(runId).stream()
            .filter(t -> t.getWorkerReportJson() != null)
            .map(t -> parseJson(t.getWorkerReportJson(), WorkerReport.class))
            .toList();
    }

    private <T> T parseJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse JSON to " + type.getSimpleName(), e);
        }
    }

    private int countQueued() {
        return (int) stateStore.listRuns("QUEUED",
            org.springframework.data.domain.PageRequest.of(0, 1000)).getTotalElements();
    }

    /**
     * Returns the set of task IDs that the reviewer flagged with unmet acceptance criteria
     * or at least one HIGH severity issue. Only these tasks need to be re-run during
     * auto-remediation — tasks that passed are left untouched.
     *
     * Returns an empty set when the review contains no per-task data (caller falls back
     * to re-running all tasks so we never silently skip a broken run).
     */
    private Set<String> getFailedTaskIds(ReviewReport review) {
        if (review.getTaskReviews() == null || review.getTaskReviews().isEmpty()) {
            return Set.of(); // empty → caller falls back to all tasks
        }
        return review.getTaskReviews().stream()
            .filter(tr -> !tr.isAcceptanceCriteriaMet() ||
                          (tr.getIssues() != null && tr.getIssues().stream()
                               .anyMatch(i -> "HIGH".equalsIgnoreCase(i.getSeverity()))))
            .map(TaskReview::getTaskId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Builds a human-readable remediation feedback string from a REWORK ReviewReport,
     * scoped to a specific task. Injected into the task's TaskDefinition JSON so the
     * worker knows exactly what to fix in the next auto-remediation attempt.
     */
    private String buildRemediationFeedback(ReviewReport review, String taskId) {
        StringBuilder sb = new StringBuilder();

        if (review.getSummary() != null && !review.getSummary().isBlank()) {
            sb.append("Overall: ").append(review.getSummary()).append("\n");
        }

        if (review.getMergeBlockers() != null && !review.getMergeBlockers().isEmpty()) {
            sb.append("\nMerge blockers (MUST FIX):\n");
            review.getMergeBlockers().forEach(b -> sb.append("  - ").append(b).append("\n"));
        }

        if (review.getTaskReviews() != null) {
            review.getTaskReviews().stream()
                .filter(tr -> taskId.equals(tr.getTaskId()))
                .findFirst()
                .ifPresent(tr -> {
                    if (tr.getIssues() != null && !tr.getIssues().isEmpty()) {
                        sb.append("\nIssues in this task:\n");
                        for (ReviewIssue issue : tr.getIssues()) {
                            String loc = (issue.getFile() != null ? " [" + issue.getFile() + "]" : "");
                            sb.append("  - [").append(issue.getSeverity()).append("]")
                              .append(loc).append(" ").append(issue.getDescription()).append("\n");
                        }
                    }
                    if (tr.getBoundaryViolations() != null && !tr.getBoundaryViolations().isEmpty()) {
                        sb.append("\nBoundary violations:\n");
                        tr.getBoundaryViolations().forEach(v -> sb.append("  - ").append(v).append("\n"));
                    }
                });
        }

        return sb.toString().trim();
    }

    /**
     * Implements post-merge suggestions for a run.
     *
     * <p>Identifies tasks that the reviewer flagged with MEDIUM or LOW severity issues,
     * injects suggestion feedback into each task's definition, re-runs those workers,
     * runs the compiler agent, and checks whether all tasks completed successfully.
     *
     * @return {@code true} if all targeted tasks completed (caller should re-review and
     *         re-open the merge gate); {@code false} if any task failed (run is already
     *         marked PARTIAL with a "Suggestions implementation failed" reason).
     */
    private boolean doSuggestionImplementation(PipelineRun run) {
        String runId = run.getRunId();

        // Load the latest review from DB
        PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
        if (freshRun.getReviewJson() == null || freshRun.getReviewJson().isBlank()) {
            log.warn("[Suggestions] No reviewJson for run {} — skipping suggestion implementation", runId);
            return true; // nothing to do
        }
        ReviewReport review = parseJson(freshRun.getReviewJson(), ReviewReport.class);

        Set<String> taskIds = getSuggestionTaskIds(review);
        if (taskIds.isEmpty()) {
            log.info("[Suggestions] No tasks with MEDIUM/LOW issues in run {} — nothing to implement", runId);
            return true;
        }

        int suggCount = review.getPostMergeSuggestions() != null ? review.getPostMergeSuggestions().size() : 0;
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "🔧 Implementing " + suggCount + " suggestion(s) across " + taskIds.size() + " task(s)…"));

        List<AgentTask> toRerun = stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .filter(t -> taskIds.contains(t.getTaskId()))
            .toList();

        for (AgentTask t : toRerun) {
            String feedback = buildSuggestionFeedback(review, t.getTaskId());
            stateStore.markRetryingWithFeedback(runId, t.getTaskId(), feedback);
            broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
        }

        // Reload refreshed task objects (markRetryingWithFeedback resets status/timestamps)
        List<AgentTask> refreshed = toRerun.stream()
            .map(t -> stateStore.getTask(t.getTaskId()))
            .toList();
        workerPool.runAll(refreshed, runId, props.getRoles().getWorker());
        compilerAgent.runCompileAndFix(runId);

        // If any task still failed, mark PARTIAL so user can retry individually
        boolean anyFailed = stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .anyMatch(t -> "FAILED".equals(t.getStatus()) || "PARTIAL".equals(t.getStatus()));

        if (anyFailed) {
            String reason = "Suggestions implementation failed — some tasks could not apply the changes. "
                + "Use 'Retry Implementation' or retry individual failed tasks.";
            stateStore.markPartial(runId, reason);
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + reason));
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
            log.warn("[Suggestions] Implementation failed for run {} — marked PARTIAL", runId);
            return false;
        }

        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "✅ Suggestions implemented successfully — re-running reviewer…"));
        return true;
    }

    /**
     * Returns the IDs of tasks that have at least one MEDIUM or LOW severity issue
     * in the review report. These are the tasks targeted for suggestion implementation.
     */
    private Set<String> getSuggestionTaskIds(ReviewReport review) {
        if (review.getTaskReviews() == null || review.getTaskReviews().isEmpty()) {
            return Set.of();
        }
        return review.getTaskReviews().stream()
            .filter(tr -> tr.getIssues() != null && tr.getIssues().stream()
                .anyMatch(i -> "MEDIUM".equalsIgnoreCase(i.getSeverity())
                            || "LOW".equalsIgnoreCase(i.getSeverity())))
            .map(TaskReview::getTaskId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Builds suggestion feedback for a specific task: global post-merge suggestions
     * plus any MEDIUM/LOW issues found in that task's review entry.
     * Injected into the task's {@code TaskDefinition} via
     * {@link StateStore#markRetryingWithFeedback}.
     */
    private String buildSuggestionFeedback(ReviewReport review, String taskId) {
        StringBuilder sb = new StringBuilder();

        if (review.getPostMergeSuggestions() != null && !review.getPostMergeSuggestions().isEmpty()) {
            sb.append("Post-merge suggestions to address (MEDIUM/LOW issues):\n");
            review.getPostMergeSuggestions().forEach(s -> sb.append("  - ").append(s).append("\n"));
        }

        if (review.getTaskReviews() != null) {
            review.getTaskReviews().stream()
                .filter(tr -> taskId.equals(tr.getTaskId()))
                .findFirst()
                .ifPresent(tr -> {
                    if (tr.getIssues() != null) {
                        List<ReviewIssue> medLow = tr.getIssues().stream()
                            .filter(i -> "MEDIUM".equalsIgnoreCase(i.getSeverity())
                                      || "LOW".equalsIgnoreCase(i.getSeverity()))
                            .toList();
                        if (!medLow.isEmpty()) {
                            sb.append("\nTask-specific improvements:\n");
                            for (ReviewIssue issue : medLow) {
                                sb.append("  - [").append(issue.getSeverity()).append("]");
                                if (issue.getFile() != null) sb.append(" [").append(issue.getFile()).append("]");
                                sb.append(" ").append(issue.getDescription()).append("\n");
                            }
                        }
                    }
                });
        }

        return sb.toString().trim();
    }

    /**
     * Returns {@code true} for tasks that are handled by system agents (not WorkerAgent)
     * and must be excluded from the worker task pool and partial-completion checks.
     *
     * <ul>
     *   <li>{@value PreviewAgent#PREVIEW_TASK_ID} — handled by {@link PreviewAgent}.</li>
     *   <li>{@value OPENAPI_TASK_ID} — retired; spec generated post-merge by ApiTestSuiteAgent.</li>
     * </ul>
     */
    private boolean isSystemTask(com.navneet.agentteam.state.entity.AgentTask t) {
        return PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId())
            || OPENAPI_TASK_ID.equals(t.getTaskId());
    }

    /**
     * Auto-completes the {@code generate_openapi_spec} task (if present) so it does not
     * block partial-completion checks or appear as incomplete to the reviewer.
     *
     * <p>The actual spec is generated post-merge by ApiTestSuiteAgent.  This is called
     * immediately after {@code workerPool.runAll()} so the task moves to COMPLETE before
     * the all-tasks-complete gate check.
     */
    private void markOpenapiTaskComplete(String runId, List<com.navneet.agentteam.state.entity.AgentTask> tasks) {
        tasks.stream()
            .filter(t -> OPENAPI_TASK_ID.equals(t.getTaskId()))
            .findFirst()
            .ifPresent(t -> {
                String model  = t.getAssignedModel() != null ? t.getAssignedModel() : "claude-haiku-4-5-20251001";
                String branch = "agent/" + runId.substring(0, 8) + "/" + OPENAPI_TASK_ID;
                if (!stateStore.lockTask(t.getTaskId(), model, branch)) {
                    // Already locked (may be a retry) — still ensure it is COMPLETE
                    log.debug("[Harness] {} already locked for run {} — skipping auto-complete lock",
                        OPENAPI_TASK_ID, runId);
                }
                WorkerReport report = WorkerReport.builder()
                    .taskId(OPENAPI_TASK_ID)
                    .status("complete")
                    .filesCreated(List.of())
                    .filesModified(List.of())
                    .filesRead(List.of())
                    .selfReview("OpenAPI spec is auto-generated post-merge by ApiTestSuiteAgent. No worker action needed.")
                    .build();
                stateStore.completeTask(t.getTaskId(), report);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "COMPLETE"));
                log.info("[Harness] {} auto-completed for run {} (spec generated post-merge)", OPENAPI_TASK_ID, runId);
            });
    }

    /**
     * Clears {@code currentGate} on a run immediately (synchronously), so the UI
     * banner disappears on the next poll without waiting for the async pipeline thread.
     * Both {@link #resolvePlanGate} and {@link #resolveMergeGate} share this fix.
     */
    private void clearGateSync(String runId) {
        stateStore.findRun(runId).ifPresent(r -> { r.setCurrentGate(null); stateStore.save(r); });
    }
}
