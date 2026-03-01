package com.navneet.agentteam.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.agentteam.agent.PlannerAgent;
import com.navneet.agentteam.agent.ReviewerAgent;
import com.navneet.agentteam.agent.model.ReviewReport;
import com.navneet.agentteam.agent.model.TaskPlan;
import com.navneet.agentteam.agent.model.WorkerReport;
import com.navneet.agentteam.config.AgentProperties;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineHarness {

    private final PlannerAgent plannerAgent;
    private final WorkerPool workerPool;
    private final ReviewerAgent reviewerAgent;
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

    // ── Tier 8: Compile agent ─────────────────────────────────────────────────
    private final com.navneet.agentteam.compiler.CompilerAgent compilerAgent;

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
            TaskPlan plan = plannerAgent.plan(userRequest, run.getRunId());
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
        if (gate == null) return false;
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
        if (gate == null) return false;
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
            TaskPlan plan = plannerAgent.plan(userRequest, runId);
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

            List<WorkerReport> reports = workerPool.runAll(tasks, runId,
                props.getRoles().getWorker());

            // ── Tier 8: Compile & Fix (non-fatal, sync before reviewer) ───
            compilerAgent.runCompileAndFix(runId);

            // ── 4. Review ──────────────────────────────────────────────────
            stateStore.markReviewing(runId);
            broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
            run = stateStore.findRun(runId).orElse(run);
            ReviewReport review = reviewerAgent.review(run, reports);
            stateStore.saveReview(runId, review);

            // ── Auto-fail guard ────────────────────────────────────────────
            // Auto-fail ONLY when all tasks are BLOCKED/FAILED — there is nothing
            // to merge so opening the gate would be meaningless.
            // REWORK recommendations are intentionally NOT auto-failed: we open the
            // merge gate and let the human decide (approve = override reviewer and
            // merge anyway; reject = fail the run).  The reviewer's full feedback is
            // visible in the run detail panel.
            String recommendation = review.getRecommendation();
            boolean reviewerRework = "REWORK".equalsIgnoreCase(recommendation);
            boolean allTasksBlocked = stateStore.getAllTasks(runId).stream()
                .allMatch(t -> "BLOCKED".equals(t.getStatus()) || "FAILED".equals(t.getStatus()));

            if (allTasksBlocked) {
                String reason = "All tasks blocked or failed — nothing to merge";
                log.warn("Auto-failing run {} — {}", runId, reason);
                stateStore.markFailed(runId, reason);
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                PipelineRun failedRun = stateStore.findRun(runId).orElse(run);
                recoveryService.autoRestartIfTransient(failedRun, reports);
                return;
            }

            // If the reviewer recommends REWORK, surface a clear warning in the log
            // stream before the merge gate opens so the human knows what to review.
            if (reviewerRework) {
                log.warn("Run {} — reviewer recommends REWORK: {}", runId, review.getSummary());
                broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                    "⚠️ Reviewer recommends REWORK: " + review.getSummary()
                    + "\nOpening merge gate — approve to override, reject to cancel."));
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
                mergeService.mergeAll(runId, tasks);
                pushToGitHubIfConfigured(run);
                lockTool.releaseAll(runId);
                stateStore.markComplete(runId);
                broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
                log.info("Async run {} complete — merged to main", runId);

                // Publish MergeCompleted — PostMergeOrchestrator handles post-merge agents
                final PipelineRun completedRun = stateStore.findRun(runId).orElse(run);
                boolean hadFailures = reports.stream()
                    .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                                   "partial".equalsIgnoreCase(r.getStatus()));
                eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                    runId, completedRun, reports, review, hadFailures));
                // Publish RunCompleted for integration outbound adapters
                eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
            } else {
                stateStore.markFailed(runId, "Merge declined by user");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Merge declined by user"));
                log.info("Async run {} — merge declined", runId);
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
        if (!"PARTIAL".equals(ts) && !"BLOCKED".equals(ts)) {
            throw new IllegalStateException("Task " + taskId + " is not retryable (status: " + ts + ")");
        }
        String rs = run.getStatus();
        if (!"PARTIAL".equals(rs) && !"FAILED".equals(rs)) {
            throw new IllegalStateException("Run " + runId + " cannot be retried in state " + rs);
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
            // ── 1. Lock + run worker ───────────────────────────────────────
            String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;
            stateStore.lockTask(taskId, props.getRoles().getWorker(), branchName);
            WorkerReport report = workerPool.executeTask(task);

            broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId,
                "complete".equalsIgnoreCase(report.getStatus()) ? "COMPLETE" : report.getStatus().toUpperCase()));

            // ── 2. Collect all task reports ────────────────────────────────
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

            // ── 3. Reviewer ────────────────────────────────────────────────
            stateStore.markReviewing(runId);
            broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
            PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
            ReviewReport review = reviewerAgent.review(freshRun, allReports);
            stateStore.saveReview(runId, review);

            boolean allBlocked = allTasks.stream()
                .allMatch(t -> "BLOCKED".equals(t.getStatus()) || "FAILED".equals(t.getStatus()));
            if (allBlocked) {
                stateStore.markFailed(runId, "All tasks blocked after force-retry");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                broadcaster.complete(runId);
                return;
            }
            if ("REWORK".equalsIgnoreCase(review.getRecommendation())) {
                broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                    "⚠️ Reviewer recommends REWORK: " + review.getSummary()
                    + "\nOpening merge gate — approve to override, reject to cancel."));
            }

            // ── 4. Merge gate ──────────────────────────────────────────────
            broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_MERGE"));
            String mergeGateId = stateStore.recordGateOpened(runId, "MERGE_APPROVAL");
            mergeGateEventIds.put(runId, mergeGateId);
            broadcaster.emit(runId, RunEvent.gateOpened(runId, "MERGE_APPROVAL"));

            CompletableFuture<Boolean> mergeGate = new CompletableFuture<>();
            mergeGates.put(runId, mergeGate);
            boolean mergeApproved = mergeGate.get();

            if (mergeApproved) {
                mergeService.mergeAll(runId, allTasks);
                pushToGitHubIfConfigured(freshRun);
                lockTool.releaseAll(runId);
                stateStore.markComplete(runId);
                String finalStatus = stateStore.findRun(runId)
                    .map(PipelineRun::getStatus).orElse("COMPLETE");
                broadcaster.emit(runId, RunEvent.complete(runId, finalStatus));
                log.info("Force-retry run {} complete — final status {}", runId, finalStatus);

                // Publish events — PostMergeOrchestrator and integration adapters handle the rest
                final PipelineRun completedRun = stateStore.findRun(runId).orElse(freshRun);
                boolean hadFailures = allReports.stream()
                    .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                                   "partial".equalsIgnoreCase(r.getStatus()));
                eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                    runId, completedRun, allReports, review, hadFailures));
                eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
            } else {
                stateStore.markFailed(runId, "Merge declined by user");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Merge declined by user"));
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
}
