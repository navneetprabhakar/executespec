package com.navneet.executespec.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.WorkerAgent;
import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.router.*;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.validator.CodeValidator;
import com.navneet.executespec.validator.model.*;
import com.navneet.executespec.validator.prompt.RetryPromptBuilder;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static com.navneet.executespec.config.CustomMetricsConfig.*;
import com.navneet.executespec.config.CustomMetricsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
 *   <li>Model selection based on task complexity (via ModelRouter)</li>
 *   <li>Worker execution in isolated worktree (via WorkerAgent.execute())</li>
 *   <li>Post-execution code validation in worktree (via CodeValidator)</li>
 *   <li>Progressive merge to main (under lock, sequential within tier)</li>
 *   <li>Retry with error feedback + model escalation on validation failure</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequentialTaskExecutor {

    private final WorkerAgent workerAgent;
    private final CodeValidator codeValidator;
    private final ModelRouter modelRouter;
    private final ModelTaskMetricRepository metricRepository;
    private final AvailableProviderResolver providerResolver;
    private final StateStore stateStore;
    private final SseBroadcaster sseBroadcaster;
    private final GitTool gitTool;
    private final GitMergeService mergeService;
    private final ObjectMapper mapper;
    private final ObservationRegistry observationRegistry;
    private final MeterRegistry meterRegistry;

    @Value("${executespec.validator.max-retries:3}")
    private int maxRetries;

    @Value("${executespec.validator.per-task-level:TYPE}")
    private String validationLevelStr;

    /*
     * TODO: Per-run cost budget — check budget before each worker attempt:
     *   - FREE: $0.50 (fixed), PRO: $2.00 (max $10), TEAM: $5.00 (max $25), ENTERPRISE: $10.00 (max $100)
     *   - Check: RunCostBudgetService.canAfford(runId, estimatedCost) before each attempt
     *   - Before tier escalation: verify remaining budget covers higher-tier cost
     *   - On budget exceeded: mark task PARTIAL, emit BUDGET_EXCEEDED SSE event
     *   - RunCostBudgetService: initBudget(runId, tier), canAfford(runId, cost), getRemainingBudget(runId)
     *   - Store budget in Redis (ephemeral, per-run lifetime)
     *   - Uses CostEstimator.computeCostForRun() for running total
     */

    /**
     * Result of sequential execution across all tasks.
     */
    public record SequentialExecutionResult(
            List<WorkerReport> reports,
            List<AgentTask> completedTasks,
            List<AgentTask> failedTasks
    ) {
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
        List<WorkerReport> allReports = Collections.synchronizedList(new ArrayList<>());
        List<AgentTask> completed = Collections.synchronizedList(new ArrayList<>());
        List<AgentTask> failed = Collections.synchronizedList(new ArrayList<>());
        List<AgentTask> previouslyMerged = Collections.synchronizedList(new ArrayList<>());
        Set<String> mergedTaskIds = ConcurrentHashMap.newKeySet();
        String runId = run.getRunId();
        String defaultModel = run.getWorkerModel();
        ValidationLevel level = parseLevel(validationLevelStr);

        // Resolve available providers for this run's org (API key-aware routing)
        Set<LlmProvider> availableProviders = providerResolver.resolve(runId);

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
                executeSingleTask(executable.get(0), run, defaultModel, level,
                        availableProviders, allReports, completed, failed,
                        previouslyMerged, mergedTaskIds, mergeLock);
            } else {
                // Multiple independent tasks — run in parallel using virtual threads
                List<Thread> threads = new ArrayList<>();
                for (AgentTask task : executable) {
                    final int tier = tierIdx;
                    // Capture observation context so virtual threads propagate trace IDs
                    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
                    Thread vt = Thread.ofVirtual()
                            .name("worker-tier" + tier + "-" + task.getTaskId())
                            .start(snapshot.wrap(() -> executeSingleTask(task, run, defaultModel, level,
                                    availableProviders, allReports, completed, failed,
                                    previouslyMerged, mergedTaskIds, mergeLock)));
                    threads.add(vt);
                }

                // Wait for all tasks in this tier to complete before moving to next tier
                for (Thread vt : threads) {
                    try {
                        vt.join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Interrupted waiting for tier {} to complete", tierIdx);
                        break;
                    }
                }
            }

            log.info("Tier {} complete: {}/{} tasks succeeded overall, {} failed",
                    tierIdx, completed.size(), tasks.size(), failed.size());

            } finally {
                tierObs.stop();
            }
        }

        log.info("Tiered execution complete: {}/{} tasks succeeded (merged: {})",
                completed.size(), tasks.size(), mergedTaskIds);
        return new SequentialExecutionResult(allReports, completed, failed);
    }

    /**
     * Execute a single task: lock → run worker with validation → merge to main.
     * Thread-safe — can be called from multiple virtual threads concurrently.
     */
    private void executeSingleTask(AgentTask task, PipelineRun run, String defaultModel,
                                    ValidationLevel level, Set<LlmProvider> availableProviders,
                                    List<WorkerReport> allReports, List<AgentTask> completed,
                                    List<AgentTask> failed, List<AgentTask> previouslyMerged,
                                    Set<String> mergedTaskIds, Object mergeLock) {
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

        // === Execute with validation + model routing ===
        WorkerReport report;
        try {
            report = executeWithValidation(run, task, level, availableProviders);
        } catch (Exception e) {
            log.error("Task {} crashed with unhandled exception: {}", taskId, e.getMessage(), e);
            stateStore.failTask(taskId, "Unhandled exception: " + e.getMessage());
            sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "FAILED"));
            failed.add(task);
            metricsTaskOutcome = "FAILED";
            taskObs.lowCardinalityKeyValue("task.outcome", "FAILED");
            try { gitTool.removeWorktree(runId, taskId); } catch (Exception ex) { /* best effort */ }
            return;
        }
        allReports.add(report);

        String normalizedStatus = normalizeStatus(report.getStatus());
        metricsTaskOutcome = normalizedStatus;
        taskObs.lowCardinalityKeyValue("task.outcome", normalizedStatus);
        sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, normalizedStatus));
        log.info("  Task [{}]: {}", taskId, normalizedStatus);

        if ("COMPLETE".equals(normalizedStatus)) {
            completed.add(task);

            // Commit any uncommitted files in the worktree before merge
            commitWorktreeIfDirty(runId, taskId);

            // Progressive merge: merge to main under lock (merges must be sequential)
            synchronized (mergeLock) {
                boolean merged = tryMergeToMain(runId, task, branchName, previouslyMerged);
                if (merged) {
                    mergedTaskIds.add(taskId);
                    previouslyMerged.add(task);
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
     * Execute a single task with validation loop:
     * 1. Select model → 2. Run worker → 3. Validate in worktree → 4. Retry if failed
     *
     * <p>WorkerAgent.execute() creates an isolated git worktree, runs the agentic loop,
     * and clears WorkspaceContext when done. Validation must therefore use the worktree
     * path directly (not gitTool.getWorkspacePath which reads WorkspaceContext).
     */
    private WorkerReport executeWithValidation(PipelineRun run, AgentTask task,
                                                ValidationLevel level,
                                                Set<LlmProvider> availableProviders) {
        String runId = run.getRunId();
        String taskId = task.getTaskId();
        ValidationResult lastValidation = null;
        TaskComplexity complexity = parseComplexity(task.getComplexity());
        WorkerReport lastReport = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {

            // === MODEL SELECTION (tier escalation on retry, filtered by available providers) ===
            ModelSelection model;
            if (attempt == 1) {
                model = modelRouter.selectForTask(complexity, availableProviders);
            } else {
                model = modelRouter.selectForRetry(complexity, attempt, availableProviders);
            }

            sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                    String.format("Attempt %d/%d using %s [%s]",
                            attempt, maxRetries, model.getModelName(), model.getTier())));
            sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));

            // === PREPARE TASK FOR EXECUTION ===
            task.setAssignedModel(model.getModelName());

            if (attempt > 1 && lastValidation != null) {
                // Inject validation errors as rework feedback so the worker sees them
                String retryPrompt = RetryPromptBuilder.build(lastValidation);
                stateStore.markRetryingWithFeedback(runId, taskId, retryPrompt);
                // Re-fetch task after markRetryingWithFeedback (it resets status + injects feedback)
                task = stateStore.getTask(taskId);
                task.setAssignedModel(model.getModelName());

                sseBroadcaster.emit(runId, RunEvent.modelEscalated(runId, taskId,
                        "previous", model.getModelName()));
            }

            // === EXECUTE WORKER (creates worktree, runs agentic loop, clears WorkspaceContext) ===
            Instant execStart = Instant.now();
            lastReport = workerAgent.execute(task, runId);
            long durationMs = java.time.Duration.between(execStart, Instant.now()).toMillis();

            // Check if worker itself reported non-complete status (BLOCKED, etc.)
            String workerStatus = normalizeStatus(lastReport.getStatus());
            if (!"COMPLETE".equals(workerStatus)) {
                stateStore.completeTask(taskId, lastReport);
                trackMetric(runId, task, model, attempt, false, null, durationMs);
                log.warn("Task {} worker returned {}, skipping validation", taskId, workerStatus);
                return lastReport;
            }

            // === VALIDATE OUTPUT IN WORKTREE ===
            // WorkspaceContext is cleared after workerAgent.execute() returns, so
            // gitTool.getWorkspacePath(runId) returns the shared workspace root.
            // The worktree is at workspace/{runId}/worktrees/{taskId}/.
            Path mainWorkspace = gitTool.getWorkspacePath(runId);
            Path worktreePath = mainWorkspace.resolve("worktrees").resolve(taskId);
            if (!Files.isDirectory(worktreePath)) {
                // Worktree might not exist (fallback to main workspace)
                worktreePath = mainWorkspace;
            }

            List<Path> modifiedFiles = getModifiedFiles(worktreePath);

            if (modifiedFiles.isEmpty()) {
                // No code files modified — skip validation (config-only tasks, etc.)
                stateStore.completeTask(taskId, lastReport);
                trackMetric(runId, task, model, attempt, true, null, durationMs);
                log.info("Task {} — no modified code files, skipping validation", taskId);
                return lastReport;
            }

            lastValidation = validateModifiedFiles(runId, taskId, worktreePath, modifiedFiles, level);

            sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                    String.format("Validation [attempt %d]: %s — %s",
                            attempt, lastValidation.getVerdict(),
                            RetryPromptBuilder.buildSummary(lastValidation))));

            // === CHECK RESULT ===
            if (lastValidation.getVerdict() != ValidationVerdict.FAIL) {
                stateStore.completeTask(taskId, lastReport);
                sseBroadcaster.emit(runId, RunEvent.validationPassed(runId, taskId));
                trackMetric(runId, task, model, attempt, true, lastValidation, durationMs);
                return lastReport;
            }

            // Validation failed
            sseBroadcaster.emit(runId, RunEvent.validationFailed(runId, taskId,
                    RetryPromptBuilder.buildSummary(lastValidation)));
            trackMetric(runId, task, model, attempt, false, lastValidation, durationMs);
            log.warn("Task {} attempt {}/{} failed validation: {} errors",
                    taskId, attempt, maxRetries, lastValidation.getErrors().size());
        }

        // All retries exhausted — mark as failed
        stateStore.failTask(taskId, String.format("Validation failed after %d attempts", maxRetries));
        sseBroadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "FAILED"));
        sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                String.format("FAILED after %d attempts", maxRetries)));

        if (lastReport != null) return lastReport;
        return WorkerReport.partial(taskId, "Validation failed after " + maxRetries + " attempts", 0, 0);
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

    // ── Validation helpers ───────────────────────────────────────────────────

    /**
     * Validate all modified code files against the appropriate language toolchain.
     * Returns FAIL on the first file that fails validation.
     */
    private ValidationResult validateModifiedFiles(String runId, String taskId,
                                                    Path workspacePath, List<Path> modifiedFiles,
                                                    ValidationLevel level) {
        for (Path file : modifiedFiles) {
            Language lang = Language.fromPath(file);
            if (lang == null) continue;

            sseBroadcaster.emit(runId, RunEvent.log(runId, taskId,
                    "Validating: " + file.getFileName()));

            ValidationResult result = codeValidator.validateFile(
                    ValidateFileRequest.builder()
                            .projectRoot(workspacePath)
                            .filePath(workspacePath.relativize(file))
                            .language(lang)
                            .level(level)
                            .build());

            String validationResult = result.getVerdict() == ValidationVerdict.FAIL ? "fail" : "pass";
            Counter.builder(VALIDATION_TOTAL).tag("result", validationResult)
                    .tag("gate", lang.name())
                    .register(meterRegistry).increment();

            if (result.getVerdict() == ValidationVerdict.FAIL) {
                return result;
            }
        }
        return ValidationResult.pass();
    }

    /**
     * Get list of modified/new files from git diff in the specified directory.
     */
    private List<Path> getModifiedFiles(Path workspacePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "diff", "--name-only", "HEAD", "--diff-filter=ACMR")
                    .directory(workspacePath.toFile()).redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            ProcessBuilder pb2 = new ProcessBuilder(
                    "git", "ls-files", "--others", "--exclude-standard")
                    .directory(workspacePath.toFile()).redirectErrorStream(true);
            Process process2 = pb2.start();
            String untrackedOutput = new String(process2.getInputStream().readAllBytes());
            process2.waitFor();

            String combined = output + "\n" + untrackedOutput;
            return combined.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> workspacePath.resolve(line.trim()))
                    .filter(Files::exists)
                    .toList();
        } catch (Exception e) {
            log.error("Failed to get modified files from git in {}", workspacePath, e);
            return List.of();
        }
    }

    // ── Metrics ──────────────────────────────────────────────────────────────

    /**
     * Track model performance metrics for future data-driven routing.
     */
    private void trackMetric(String runId, AgentTask task, ModelSelection model,
                              int attempt, boolean passed, ValidationResult validation,
                              long durationMs) {
        try {
            metricRepository.save(ModelTaskMetric.builder()
                    .runId(runId)
                    .taskId(task.getTaskId())
                    .modelName(model.getModelName())
                    .provider(model.getProvider().name())
                    .tier(model.getTier().name())
                    .complexity(task.getComplexity() != null ? task.getComplexity() : "MEDIUM")
                    .attempt(attempt)
                    .validationPassed(passed)
                    .errorCount(validation != null ? validation.getErrors().size() : 0)
                    .firstErrorGate(validation != null && !validation.getErrors().isEmpty()
                            ? validation.getErrors().get(0).getGate() : null)
                    .durationMs(durationMs)
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

    private TaskDefinition parseTaskDef(String json) {
        try {
            return mapper.readValue(json, TaskDefinition.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot parse task definition", e);
        }
    }

    private ValidationLevel parseLevel(String level) {
        try { return ValidationLevel.valueOf(level.toUpperCase()); }
        catch (Exception e) { return ValidationLevel.TYPE; }
    }

    private TaskComplexity parseComplexity(String complexity) {
        try { return TaskComplexity.valueOf(complexity != null ? complexity.toUpperCase() : "MEDIUM"); }
        catch (Exception e) { return TaskComplexity.MEDIUM; }
    }
}
