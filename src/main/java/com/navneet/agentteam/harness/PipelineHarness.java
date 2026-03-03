package com.navneet.agentteam.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.CostEstimator;
import com.navneet.agentteam.agent.PlannerAgent;
import com.navneet.agentteam.agent.ReviewerAgent;
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

    // ── Async infrastructure ──────────────────────────────────────────────────

    /** Limits simultaneous active pipeline runs. */
    private Semaphore runSlots;
    /** Thread pool for async pipeline execution (unbounded — threads block on semaphore). */
    private ExecutorService executor;
    /** CompletableFutures awaiting plan-approval REST calls. */
    private final Map<String, CompletableFuture<Boolean>> planGates    = new ConcurrentHashMap<>();
    /** CompletableFutures awaiting merge-approval REST calls. */
    private final Map<String, CompletableFuture<Boolean>> mergeGates   = new ConcurrentHashMap<>();
    /** Gate-event IDs for plan approval, used to record resolution. */
    private final Map<String, String> planGateEventIds  = new ConcurrentHashMap<>();
    /** Gate-event IDs for merge approval, used to record resolution. */
    private final Map<String, String> mergeGateEventIds = new ConcurrentHashMap<>();

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
            List<WorkerReport> reports = workerPool.runAll(tasks, run.getRunId(),
                props.getRoles().getWorker());

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
                                  String repoUrl, String repoMode) {
        PipelineRun run = stateStore.createRun(userRequest, source, repoUrl, repoMode,
            props.getRoles().getPlanner(),
            props.getRoles().getWorker(),
            props.getRoles().getReviewer());

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
     * @return false if no pending gate exists for the runId
     */
    public boolean resolvePlanGate(String runId, boolean approved, String comment) {
        CompletableFuture<Boolean> gate = planGates.remove(runId);
        if (gate == null) {
            // No live gate — server may have restarted and lost in-memory state.
            // If the run is still stuck in AWAITING_APPROVAL, mark it FAILED so the
            // user can resubmit, then signal the controller to return 409 (not 404).
            stateStore.findRun(runId).ifPresent(run -> {
                if ("AWAITING_APPROVAL".equals(run.getStatus())) {
                    log.warn("[Gate] Plan gate lost for run {} (server restart?); auto-failing stuck run", runId);
                    stateStore.markFailed(runId, "Plan-approval gate lost after server restart — please resubmit");
                    broadcaster.emit(runId, RunEvent.runStatus(runId, "FAILED"));
                }
            });
            return false;
        }
        String gateEventId = planGateEventIds.remove(runId);
        if (gateEventId != null) stateStore.recordGateResolved(gateEventId, approved);
        // Clear currentGate immediately so the UI banner disappears on next poll.
        // (markApproved() also clears it but runs asynchronously on the pipeline thread,
        //  causing a race where the frontend refetch still sees the old gate value.)
        stateStore.findRun(runId).ifPresent(r -> { r.setCurrentGate(null); stateStore.save(r); });
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("SSE emit failed on plan gate resolve for run {} — client likely disconnected", runId);
        }
        gate.complete(approved);   // must run even if SSE fails
        return true;
    }

    /**
     * Resolves the merge-approval gate for a REST-submitted run.
     * @return false if no pending gate exists for the runId
     */
    public boolean resolveMergeGate(String runId, boolean approved, String comment) {
        CompletableFuture<Boolean> gate = mergeGates.remove(runId);
        if (gate == null) {
            stateStore.findRun(runId).ifPresent(run -> {
                if ("AWAITING_MERGE".equals(run.getStatus())) {
                    log.warn("[Gate] Merge gate lost for run {} (server restart?); auto-failing stuck run", runId);
                    stateStore.markFailed(runId, "Merge gate lost after server restart — please resubmit");
                    broadcaster.emit(runId, RunEvent.runStatus(runId, "FAILED"));
                }
            });
            return false;
        }
        String gateEventId = mergeGateEventIds.remove(runId);
        if (gateEventId != null) stateStore.recordGateResolved(gateEventId, approved);
        // Clear currentGate immediately — same race-condition fix as resolvePlanGate.
        stateStore.findRun(runId).ifPresent(r -> { r.setCurrentGate(null); stateStore.save(r); });
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("SSE emit failed on merge gate resolve for run {} — client likely disconnected", runId);
        }
        gate.complete(approved);   // must run even if SSE fails
        return true;
    }

    /** Cancels a run. Returns false if run is already terminal. */
    public boolean cancelRun(String runId) {
        // Complete any open gates with rejection so the pipeline thread unblocks
        CompletableFuture<Boolean> pg = planGates.remove(runId);
        if (pg != null) pg.complete(false);
        CompletableFuture<Boolean> mg = mergeGates.remove(runId);
        if (mg != null) mg.complete(false);

        boolean cancelled = stateStore.cancelRun(runId);
        if (cancelled) {
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            broadcaster.complete(runId);
        }
        return cancelled;
    }

    // ── Internal async pipeline execution ────────────────────────────────────

    private void executeRunAsync(String runId, String userRequest) {
        log.info("Executing async run {}", runId);

        // Initialise this run's isolated workspace (workspace/{runId}/) before doing anything else
        workspaceInitService.ensureRunWorkspace(runId);

        PipelineRun run = stateStore.findRun(runId).orElseThrow();
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
            planGateEventIds.put(runId, planGateId);
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "PLAN_APPROVAL"));

            CompletableFuture<Boolean> planGate = new CompletableFuture<>();
            planGates.put(runId, planGate);

            boolean planApproved;
            // Tier 7: integration-triggered runs may skip the human approval gate
            if (run.isSkipApproval()) {
                planApproved = true;
                planGates.remove(runId);
                String gateEventId = planGateEventIds.remove(runId);
                if (gateEventId != null) stateStore.recordGateResolved(gateEventId, true);
                broadcaster.emit(runId, RunEvent.gateResolved(runId, true, "Auto-approved (integration run)"));
                log.info("Plan gate auto-approved for integration run {}", runId);
            } else {
                // No timeout — run stays AWAITING_APPROVAL until human acts.
                planApproved = planGate.get();
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
                .filter(t -> !PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId()))
                .toList();

            List<WorkerReport> reports = workerPool.runAll(workerTasks, runId,
                props.getRoles().getWorker());

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
                .filter(t -> !PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId()))
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
                    .filter(t -> !PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId()))
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

            broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_MERGE"));

            // ── 5. Merge gate ──────────────────────────────────────────────
            String mergeGateId = stateStore.recordGateOpened(runId, "MERGE_APPROVAL");
            mergeGateEventIds.put(runId, mergeGateId);
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "MERGE_APPROVAL"));

            CompletableFuture<Boolean> mergeGate = new CompletableFuture<>();
            mergeGates.put(runId, mergeGate);

            // No timeout — run stays AWAITING_APPROVAL until human acts.
            boolean mergeApproved = mergeGate.get();

            if (mergeApproved) {
                // Bug fix: merge first, then mark COMPLETE immediately so that any
                // subsequent failure (GitHub push, cost save) does NOT revert the run
                // to FAILED — the code IS successfully merged at this point.
                mergeService.mergeAll(runId, tasks);
                lockTool.releaseAll(runId);
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
            } else {
                // Bug fix: when the reviewer recommended REWORK and the user declines
                // the merge, keep the run as PARTIAL so tasks can be retried.
                // A plain rejection (reviewer said APPROVE but user chose not to merge)
                // still fails the run.
                if (reviewerRework) {
                    String reason = "Merge declined after REWORK review — retry individual tasks to address reviewer concerns";
                    log.info("Async run {} — merge declined after REWORK; marking PARTIAL", runId);
                    stateStore.markPartial(runId, reason);
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                        "ℹ️ Run marked PARTIAL. Reviewer recommended REWORK. "
                        + "Use 'Retry Task' on each incomplete task to address the issues, then the merge gate will reopen."));
                    broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                } else {
                    stateStore.markFailed(runId, "Merge declined by user");
                    broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Merge declined by user"));
                    log.info("Async run {} — merge declined", runId);
                }
            }

        } catch (MergeBlockedException e) {
            // README.md was not updated by one of the worker branches — this is a
            // recoverable condition. Keep the run as PARTIAL so the user can retry
            // the specific task that missed the README update.
            log.warn("Merge blocked for run {}: {}", runId, e.getMessage());
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "⛔ " + e.getMessage()
                + "\n\nRun marked PARTIAL — retry the task that missed the README update."));
            stateStore.markPartial(runId, e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
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
        List<WorkerReport> reports = workerPool.runAll(incomplete, run.getRunId(),
            props.getRoles().getWorker());
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
            mergeService.mergeAll(run.getRunId(), stateStore.getAllTasks(run.getRunId()));
            pushToGitHubIfConfigured(run);
            lockTool.releaseAll(run.getRunId());
            stateStore.markComplete(run.getRunId());
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

        String pat = props.getGithub() != null ? props.getGithub().getPat() : null;
        if (pat == null || pat.isBlank()) {
            log.warn("GitHub PAT not configured — skipping push for run {}", run.getRunId());
            return;
        }

        String remote = repoUrl;
        if ("NEW".equals(repoMode) && (repoUrl == null || repoUrl.isBlank())) {
            log.info("Creating new GitHub repo for run {}", run.getRunId());
            remote = gitHubRepoService.createRepo(run.getUserRequest(), null, "private");
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
        if (!"PARTIAL".equals(ts) && !"BLOCKED".equals(ts) && !"FAILED".equals(ts)) {
            throw new IllegalStateException(
                "Task " + taskId + " is not retryable (status: " + ts
                + "). Only PARTIAL, BLOCKED, or FAILED tasks can be retried.");
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
        try {
            // ── 1. Lock + run the retried worker task ──────────────────────
            String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;
            stateStore.lockTask(taskId, props.getRoles().getWorker(), branchName);
            WorkerReport retryReport = workerPool.executeTask(task);

            broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId,
                "complete".equalsIgnoreCase(retryReport.getStatus())
                    ? "COMPLETE" : retryReport.getStatus().toUpperCase()));

            // ── 2. Auto-run any other PARTIAL/BLOCKED worker tasks ─────────
            // After one task is retried, check whether other worker tasks are
            // still incomplete. If so, reset and run them all before proceeding
            // to reviewer — otherwise the merge gate would open prematurely again.
            List<AgentTask> otherIncomplete = stateStore.getAllTasks(runId).stream()
                .filter(t -> !taskId.equals(t.getTaskId()))
                .filter(t -> !PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId()))
                .filter(t -> "PARTIAL".equals(t.getStatus()) || "BLOCKED".equals(t.getStatus())
                             || "FAILED".equals(t.getStatus()))
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
                .filter(t -> !PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId()))
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
                    .filter(t -> !PreviewAgent.PREVIEW_TASK_ID.equals(t.getTaskId()))
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
            String mergeGateId = stateStore.recordGateOpened(runId, "MERGE_APPROVAL");
            mergeGateEventIds.put(runId, mergeGateId);
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "MERGE_APPROVAL"));

            CompletableFuture<Boolean> mergeGate = new CompletableFuture<>();
            mergeGates.put(runId, mergeGate);
            boolean mergeApproved = mergeGate.get();

            if (mergeApproved) {
                // Mark COMPLETE immediately after merge — GitHub push is non-blocking
                mergeService.mergeAll(runId, allTasks);
                lockTool.releaseAll(runId);
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
            } else {
                // REWORK rejection after exhausting auto-remediation → PARTIAL; plain rejection → FAILED
                if (retryRework) {
                    String reason = "Merge declined after REWORK — retry tasks to address reviewer concerns";
                    stateStore.markPartial(runId, reason);
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                        "ℹ️ Run marked PARTIAL. Use 'Retry Task' on each task to fix the reviewer's concerns."));
                    broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                } else {
                    stateStore.markFailed(runId, "Merge declined by user");
                    broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Merge declined by user"));
                }
            }

        } catch (MergeBlockedException e) {
            log.warn("Merge blocked for force-retry run {}: {}", runId, e.getMessage());
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "⛔ " + e.getMessage()
                + "\n\nRun marked PARTIAL — retry the task that missed the README update."));
            stateStore.markPartial(runId, e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
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
            event.repoMode()
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
            original.getRepoMode()
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
            original.getRepoMode()
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
        PipelineRun run = submitRun(userRequest, source, null, null);
        // Set skipApproval flag so executeRunAsync() auto-approves the plan gate
        run.setSkipApproval(skipApproval);
        stateStore.save(run);
        return run;
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
}
