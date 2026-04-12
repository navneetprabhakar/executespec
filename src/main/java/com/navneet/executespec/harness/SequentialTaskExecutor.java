package com.navneet.executespec.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.compiler.CompilerAgent;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.router.*;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.GitTool;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.navneet.executespec.config.CustomMetricsConfig.TASK_DURATION;
import static com.navneet.executespec.config.CustomMetricsConfig.TASK_TOTAL;

/**
 * Executes worker tasks in <b>tiered parallel</b> order based on the dependency graph.
 *
 * <p>Tasks are grouped into tiers: tier 0 = no dependencies, tier N = deepest
 * dependency in tier N-1.  All tasks in the same tier run <b>concurrently</b>
 * in separate virtual threads with isolated git worktrees.  After a tier
 * completes, branches merge to main and the next tier starts.
 *
 * <p>Per-task execution flow:
 * <ol>
 *   <li>Branch creation + lockTask (persists branch name to DB)</li>
 *   <li>Model selection based on agent tier (via ModelResolver)</li>
 *   <li>Worker execution in isolated worktree (via WorkerAgent.execute())</li>
 *   <li>Progressive merge to main (under lock, sequential within tier)</li>
 * </ol>
 *
 * <p>Code validation is deferred to the review phase (compiler agent + reviewer post-merge).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequentialTaskExecutor {

    private final WorkerAgent workerAgent;
    private final ModelResolver modelResolver;
    private final ModelTaskMetricRepository metricRepository;
    private final AvailableProviderResolver providerResolver;
    private final StateStore stateStore;
    private final SseBroadcaster sseBroadcaster;
    private final GitTool gitTool;
    private final GitMergeService mergeService;
    private final ObjectMapper mapper;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;
    private final AgentProperties agentProperties;
    private final RunBudgetTracker runBudgetTracker;


    private final RunCostBudgetService runCostBudgetService;
    private final CompilerAgent compilerAgent;
    private final SubtaskDecomposer subtaskDecomposer;
    private final ProgressiveMergeValidator mergeValidator;
    private final AdaptiveIterationService adaptiveIterationService;
    private final TaskCompileSupervisor taskCompileSupervisor;

    /**
     * Result of sequential execution across all tasks.
     */
    public record SequentialExecutionResult(
            List<WorkerReport> reports,
            List<AgentTask> completedTasks,
            List<AgentTask> failedTasks,
            boolean budgetExceeded
    ) {
        /** Convenience constructor for the common non-budget-exceeded case. */
        public SequentialExecutionResult(List<WorkerReport> reports,
                                         List<AgentTask> completedTasks,
                                         List<AgentTask> failedTasks) {
            this(reports, completedTasks, failedTasks, false);
        }

        public boolean hasFailures() {
            return failedTasks != null && !failedTasks.isEmpty();
        }

        public int totalTasks() {
            return (completedTasks != null ? completedTasks.size() : 0)
                    + (failedTasks != null ? failedTasks.size() : 0);
        }
    }

    /**
     * Execute all tasks using tiered parallel execution.
     * Tasks are grouped into tiers by dependency depth; all tasks in the same
     * tier run concurrently in virtual threads.  After each tier completes,
     * branches merge to main and the next tier starts.
     */
    public SequentialExecutionResult execute(PipelineRun run, List<AgentTask> tasks) {
        return execute(run, tasks, run.getExecutionModeEnum());
    }

    public SequentialExecutionResult execute(PipelineRun run, List<AgentTask> tasks, ExecutionMode mode) {
        List<WorkerReport> allReports = Collections.synchronizedList(new ArrayList<>());
        List<AgentTask> completed = Collections.synchronizedList(new ArrayList<>());
        List<AgentTask> failed = Collections.synchronizedList(new ArrayList<>());
        List<AgentTask> previouslyMerged = Collections.synchronizedList(new ArrayList<>());
        Set<String> mergedTaskIds = ConcurrentHashMap.newKeySet();
        boolean budgetExceededBetweenTasks = false;
        String runId = run.getRunId();
        String defaultModel = run.getWorkerModel();

        // Model selection now handled by ModelResolver — no tier ceiling concept

        // Resolve available providers for this run's org (API key-aware routing)
        Set<LlmProvider> availableProviders = providerResolver.resolve(runId);

        // Pre-build taskId → owned files map for context-aware subtask decomposition (RC6)
        Map<String, Set<String>> taskOwnedFilesMap = buildTaskOwnedFilesMap(tasks);

        // Compute execution tiers from dependency graph
        List<List<AgentTask>> tiers = computeTiers(tasks);

        log.info("Starting tiered execution of {} tasks in {} tiers for run {} (providers: {})",
                tasks.size(), tiers.size(), runId, availableProviders);
        for (int tierIdx = 0; tierIdx < tiers.size(); tierIdx++) {
            log.info("  Tier {}: {}", tierIdx, tiers.get(tierIdx).stream()
                    .map(AgentTask::getTaskId).toList());
        }

        // Merge lock: merges to main must be sequential to avoid branch conflicts
        Object mergeLock = new Object();

        for (int tierIdx = 0; tierIdx < tiers.size(); tierIdx++) {
            // Check for cancellation before starting each tier
            if (Thread.currentThread().isInterrupted()) {
                log.info("Pipeline thread interrupted before tier {} — aborting execution for run {}", tierIdx, runId);
                break;
            }
            // Also check DB status in case cancellation happened between tiers
            PipelineRun freshRun = stateStore.findRun(runId).orElse(null);
            if (freshRun != null && "CANCELLED".equals(freshRun.getStatus())) {
                log.info("Run {} is CANCELLED — aborting execution at tier {}", runId, tierIdx);
                break;
            }

            List<AgentTask> tierTasks = tiers.get(tierIdx);

            Observation tierObs = Observation.createNotStarted("executespec.tier", observationRegistry)
                    .lowCardinalityKeyValue("tier.index", String.valueOf(tierIdx))
                    .lowCardinalityKeyValue("run.id", runId)
                    .lowCardinalityKeyValue("tier.task.count", String.valueOf(tierTasks.size()))
                    .start();
            try {

            // Filter out tasks whose dependencies have failed
            List<AgentTask> executable = new ArrayList<>();
            for (AgentTask task : tierTasks) {
                if (hasDependencyFailure(task, failed)) {
                    log.warn("Skipping task {} — dependency failed", task.getTaskId());
                    stateStore.blockTask(task.getTaskId(), "Dependency task failed");
                    sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "BLOCKED"));
                    failed.add(task);
                } else {
                    executable.add(task);
                }
            }

            if (executable.isEmpty()) continue;

            sseBroadcaster.emit(runId, RunEvent.log(runId, executable.get(0).getTaskId(),
                    String.format("Tier %d: starting %d task(s)%s",
                            tierIdx, executable.size(),
                            executable.size() > 1 ? " in parallel" : "")));

            if (executable.size() == 1) {
                // Single task in tier — run directly on current thread (no thread overhead)
                executeSingleTask(executable.get(0), run, defaultModel,
                        availableProviders, allReports, completed, failed,
                        previouslyMerged, mergedTaskIds, mergeLock, taskOwnedFilesMap);
            } else {
                // Multiple independent tasks — run in parallel using virtual threads
                List<Thread> threads = new ArrayList<>();
                for (AgentTask task : executable) {
                    final int tier = tierIdx;
                    // Capture observation context so virtual threads propagate trace IDs
                    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
                    Thread vt = Thread.ofVirtual()
                            .name("worker-tier" + tier + "-" + task.getTaskId())
                            .start(snapshot.wrap(() -> executeSingleTask(task, run, defaultModel,
                                    availableProviders, allReports, completed, failed,
                                    previouslyMerged, mergedTaskIds, mergeLock, taskOwnedFilesMap)));
                    threads.add(vt);
                }

                // Wait for all tasks in this tier — bounded by maxTaskDurationMinutes to prevent infinite stalls
                long joinTimeoutMs = TimeUnit.MINUTES.toMillis(
                        agentProperties.getConcurrency().getMaxTaskDurationMinutes() + 2); // +2 min grace
                for (Thread vt : threads) {
                    try {
                        vt.join(joinTimeoutMs);
                        if (vt.isAlive()) {
                            log.error("Worker thread {} exceeded {}min timeout — interrupting",
                                    vt.getName(), agentProperties.getConcurrency().getMaxTaskDurationMinutes());
                            vt.interrupt();
                            // Give it 10s to clean up after interrupt
                            vt.join(TimeUnit.SECONDS.toMillis(10));
                            if (vt.isAlive()) {
                                log.error("Worker thread {} still alive after interrupt — abandoning", vt.getName());
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted waiting for tier {} to complete", tierIdx);
                        break;
                    }
                }
            }

            log.info("Tier {} complete: {}/{} tasks succeeded overall, {} failed",
                    tierIdx, completed.size(), tasks.size(), failed.size());

            // Post-tier compile check: catch and auto-fix syntax errors before next tier starts (2 passes max)
            if (!mergedTaskIds.isEmpty()) {
                String syntaxError = compilerAgent.quickCompileAndFix(runId);
                if (syntaxError != null) {
                    log.warn("Post-tier {} compile still failing for run {} after auto-fix — consulting supervisor",
                            tierIdx, runId);
                    // Supervisor provides insight/metrics; structural errors deferred to final compile phase
                    List<AgentTask> currentTierMerged = tiers.get(tierIdx).stream()
                            .filter(t -> mergedTaskIds.contains(t.getTaskId()))
                            .toList();
                    if (!currentTierMerged.isEmpty()) {
                        TaskCompileDecision tierDecision = taskCompileSupervisor.superviseTaskGroup(
                                run, currentTierMerged.get(0), currentTierMerged,
                                syntaxError, mergedTaskIds, availableProviders);
                        if (tierDecision.action() == SupervisionAction.RETRY_TASK) {
                            sseBroadcaster.emit(run.getRunId(), RunEvent.log(run.getRunId(), "pipeline",
                                    "⚠️ Tier " + tierIdx + " structural errors detected (fixability: "
                                    + tierDecision.fixabilityScore() + "/100) — deferred to final compile phase"));
                        }
                    }
                }
            }

            // Inter-tier progression stall check: warn user if spending fast but completing slowly.
            // This is NOT a hard stop — emits a warning SSE so the user can cancel before hitting the ceiling.
            if (runCostBudgetService.isProgressionStalled(runId, completed.size(), tasks.size())) {
                String tier = runCostBudgetService.getRunTier(runId);
                Double budget = runCostBudgetService.getBudget(runId);
                double currentCost = runCostBudgetService.getCurrentCost(runId);
                log.warn("[CostBudget] Run {} progression stalled: {}/{} tasks done at ${} spent — emitting warning",
                        runId, completed.size(), tasks.size(), String.format("%.2f", currentCost));
                sseBroadcaster.emit(runId, RunEvent.log(runId, "pipeline",
                        String.format("⚠️ COST_STALL_WARNING: $%.2f spent but only %d/%d tasks complete. " +
                                "Workers may be stuck in read loops. Consider cancelling to avoid further cost.",
                                currentCost, completed.size(), tasks.size())));
            }

            // Inter-tier cost budget check: stop before starting next tier if hard cap reached
            if (runCostBudgetService.isOverBudget(runId)) {
                String tier = runCostBudgetService.getRunTier(runId);
                Double budget = runCostBudgetService.getBudget(runId);
                double currentCost = runCostBudgetService.getCurrentCost(runId);
                log.warn("[CostBudget] Run {} exceeded ${} budget between tiers (current=${}) — stopping pipeline",
                        runId, budget != null ? String.format("%.2f", budget) : "?",
                        String.format("%.4f", currentCost));
                sseBroadcaster.emit(runId, RunEvent.budgetExceeded(runId, tier,
                        budget != null ? budget : 0.0));
                budgetExceededBetweenTasks = true;
                break;
            }

            } finally {
                tierObs.stop();
            }
        }

        log.info("Tiered execution complete: {}/{} tasks succeeded (merged: {}, budgetExceeded={})",
                completed.size(), tasks.size(), mergedTaskIds, budgetExceededBetweenTasks);
        return new SequentialExecutionResult(allReports, completed, failed, budgetExceededBetweenTasks);
    }

    /**
     * Execute a single task: lock → run worker with validation → merge to main.
     * Thread-safe — can be called from multiple virtual threads concurrently.
     */
    private void executeSingleTask(AgentTask task, PipelineRun run, String defaultModel,
                                    Set<LlmProvider> availableProviders,
                                    List<WorkerReport> allReports, List<AgentTask> completed,
                                    List<AgentTask> failed, List<AgentTask> previouslyMerged,
                                    Set<String> mergedTaskIds, Object mergeLock,
                                    Map<String, Set<String>> taskOwnedFilesMap) {
        String runId = run.getRunId();
        String taskId = task.getTaskId();

        Instant taskMetricsStart = Instant.now();
        String metricsTaskOutcome = "UNKNOWN";
        Observation taskObs = Observation.createNotStarted("executespec.task", observationRegistry)
                .lowCardinalityKeyValue("task.id", taskId)
                .lowCardinalityKeyValue("run.id", runId)
                .lowCardinalityKeyValue("task.complexity", task.getComplexity() != null ? task.getComplexity() : "MEDIUM")
                .start();
        try {

        log.info("Executing task: {} [complexity={}]",
                taskId, task.getComplexity() != null ? task.getComplexity() : "MEDIUM");

        // === Lock task + set branch name (persists to DB) ===
        String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;
        String taskModel = task.getAssignedModel() != null
                ? task.getAssignedModel() : defaultModel;

        if (!stateStore.lockTask(taskId, taskModel, branchName)) {
            log.warn("Task {} already locked — skipping", taskId);
            completed.add(task);
            return;
        }
        // lockTask persists branchName to DB but doesn't update the in-memory
        // AgentTask — set it here so downstream code can read it.
        task.setBranchName(branchName);
        task.setAssignedModel(taskModel);

        // === LLM call budget check before executing worker ===
        if (runBudgetTracker.isOverBudget(runId)) {
            log.warn("[Budget] Run {} over budget ({}/{} calls) — skipping task {}",
                runId, runBudgetTracker.getCurrentCalls(runId),
                runBudgetTracker.getCeiling(runId), taskId);
            stateStore.failTask(taskId, "Run budget exceeded — task skipped");
            sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "FAILED"));
            failed.add(task);
            try { gitTool.removeWorktree(runId, taskId); } catch (Exception ex) { /* best effort */ }
            return;
        }

        // === Cost budget check (silent hard cap) before executing worker ===
        if (runCostBudgetService.isOverBudget(runId)) {
            String tier = runCostBudgetService.getRunTier(runId);
            Double budget = runCostBudgetService.getBudget(runId);
            log.warn("[CostBudget] Run {} over ${} budget — cancelling run", runId,
                    budget != null ? String.format("%.2f", budget) : "?");
            stateStore.failTask(taskId, "Run cost budget exceeded — upgrade for higher limits");
            sseBroadcaster.emit(runId, RunEvent.budgetExceeded(runId, tier, budget != null ? budget : 3.0));
            sseBroadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            failed.add(task);
            try { gitTool.removeWorktree(runId, taskId); } catch (Exception ex) { /* best effort */ }
            return;
        }

        // === Subtask decomposition: split multi-file tasks into per-file subtasks ===
        TaskDefinition taskDef = parseTaskDef(task.getTaskJson());
        if (subtaskDecomposer.shouldDecompose(taskDef, taskOwnedFilesMap)) {
            metricsTaskOutcome = executeDecomposedTask(task, taskDef, run, availableProviders,
                    allReports, completed, failed, previouslyMerged, mergedTaskIds, mergeLock,
                    branchName);
            taskObs.lowCardinalityKeyValue("task.outcome", metricsTaskOutcome);
            return;
        }

        // === Execute worker with automatic retry on failure ===
        int maxRetries = agentProperties.getConcurrency().getTaskMaxRetries();
        WorkerReport report = null;
        String normalizedStatus = "FAILED";

        for (int attempt = 1; attempt <= maxRetries + 1; attempt++) {
            try {
                report = executeTask(run, task, availableProviders);
            } catch (Exception e) {
                log.error("Task {} crashed on attempt {}/{}: {}", taskId, attempt, maxRetries + 1, e.getMessage(), e);
                if (attempt <= maxRetries) {
                    sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                            String.format("⚠️ Task failed (attempt %d/%d) — retrying...", attempt, maxRetries + 1)));
                    stateStore.lockTask(taskId, task.getAssignedModel(), branchName);
                    sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));
                    continue;
                }
                stateStore.failTask(taskId, "Unhandled exception after " + attempt + " attempt(s): " + e.getMessage());
                sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "FAILED"));
                failed.add(task);
                metricsTaskOutcome = "FAILED";
                taskObs.lowCardinalityKeyValue("task.outcome", "FAILED");
                try { gitTool.removeWorktree(runId, taskId); } catch (Exception ex) { /* best effort */ }
                return;
            }

            normalizedStatus = normalizeStatus(report.getStatus());
            if ("COMPLETE".equals(normalizedStatus) || "PARTIAL".equals(normalizedStatus)) {
                break; // success
            }

            // Non-complete status — retry if attempts remain
            if (attempt <= maxRetries) {
                log.warn("Task {} returned {} on attempt {}/{} — retrying", taskId, normalizedStatus, attempt, maxRetries + 1);
                sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                        String.format("⚠️ Task returned %s (attempt %d/%d) — retrying...", normalizedStatus, attempt, maxRetries + 1)));
                stateStore.lockTask(taskId, task.getAssignedModel(), branchName);
                sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));
            }
        }

        allReports.add(report);

        metricsTaskOutcome = normalizedStatus;
        taskObs.lowCardinalityKeyValue("task.outcome", normalizedStatus);
        sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, normalizedStatus));
        log.info("  Task [{}]: {}", taskId, normalizedStatus);

        if ("COMPLETE".equals(normalizedStatus) || "PARTIAL".equals(normalizedStatus)) {
            completed.add(task);

            // Commit any uncommitted files in the worktree before merge
            commitWorktreeIfDirty(runId, taskId);

            // Progressive merge: merge to main under lock (merges must be sequential)
            synchronized (mergeLock) {
                boolean merged = tryMergeToMain(runId, task, branchName, previouslyMerged);
                if (merged) {
                    // Quick compile check after merge — catches broken main before next task starts
                    Path workspacePath = gitTool.getWorkspacePath(runId);
                    ProgressiveMergeValidator.MergeCheckResult checkResult =
                            mergeValidator.quickCheck(runId, workspacePath);

                    if (checkResult.result() == ProgressiveMergeValidator.CheckResult.FAILED) {
                        // ── Supervisor: intelligent triage before deciding to revert ──────────
                        TaskCompileDecision decision = taskCompileSupervisor.supervise(
                                run, task, checkResult.errorOutput(), mergedTaskIds, availableProviders);
                        log.info("[Supervisor] task={} action={} fixability={} owned={} cascading={}",
                                taskId, decision.action(), decision.fixabilityScore(),
                                decision.ownedErrorCount(), decision.cascadingErrorCount());

                        switch (decision.action()) {
                            case PASS -> {
                                // All errors are cascading noise — keep merge as-is
                                mergedTaskIds.add(taskId);
                                previouslyMerged.add(task);
                                metricsTaskOutcome = "PASS_CASCADING";
                            }
                            case CONTINUE_WITH_WARNING -> {
                                // Errors in non-owned files — not this task's fault
                                mergedTaskIds.add(taskId);
                                previouslyMerged.add(task);
                                metricsTaskOutcome = "CONTINUE_WARNING";
                            }
                            case AUTO_FIX -> {
                                String buildTool = compilerAgent.detectBuildTool(runId);
                                boolean patched = buildTool != null && compilerAgent.applyTargetedFix(
                                        runId, extractOwnedFilesFromTask(task),
                                        decision.autoFixInstructions(), buildTool);
                                if (patched) {
                                    Path wp = gitTool.getWorkspacePath(runId);
                                    ProgressiveMergeValidator.MergeCheckResult recheck =
                                            mergeValidator.quickCheck(runId, wp);
                                    if (recheck.result() != ProgressiveMergeValidator.CheckResult.FAILED) {
                                        mergedTaskIds.add(taskId);
                                        previouslyMerged.add(task);
                                        metricsTaskOutcome = "AUTO_FIXED";
                                        break;
                                    }
                                    // Patch insufficient — escalate to retry
                                    decision = decision.escalateToRetry(recheck.errorOutput());
                                } else {
                                    decision = decision.escalateToRetry(checkResult.errorOutput());
                                }
                                metricsTaskOutcome = handleRetryTask(run, task, branchName, decision,
                                        availableProviders, completed, failed, previouslyMerged,
                                        mergedTaskIds, taskObs);
                            }
                            case RETRY_TASK -> {
                                metricsTaskOutcome = handleRetryTask(run, task, branchName, decision,
                                        availableProviders, completed, failed, previouslyMerged,
                                        mergedTaskIds, taskObs);
                            }
                        }
                    } else {
                        mergedTaskIds.add(taskId);
                        previouslyMerged.add(task);
                    }
                } else {
                    // Merge failed — downgrade to failed
                    completed.remove(task);
                    failed.add(task);
                    metricsTaskOutcome = "MERGE_FAILED";
                    taskObs.lowCardinalityKeyValue("task.outcome", "MERGE_FAILED");
                }
            }
        } else {
            failed.add(task);
            // Clean up worktree for non-complete task
            try {
                gitTool.removeWorktree(runId, taskId);
            } catch (Exception e) {
                log.debug("Worktree cleanup for failed task {}: {}", taskId, e.getMessage());
            }
        }

        } finally {
            // Record task metrics
            java.time.Duration taskDuration = java.time.Duration.between(taskMetricsStart, Instant.now());
            Counter.builder(TASK_TOTAL).tag("status", metricsTaskOutcome)
                    .register(meterRegistry).increment();
            Timer.builder(TASK_DURATION).tag("status", metricsTaskOutcome)
                    .register(meterRegistry).record(taskDuration);
            taskObs.stop();
        }
    }

    /**
     * Execute a single task: select model → run worker → complete.
     * Validation is deferred to the review phase (compiler agent + reviewer post-merge).
     */
    private WorkerReport executeTask(PipelineRun run, AgentTask task,
                                      Set<LlmProvider> availableProviders) {
        String runId = run.getRunId();
        String taskId = task.getTaskId();

        // === MODEL SELECTION via ModelResolver ===
        ResolvedModel model = modelResolver.resolve("WorkerAgent", run.getAiProviderEnum());

        sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                String.format("Using %s [%s]", model.getModel(), model.getAgentTier())));
        sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));

        task.setAssignedModel(model.getModel());

        // === EXECUTE WORKER (creates worktree, runs agentic loop, clears WorkspaceContext) ===
        Instant execStart = Instant.now();
        WorkerReport report = workerAgent.execute(task, runId);
        long durationMs = java.time.Duration.between(execStart, Instant.now()).toMillis();

        String workerStatus = normalizeStatus(report.getStatus());
        stateStore.completeTask(taskId, report);
        trackMetric(runId, task, model, 1, "COMPLETE".equals(workerStatus), durationMs, report);

        // Record actual iterations for adaptive cap tuning (only on genuine completions)
        if ("COMPLETE".equals(workerStatus) && report.getIterationCount() > 0) {
            adaptiveIterationService.recordTaskCompletion(
                    report.getProvider(),
                    task.getComplexity() != null ? task.getComplexity() : "MEDIUM",
                    report.getIterationCount());
        }

        return report;
    }

    /**
     * Supervisor RETRY_TASK handler: revert merge → re-run worker with targeted feedback → re-merge.
     * Extracted from the old inline retry block so it can be called from both RETRY_TASK
     * and AUTO_FIX-escalated paths.
     *
     * @return a metrics outcome string (e.g. "COMPLETE_AFTER_RETRY", "PARTIAL_COMPILE_FAILED")
     */
    private String handleRetryTask(PipelineRun run, AgentTask task, String branchName,
                                    TaskCompileDecision decision,
                                    Set<LlmProvider> availableProviders,
                                    List<AgentTask> completed, List<AgentTask> failed,
                                    List<AgentTask> previouslyMerged, Set<String> mergedTaskIds,
                                    Observation taskObs) {
        String runId = run.getRunId();
        String taskId = task.getTaskId();

        String feedback = (decision.retryInstructions() != null && !decision.retryInstructions().isBlank())
                ? decision.retryInstructions()
                : "Post-merge compile check failed. Fix the following errors:\n" + decision.assessment();

        log.warn("[Supervisor] RETRY_TASK: reverting merge for task {} — {}", taskId, decision.assessment());

        boolean reverted = mergeService.revertLastMerge(runId);
        if (reverted) {
            WorkerReport retryReport = retryTaskWithCompileError(run, task, branchName, feedback, availableProviders);
            String retryStatus = normalizeStatus(retryReport.getStatus());
            if ("COMPLETE".equals(retryStatus)) {
                boolean reMerged = tryMergeToMain(runId, task, branchName, previouslyMerged);
                if (reMerged) {
                    mergedTaskIds.add(taskId);
                    previouslyMerged.add(task);
                    return "COMPLETE_AFTER_RETRY";
                } else {
                    completed.remove(task);
                    failed.add(task);
                    taskObs.lowCardinalityKeyValue("task.outcome", "MERGE_FAILED_AFTER_RETRY");
                    return "MERGE_FAILED_AFTER_RETRY";
                }
            } else {
                completed.remove(task);
                failed.add(task);
                taskObs.lowCardinalityKeyValue("task.outcome", "PARTIAL_COMPILE_FAILED");
                log.warn("[Supervisor] Task {} retry also failed — marking PARTIAL, continuing", taskId);
                return "PARTIAL_COMPILE_FAILED";
            }
        } else {
            completed.remove(task);
            failed.add(task);
            taskObs.lowCardinalityKeyValue("task.outcome", "REVERT_FAILED");
            return "REVERT_FAILED";
        }
    }

    /** Extracts the owned file list from a task's JSON, returning empty list on failure. */
    private List<String> extractOwnedFilesFromTask(AgentTask task) {
        if (task.getTaskJson() == null) return List.of();
        try {
            com.navneet.executespec.agent.model.TaskDefinition def =
                    mapper.readValue(task.getTaskJson(), com.navneet.executespec.agent.model.TaskDefinition.class);
            return def.getOwnedFiles() != null ? def.getOwnedFiles() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Retries a task once with the compile error injected as additional context.
     * Called after a post-merge compile check fails and the merge has been reverted.
     * Uses the existing reworkFeedback mechanism in TaskDefinition so WorkerAgent
     * automatically surfaces the error in the initial task prompt.
     */
    private WorkerReport retryTaskWithCompileError(PipelineRun run, AgentTask task,
                                                    String branchName, String compileError,
                                                    Set<LlmProvider> availableProviders) {
        String runId = run.getRunId();
        String taskId = task.getTaskId();

        log.info("[ProgressiveMerge] Retrying task {} with compile error feedback for run {}", taskId, runId);
        sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                "Retrying task with compile error context: " +
                (compileError.length() > 300 ? compileError.substring(0, 300) + "…" : compileError)));

        // Inject compile error via reworkFeedback — WorkerAgent reads this and surfaces it in prompt
        String feedback = "Post-merge compile check failed. Fix the following errors:\n" + compileError;
        stateStore.markRetryingWithFeedback(runId, taskId, feedback);

        // Re-read the updated task (markRetryingWithFeedback modifies taskJson in DB)
        AgentTask updatedTask;
        try { updatedTask = stateStore.getTask(taskId); } catch (Exception ex) { updatedTask = task; }

        WorkerReport report = workerAgent.execute(updatedTask, runId);
        stateStore.completeTask(taskId, report);
        return report;
    }

    // ── Worktree commit + progressive merge ─────────────────────────────────

    /**
     * Commits any uncommitted files in the task's worktree using shell git.
     *
     * <p>Uses shell {@code git} directly in the worktree directory rather than
     * JGit's {@code commitIfDirty}, because JGit has reliability issues with
     * linked worktrees (which use a {@code .git} file pointing back to the main
     * repo, rather than a {@code .git} directory).
     *
     * <p>Workers may or may not call {@code git_commit} during their agentic
     * loop.  This safety net ensures all files are committed to the branch
     * before the progressive merge to main.
     */
    private void commitWorktreeIfDirty(String runId, String taskId) {
        Path worktreePath = gitTool.getWorkspacePath(runId)
                .resolve("worktrees").resolve(taskId);
        if (!Files.isDirectory(worktreePath)) {
            log.debug("Worktree directory missing for task {} — skip commit", taskId);
            return;
        }
        try {
            // Check for any uncommitted changes (tracked or untracked)
            ProcessBuilder statusPb = new ProcessBuilder("git", "status", "--porcelain")
                    .directory(worktreePath.toFile()).redirectErrorStream(true);
            Process statusProc = statusPb.start();
            String statusOutput = new String(statusProc.getInputStream().readAllBytes());
            statusProc.waitFor();

            if (statusOutput.isBlank()) {
                log.debug("Worktree for task {} is clean — nothing to commit", taskId);
                return;
            }

            log.info("Auto-committing {} dirty entries in worktree for task {}",
                    statusOutput.lines().count(), taskId);

            // Stage all files
            ProcessBuilder addPb = new ProcessBuilder("git", "add", "-A")
                    .directory(worktreePath.toFile()).redirectErrorStream(true);
            Process addProc = addPb.start();
            addProc.getInputStream().readAllBytes();
            addProc.waitFor();

            // Commit
            String message = "Task " + taskId + " — auto-commit before merge";
            ProcessBuilder commitPb = new ProcessBuilder("git", "commit", "-m", message)
                    .directory(worktreePath.toFile()).redirectErrorStream(true);
            Process commitProc = commitPb.start();
            String commitOutput = new String(commitProc.getInputStream().readAllBytes());
            int exitCode = commitProc.waitFor();

            if (exitCode == 0) {
                log.info("Auto-committed worktree for task {}: {}",
                        taskId, commitOutput.lines().findFirst().orElse(""));
            } else {
                log.error("Auto-commit FAILED for task {} (exit {}): {}",
                        taskId, exitCode, commitOutput);
            }
        } catch (Exception e) {
            log.error("Failed to auto-commit worktree for task {}: {}", taskId, e.getMessage(), e);
        }
    }

    /**
     * Merges the task's branch to main immediately after completion.
     * Uses GitMergeService's conflict resolution cascade.
     * Returns true on success, false on unresolvable conflict.
     */
    private boolean tryMergeToMain(String runId, AgentTask task, String branchName,
                                    List<AgentTask> previouslyMerged) {
        try {
            GitMergeService.TaskMergeResult result = mergeService.mergeTaskBranchWithResolution(
                    runId, task, previouslyMerged);

            if (result.outcome() == MergeOutcome.FAILED) {
                stateStore.failTask(task.getTaskId(),
                        "Merge conflict — auto-resolution failed for: "
                        + String.join(", ", result.failedFiles())
                        + ". Retry this task to regenerate the changes cleanly.");
                sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, task.getTaskId(), "FAILED"));
                sseBroadcaster.emit(runId, RunEvent.log(runId, task.getTaskId(),
                        "Merge conflict on branch " + branchName + " — task marked FAILED"));
                return false;
            }

            if (result.outcome() != MergeOutcome.CLEAN) {
                sseBroadcaster.emit(runId, RunEvent.log(runId, task.getTaskId(),
                        "Auto-resolved merge conflict in: " + String.join(", ", result.resolvedFiles())));
            }

            gitTool.removeWorktree(runId, task.getTaskId());
            log.info("Progressive merge: task {} branch {} merged to main (outcome={})",
                    task.getTaskId(), branchName, result.outcome());
            return true;
        } catch (Exception e) {
            log.error("Progressive merge failed for task {}: {}", task.getTaskId(), e.getMessage(), e);
            return false;
        }
    }

    // ── Tier computation ─────────────────────────────────────────────────────

    /**
     * Groups tasks into execution tiers based on the dependency graph.
     * Tier 0 contains tasks with no dependencies; tier N contains tasks whose
     * deepest dependency is in tier N-1. Tasks in the same tier are independent
     * and can run in parallel.
     */
    private List<List<AgentTask>> computeTiers(List<AgentTask> tasks) {
        Map<String, AgentTask> byId = new LinkedHashMap<>();
        Map<String, List<String>> deps = new HashMap<>();

        for (AgentTask t : tasks) {
            byId.put(t.getTaskId(), t);
            TaskDefinition def = parseTaskDef(t.getTaskJson());
            List<String> taskDeps = def.getDependsOn() != null ? def.getDependsOn() : List.of();
            deps.put(t.getTaskId(), taskDeps);
        }

        // Compute tier level for each task (memoized recursive)
        Map<String, Integer> tierLevel = new HashMap<>();
        for (String taskId : byId.keySet()) {
            computeTierLevel(taskId, deps, byId, tierLevel, new HashSet<>());
        }

        // Group tasks by tier
        int maxTier = tierLevel.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<List<AgentTask>> tiers = new ArrayList<>();
        for (int tier = 0; tier <= maxTier; tier++) {
            final int t = tier;
            List<AgentTask> tierTasks = byId.values().stream()
                    .filter(task -> tierLevel.getOrDefault(task.getTaskId(), 0) == t)
                    .toList();
            if (!tierTasks.isEmpty()) {
                tiers.add(tierTasks);
            }
        }

        return tiers;
    }

    /**
     * Recursively compute the tier level of a task.
     * A task's tier = max(dependency tiers) + 1, or 0 if no dependencies.
     */
    private int computeTierLevel(String taskId, Map<String, List<String>> deps,
                                  Map<String, AgentTask> byId, Map<String, Integer> computed,
                                  Set<String> visiting) {
        if (computed.containsKey(taskId)) return computed.get(taskId);
        if (visiting.contains(taskId)) return 0; // circular dependency guard
        visiting.add(taskId);

        int maxDepTier = -1;
        for (String dep : deps.getOrDefault(taskId, List.of())) {
            if (byId.containsKey(dep)) {
                maxDepTier = Math.max(maxDepTier,
                        computeTierLevel(dep, deps, byId, computed, visiting));
            }
        }

        int tier = maxDepTier + 1;
        computed.put(taskId, tier);
        visiting.remove(taskId);
        return tier;
    }

    // ── Topological sort (retained for reference) ────────────────────────────

    /**
     * Kahn's algorithm: returns tasks in dependency-first order.
     * Tasks with no dependencies come first; tasks whose deps are all
     * satisfied come next, etc.
     */
    private List<AgentTask> topologicalSort(List<AgentTask> tasks) {
        Map<String, AgentTask> byId = new LinkedHashMap<>();
        Map<String, List<String>> deps = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (AgentTask t : tasks) {
            byId.put(t.getTaskId(), t);
            TaskDefinition def = parseTaskDef(t.getTaskJson());
            List<String> taskDeps = def.getDependsOn() != null ? def.getDependsOn() : List.of();
            deps.put(t.getTaskId(), taskDeps);
            inDegree.put(t.getTaskId(), 0);
        }

        // Count in-degrees (only for deps that are in our task set)
        for (var entry : deps.entrySet()) {
            int count = 0;
            for (String dep : entry.getValue()) {
                if (byId.containsKey(dep)) count++;
            }
            inDegree.put(entry.getKey(), count);
        }

        // BFS: start with tasks that have zero in-degree
        Queue<String> queue = new LinkedList<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<AgentTask> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            sorted.add(byId.get(id));

            // Reduce in-degree for tasks that depend on this one
            for (var entry : deps.entrySet()) {
                if (entry.getValue().contains(id)) {
                    int newDeg = inDegree.get(entry.getKey()) - 1;
                    inDegree.put(entry.getKey(), newDeg);
                    if (newDeg == 0) queue.add(entry.getKey());
                }
            }
        }

        // If some tasks weren't added (circular deps), append them at the end
        if (sorted.size() < tasks.size()) {
            Set<String> added = new HashSet<>();
            sorted.forEach(t -> added.add(t.getTaskId()));
            for (AgentTask t : tasks) {
                if (!added.contains(t.getTaskId())) {
                    log.warn("Task {} has unresolved dependencies — appending at end", t.getTaskId());
                    sorted.add(t);
                }
            }
        }

        return sorted;
    }

    // ── Metrics ──────────────────────────────────────────────────────────────

    /**
     * Track model performance metrics for future data-driven routing.
     */
    private void trackMetric(String runId, AgentTask task, ResolvedModel model,
                              int attempt, boolean passed, long durationMs,
                              WorkerReport report) {
        try {
            metricRepository.save(ModelTaskMetric.builder()
                    .runId(runId)
                    .taskId(task.getTaskId())
                    .modelName(model.getModel())
                    .provider(model.getActualProvider().name())
                    .tier(model.getAgentTier().name())
                    .complexity(task.getComplexity() != null ? task.getComplexity() : "MEDIUM")
                    .attempt(attempt)
                    .validationPassed(passed)
                    .errorCount(0)
                    .durationMs(durationMs)
                    .wasTruncated(report != null && report.isWasTruncated())
                    .maxOutputTokens(report != null ? report.getMaxOutputTokens() : 0)
                    .actualOutputTokens(report != null ? report.getActualOutputTokens() : 0)
                    .iterationCount(report != null ? report.getIterationCount() : 0)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to track model task metric", e);
        }
    }

    // ── Dependency + parsing helpers ─────────────────────────────────────────

    /**
     * Check if any of this task's dependencies are in the failed list.
     * Parses dependsOn from the task's JSON definition.
     */
    private boolean hasDependencyFailure(AgentTask task, List<AgentTask> failed) {
        if (failed.isEmpty() || task.getTaskJson() == null) return false;
        try {
            TaskDefinition def = mapper.readValue(task.getTaskJson(), TaskDefinition.class);
            if (def.getDependsOn() == null || def.getDependsOn().isEmpty()) return false;
            Set<String> failedIds = failed.stream()
                    .map(AgentTask::getTaskId)
                    .collect(Collectors.toSet());
            return def.getDependsOn().stream().anyMatch(failedIds::contains);
        } catch (Exception e) {
            log.warn("Could not parse task JSON for dependency check: {}", task.getTaskId(), e);
            return false;
        }
    }

    private String normalizeStatus(String raw) {
        if (raw == null) return "BLOCKED";
        return switch (raw.toLowerCase()) {
            case "complete", "done" -> "COMPLETE";
            case "blocked" -> "BLOCKED";
            case "partial" -> "PARTIAL";
            case "failed"  -> "FAILED";
            default -> raw.toUpperCase();
        };
    }

    // ── Subtask decomposition ─────────────────────────────────────────────────

    /**
     * Executes a decomposed multi-file task: creates per-file subtasks in DB,
     * then executes each subtask sequentially in the same git worktree.
     * All subtasks share one worktree so subtask N+1 can read files from subtask N.
     * Single git commit + merge after all subtasks complete.
     *
     * @return the outcome string for metrics (COMPLETE, PARTIAL, FAILED, MERGE_FAILED)
     */
    private String executeDecomposedTask(AgentTask parentTask, TaskDefinition taskDef,
                                          PipelineRun run, Set<LlmProvider> availableProviders,
                                          List<WorkerReport> allReports, List<AgentTask> completed,
                                          List<AgentTask> failed, List<AgentTask> previouslyMerged,
                                          Set<String> mergedTaskIds, Object mergeLock,
                                          String branchName) {
        String runId = run.getRunId();
        String parentTaskId = parentTask.getTaskId();

        try {
            // 1. Decompose into per-file subtasks
            List<TaskDefinition> subtaskDefs = subtaskDecomposer.decompose(taskDef);
            log.info("[Decompose] Task {} → {} subtasks (files: {})",
                    parentTaskId, subtaskDefs.size(),
                    subtaskDefs.stream().map(d -> d.getOwnedFiles().get(0)).toList());

            // 2. Create subtask DB rows
            List<AgentTask> subtaskRows = stateStore.createSubtasks(
                    parentTaskId, runId, subtaskDefs, parentTask.getTenantId());

            // 3. Emit decomposition event
            sseBroadcaster.emit(runId, RunEvent.taskDecomposed(runId, parentTaskId, subtaskRows.size()));

            // 4. Create worktree ONCE for the parent task
            List<String> depBranches = new ArrayList<>();
            if (taskDef.getDependsOn() != null) {
                taskDef.getDependsOn().forEach(depId ->
                        depBranches.add("agent/" + runId.substring(0, 8) + "/" + depId));
            }
            String workDir = gitTool.createWorktree(runId, parentTaskId, branchName, depBranches);

            // 5. Execute each subtask SEQUENTIALLY in the same worktree
            ResolvedModel model = modelResolver.resolve("WorkerAgent", run.getAiProviderEnum());
            boolean anyFailed = false;

            for (AgentTask subtask : subtaskRows) {
                if (anyFailed) {
                    stateStore.failTask(subtask.getTaskId(), "Blocked — prior subtask failed");
                    sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, subtask.getTaskId(), "BLOCKED"));
                    continue;
                }

                // Check budget (silent hard cap) before each subtask
                if (runCostBudgetService.isOverBudget(runId)) {
                    String tier = runCostBudgetService.getRunTier(runId);
                    Double budget = runCostBudgetService.getBudget(runId);
                    log.warn("[CostBudget] Run {} over budget during subtask {} — stopping",
                            runId, subtask.getTaskId());
                    stateStore.failTask(subtask.getTaskId(), "Run cost budget exceeded");
                    sseBroadcaster.emit(runId, RunEvent.budgetExceeded(runId, tier, budget != null ? budget : 3.0));
                    anyFailed = true;
                    continue;
                }

                // Lock and execute subtask
                subtask.setAssignedModel(model.getModel());
                stateStore.lockTask(subtask.getTaskId(), model.getModel(), branchName);
                sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, subtask.getTaskId(), "WORKING"));
                sseBroadcaster.emit(runId, RunEvent.log(runId, subtask.getTaskId(),
                        String.format("Subtask %d/%d: %s [%s]",
                                subtask.getSubtaskIndex(), subtask.getSubtaskTotal(),
                                SubtaskDecomposer.extractFileName(subtaskDefs.get(subtask.getSubtaskIndex() - 1)
                                        .getOwnedFiles().get(0)),
                                model.getModel())));

                // ── Execute subtask with automatic retry on failure ──
                int subtaskMaxRetries = agentProperties.getConcurrency().getTaskMaxRetries();
                try {
                    WorkerReport report = null;
                    String status = "FAILED";
                    long durationMs = 0;
                    ResolvedModel usedModel = model;
                    boolean succeeded = false;

                    for (int attempt = 1; attempt <= subtaskMaxRetries + 1; attempt++) {
                        Instant subtaskStart = Instant.now();
                        report = workerAgent.executeInWorkDir(subtask, runId, workDir);
                        durationMs = java.time.Duration.between(subtaskStart, Instant.now()).toMillis();
                        status = normalizeStatus(report.getStatus());

                        if ("COMPLETE".equals(status) || "PARTIAL".equals(status)) {
                            succeeded = true;
                            break;
                        }

                        // ── Fabrication escalation on final attempt ──
                        if (attempt == subtaskMaxRetries + 1
                                && isFabricationFailure(report)
                                && !model.getModel().equals(getPlanningModel(run))) {
                            String escalatedModel = getPlanningModel(run);
                            log.warn("[Decompose] Subtask {} failed with fabrication on {} — escalating to {}",
                                    subtask.getTaskId(), model.getModel(), escalatedModel);
                            stateStore.appendTaskLog(subtask.getTaskId(),
                                    "FABRICATION_ESCALATION:" + model.getModel() + "→" + escalatedModel);
                            sseBroadcaster.emit(runId, RunEvent.log(runId, subtask.getTaskId(),
                                    String.format("Fabrication detected — retrying with %s", escalatedModel)));

                            subtask.setAssignedModel(escalatedModel);
                            stateStore.lockTask(subtask.getTaskId(), escalatedModel, branchName);

                            Instant retryStart = Instant.now();
                            report = workerAgent.executeInWorkDir(subtask, runId, workDir);
                            durationMs = java.time.Duration.between(retryStart, Instant.now()).toMillis();
                            status = normalizeStatus(report.getStatus());
                            usedModel = modelResolver.resolve(
                                    "WorkerAgent", run.getAiProviderEnum(), AgentTier.PLANNING);

                            if ("COMPLETE".equals(status) || "PARTIAL".equals(status)) {
                                succeeded = true;
                            }
                            break;
                        }

                        // Retry with same model
                        if (attempt <= subtaskMaxRetries) {
                            log.warn("[Decompose] Subtask {} returned {} on attempt {}/{} — retrying",
                                    subtask.getTaskId(), status, attempt, subtaskMaxRetries + 1);
                            sseBroadcaster.emit(runId, RunEvent.log(runId, subtask.getTaskId(),
                                    String.format("⚠️ Subtask failed (attempt %d/%d) — retrying...",
                                            attempt, subtaskMaxRetries + 1)));
                            stateStore.lockTask(subtask.getTaskId(), model.getModel(), branchName);
                            sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, subtask.getTaskId(), "WORKING"));
                        }
                    }

                    stateStore.completeTask(subtask.getTaskId(), report);
                    trackMetric(runId, subtask, usedModel, 1, succeeded, durationMs, report);
                    sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, subtask.getTaskId(), status));

                    if (!succeeded) {
                        anyFailed = true;
                        log.warn("[Decompose] Subtask {} failed after {} attempt(s) with status {}",
                                subtask.getTaskId(), subtaskMaxRetries + 1, status);
                    }

                    allReports.add(report);
                } catch (Exception e) {
                    log.error("[Decompose] Subtask {} crashed: {}", subtask.getTaskId(), e.getMessage(), e);
                    stateStore.failTask(subtask.getTaskId(), "Unhandled exception: " + e.getMessage());
                    sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, subtask.getTaskId(), "FAILED"));
                    anyFailed = true;
                }
            }

            // 6. Aggregate subtask results into parent
            stateStore.completeParentFromSubtasks(parentTaskId);
            sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, parentTaskId,
                    anyFailed ? "PARTIAL" : "COMPLETE"));

            // Commit all subtask files in one commit + progressive merge regardless of partial status
            commitWorktreeIfDirty(runId, parentTaskId);
            synchronized (mergeLock) {
                boolean merged = tryMergeToMain(runId, parentTask, branchName, previouslyMerged);
                if (merged) {
                    mergedTaskIds.add(parentTaskId);
                    previouslyMerged.add(parentTask);
                    // Treat PARTIAL decomposed tasks as completed so dependents can still run.
                    // Consistent with non-decomposed PARTIAL task behavior (also added to completed).
                    completed.add(parentTask);
                    return anyFailed ? "PARTIAL" : "COMPLETE";
                } else {
                    failed.add(parentTask);
                    return "MERGE_FAILED";
                }
            }

        } catch (Exception e) {
            log.error("[Decompose] Parent task {} crashed: {}", parentTaskId, e.getMessage(), e);
            stateStore.failTask(parentTaskId, "Decomposition failed: " + e.getMessage());
            sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, parentTaskId, "FAILED"));
            failed.add(parentTask);
            return "FAILED";
        } finally {
            try { gitTool.removeWorktree(runId, parentTaskId); } catch (Exception ex) { /* best effort */ }
        }
    }

    private TaskDefinition parseTaskDef(String json) {
        try {
            return mapper.readValue(json, TaskDefinition.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse task definition", e);
        }
    }

    /**
     * Pre-builds a taskId → owned files map from the full task list.
     * Used by SubtaskDecomposer to detect same-file sequential chains and skip
     * redundant phase decomposition (RC6).
     */
    private Map<String, Set<String>> buildTaskOwnedFilesMap(List<AgentTask> tasks) {
        Map<String, Set<String>> result = new HashMap<>();
        for (AgentTask task : tasks) {
            if (task.getTaskJson() == null) continue;
            try {
                TaskDefinition def = parseTaskDef(task.getTaskJson());
                if (def.getOwnedFiles() != null && !def.getOwnedFiles().isEmpty()) {
                    result.put(task.getTaskId(), new java.util.HashSet<>(def.getOwnedFiles()));
                }
            } catch (Exception e) {
                log.debug("Could not parse task {} for owned-files map: {}", task.getTaskId(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * Checks if a worker report indicates a fabrication or iteration-limit failure.
     * Fabrication: model output completion without calling writeFile.
     * Iteration limit: subtask hit the 10-iteration cap without producing output.
     * Both failure modes are recoverable by escalating to a smarter model.
     */
    private boolean isFabricationFailure(WorkerReport report) {
        if (report == null || report.getBlockers() == null) return false;
        String blockers = report.getBlockers().toLowerCase();
        return blockers.contains("fabricated completion")
                || blockers.contains("fabricated/partial completion")
                || blockers.contains("repeated_fabricated")
                || blockers.contains("iteration limit")
                || blockers.contains("tool-call iterations");
    }

    /**
     * Resolves the PLANNING tier model for the run's selected provider.
     * Used as the escalation target when a subtask fails with fabrication.
     */
    private String getPlanningModel(PipelineRun run) {
        try {
            return modelResolver.resolve("WorkerAgent", run.getAiProviderEnum(),
                    AgentTier.PLANNING).getModel();
        } catch (Exception e) {
            // Fallback — return empty so escalation is skipped
            return "";
        }
    }

}
