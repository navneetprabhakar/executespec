package com.navneet.executespec.v2.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.PlannedTaskV2;
import com.navneet.executespec.v2.agent.WorkerAgentV2;
import com.navneet.executespec.v2.agent.WorkerResult;
import com.navneet.executespec.v2.agent.WorkerTaskRequest;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.tool.BuildProjectTool;
import com.navneet.executespec.v2.tool.ExecutionContext;
import com.navneet.executespec.v2.tool.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * V2 sequential executor. Runs a {@link PlanV2}'s tasks in order, one worker per task.
 *
 * <p>Per-task flow:
 * <ol>
 *   <li>Compose system + user prompts from the task's description + hardConstraints +
 *       touchesFiles hint</li>
 *   <li>Invoke {@link WorkerAgentV2} with the composed prompts and the run's ExecutionContext
 *       (substituting the task ID)</li>
 *   <li>If worker didn't reach {@code COMPLETED}: record {@code WORKER_FAILED}, continue to
 *       next task (current policy — Phase 5 baseline. A future supervisor could upgrade to
 *       ABORT on catastrophic failures.)</li>
 *   <li>Worker completed → run smoke test via {@link BuildProjectTool} using the plan's
 *       {@code buildCommand}</li>
 *   <li>Smoke test passed → {@code SUCCESS}, next task</li>
 *   <li>Smoke test failed → ONE retry: re-invoke worker with the build error injected into the
 *       user message</li>
 *   <li>Retry passed → {@code SUCCESS_AFTER_RETRY}; retry failed → {@code COMPLETED_WITH_WARNING},
 *       continue anyway (reviewer phase — Phase 7 — will catch residual issues)</li>
 * </ol>
 *
 * <p>Phase 5 baseline scope:
 * <ul>
 *   <li>Rule-based failure handling (retry once, then warn). A future TaskSupervisorV2
 *       can add LLM-tier triage (CASCADING / FIXABLE / STRUCTURAL) for smarter decisions.</li>
 *   <li>No SSE events, no Redis context persistence, no cross-task context sharing.</li>
 *   <li>Smoke tests default to the plan's {@code smokeTestStrategy} (currently only BUILD
 *       is fully wired — PREVIEW_HEALTH / TEST_SUITE planned for later phases).</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SequentialExecutorV2 {

    /** System prompt template for worker invocations. */
    private static final String SYSTEM_PROMPT_BASE = """
            You are a code-execution agent operating in a sandboxed workspace. You have
            access to filesystem (readFile, writeFile, editFile, listFiles, searchFiles),
            shell, git (status, diff, commit), and buildProject tools. Use the tools to
            complete the task — do not explain or hedge.

            Important rules:
            - The shell runs in the workspace root already. Do NOT `cd /workspace` or use
              absolute paths — use relative paths (e.g. `python3 fib.py`, not `cd /workspace && ...`).
            - Use `python3` (never bare `python`); many systems have no `python` on PATH.
            - writeFile creates NEW files only. To modify an existing file, use editFile.
            - editFile takes an array of {oldStr, newStr} pairs; every oldStr must occur
              exactly once in the current file.
            - After making changes, you may call buildProject to verify the project still
              compiles.
            - When the task is fully complete, call completeTask with a brief summary.
              Do not output any text that is not needed to complete the task.

            Efficiency — BATCH AGGRESSIVELY. Every extra turn costs tokens and latency:
            - Emit ALL independent tool calls in ONE turn (reads, writes, edits, builds).
            - If you just wrote a file, DO NOT re-read it to verify — trust writeFile's
              success envelope and move on.
            - Do NOT call gitStatus/gitDiff unless you specifically need commit staging info.
            - Do NOT re-read the same file twice in a task; content does not change unless
              YOU wrote to it.
            - Prefer large editFile batches (many {oldStr,newStr} pairs in one call) over
              many small editFile calls.
            - When the work is done, call completeTask IMMEDIATELY; do not add
              "final verification" round-trips.
            """;

    private final WorkerAgentV2 worker;
    private final BuildProjectTool buildProjectTool;
    private final ObjectMapper objectMapper;
    private final V2Metrics metrics;
    private final SseBroadcaster broadcaster;
    private final AgentTaskRepository taskRepo;
    private final ModelResolver modelResolver;

    public ExecutionResult execute(ExecutionRequest req) {
        long runStart = System.currentTimeMillis();
        List<TaskOutcome> outcomes = new ArrayList<>();
        PlanV2 plan = req.plan();

        log.info("SequentialExecutorV2 START run={} tasks={} provider={} model={} smokeTests={}",
                req.runContext().runId(), plan.tasks().size(), req.provider(), req.model(),
                req.enableSmokeTests());

        for (PlannedTaskV2 task : plan.tasks()) {
            TaskOutcome outcome = executeOne(task, plan, req);
            outcomes.add(outcome);
            metrics.recordTaskStatus(outcome.status().name());
            metrics.recordTaskLatency(outcome.status().name(),
                    java.time.Duration.ofMillis(outcome.totalLatencyMs()));
        }

        ExecutionResult result = summarize(outcomes, System.currentTimeMillis() - runStart);
        log.info("SequentialExecutorV2 DONE run={} overall={} success={}/{} warnings={} failed={} latency={}ms",
                req.runContext().runId(), result.overall(),
                result.tasksSuccessful(), result.tasksExecuted(),
                result.tasksWithWarning(), result.tasksFailed(), result.totalLatencyMs());
        return result;
    }

    private TaskOutcome executeOne(PlannedTaskV2 task, PlanV2 plan, ExecutionRequest req) {
        long taskStart = System.currentTimeMillis();
        ExecutionContext taskCtx = new ExecutionContext(
                req.runContext().runId(), task.id(), req.runContext().workspaceRoot());

        String systemPrompt = composeSystemPrompt(task, req.designBlock());
        String userPrompt = composeUserPrompt(task, null);

        log.info("Executor task={} ('{}') START", task.id(), task.title());
        String runId = req.runContext().runId();
        persistTaskStatus(runId, task.id(), "WORKING");
        broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, task.id(),
                "Starting task: " + task.title()));
        TierConfig cfg = tierConfigFor(task.complexity(), req.provider(), req.model());
        WorkerResult primary = worker.run(WorkerTaskRequest.builder()
                .provider(cfg.provider)
                .model(cfg.model)
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .context(taskCtx)
                .maxIterations(req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
                .temperature(0.0)
                .maxTokensPerCall(cfg.maxTokens)
                .build());

        log.info("Executor task={} toolCallStats provider={} model={} complexity={} outcome={} iters={} toolCalls={} fileWrites={}",
                task.id(), cfg.provider, cfg.model,
                task.complexity() == null ? "MEDIUM" : task.complexity().name(),
                primary.outcome(), primary.iterations(),
                primary.toolCallsDispatched(),
                countFileWrites(primary.toolCallLog()));

        // Worker didn't reach COMPLETED. Check whether it still wrote files — if so
        // the task produced real output and shouldn't be marked FAILED outright.
        // Common case: Gemini hits its iteration cap on HIGH tasks but has already
        // written the schema / entities / controller and just failed to call
        // `completeTask`. Marking these FAILED made the run look broken even though
        // the code was fine, and tanked the accuracy score.
        if (primary.outcome() != WorkerResult.Outcome.COMPLETED) {
            int fileWrites = countFileWrites(primary.toolCallLog());
            if (fileWrites > 0) {
                log.warn("Executor task={} worker outcome={} but wrote {} files — promoting to PARTIAL",
                        task.id(), primary.outcome(), fileWrites);
                persistTaskStatus(runId, task.id(), "PARTIAL");
                broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
                return TaskOutcome.builder()
                        .taskId(task.id()).taskTitle(task.title())
                        .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
                        .workerOutcome(primary)
                        .totalLatencyMs(System.currentTimeMillis() - taskStart)
                        .toolCallsDispatched(primary.toolCallsDispatched())
                        .build();
            }
            log.warn("Executor task={} WORKER_FAILED outcome={} iters={} (no file writes)",
                    task.id(), primary.outcome(), primary.iterations());
            persistTaskStatus(runId, task.id(), "FAILED");
            broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "FAILED"));
            return TaskOutcome.builder()
                    .taskId(task.id()).taskTitle(task.title())
                    .status(TaskOutcome.Status.WORKER_FAILED)
                    .workerOutcome(primary)
                    .totalLatencyMs(System.currentTimeMillis() - taskStart)
                    .toolCallsDispatched(primary.toolCallsDispatched())
                    .build();
        }

        if (!req.enableSmokeTests()) {
            log.info("Executor task={} SUCCESS (smoke tests disabled)", task.id());
            persistTaskStatus(runId, task.id(), "COMPLETE");
            broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
            return TaskOutcome.builder()
                    .taskId(task.id()).taskTitle(task.title())
                    .status(TaskOutcome.Status.SUCCESS)
                    .workerOutcome(primary)
                    .totalLatencyMs(System.currentTimeMillis() - taskStart)
                    .toolCallsDispatched(primary.toolCallsDispatched())
                    .build();
        }

        SmokeTestResult smoke = runSmokeTest(taskCtx, plan.buildCommand());
        if (smoke.passed()) {
            log.info("Executor task={} SUCCESS (smoke exit={}, took {}ms)",
                    task.id(), smoke.exitCode(), smoke.latencyMs());
            persistTaskStatus(runId, task.id(), "COMPLETE");
            broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
            return TaskOutcome.builder()
                    .taskId(task.id()).taskTitle(task.title())
                    .status(TaskOutcome.Status.SUCCESS)
                    .workerOutcome(primary)
                    .smokeTestPassed(true)
                    .smokeTestExitCode(smoke.exitCode())
                    .smokeTestOutput(smoke.output())
                    .totalLatencyMs(System.currentTimeMillis() - taskStart)
                    .toolCallsDispatched(primary.toolCallsDispatched())
                    .build();
        }

        log.warn("Executor task={} smoke test FAILED (exit={}), retrying once with error context",
                task.id(), smoke.exitCode());

        // ONE retry with the build error injected.
        String retryPrompt = composeUserPrompt(task, smoke.output());
        WorkerResult retry = worker.run(WorkerTaskRequest.builder()
                .provider(cfg.provider)
                .model(cfg.model)
                .systemPrompt(systemPrompt)
                .userPrompt(retryPrompt)
                .context(taskCtx)
                .maxIterations(req.maxIterationsPerTask() != null ? req.maxIterationsPerTask() : cfg.maxIter)
                .temperature(0.0)
                .maxTokensPerCall(cfg.maxTokens)
                .build());

        int totalToolCalls = primary.toolCallsDispatched() + retry.toolCallsDispatched();
        if (retry.outcome() != WorkerResult.Outcome.COMPLETED) {
            log.warn("Executor task={} retry WORKER_FAILED outcome={}; marking COMPLETED_WITH_WARNING",
                    task.id(), retry.outcome());
            return TaskOutcome.builder()
                    .taskId(task.id()).taskTitle(task.title())
                    .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
                    .workerOutcome(primary)
                    .retryAttempted(true).retryWorkerOutcome(retry)
                    .smokeTestPassed(false)
                    .smokeTestExitCode(smoke.exitCode())
                    .smokeTestOutput(smoke.output())
                    .totalLatencyMs(System.currentTimeMillis() - taskStart)
                    .toolCallsDispatched(totalToolCalls)
                    .build();
        }

        // Retry completed — re-smoke.
        SmokeTestResult retrySmoke = runSmokeTest(taskCtx, plan.buildCommand());
        if (retrySmoke.passed()) {
            log.info("Executor task={} SUCCESS_AFTER_RETRY", task.id());
            persistTaskStatus(runId, task.id(), "COMPLETE");
            broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
            return TaskOutcome.builder()
                    .taskId(task.id()).taskTitle(task.title())
                    .status(TaskOutcome.Status.SUCCESS_AFTER_RETRY)
                    .workerOutcome(primary)
                    .retryAttempted(true).retryWorkerOutcome(retry)
                    .smokeTestPassed(true)
                    .smokeTestExitCode(retrySmoke.exitCode())
                    .smokeTestOutput(retrySmoke.output())
                    .totalLatencyMs(System.currentTimeMillis() - taskStart)
                    .toolCallsDispatched(totalToolCalls)
                    .build();
        }

        log.warn("Executor task={} smoke still fails after retry; COMPLETED_WITH_WARNING", task.id());
        persistTaskStatus(runId, task.id(), "PARTIAL");
        broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
        return TaskOutcome.builder()
                .taskId(task.id()).taskTitle(task.title())
                .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
                .workerOutcome(primary)
                .retryAttempted(true).retryWorkerOutcome(retry)
                .smokeTestPassed(false)
                .smokeTestExitCode(retrySmoke.exitCode())
                .smokeTestOutput(retrySmoke.output())
                .totalLatencyMs(System.currentTimeMillis() - taskStart)
                .toolCallsDispatched(totalToolCalls)
                .build();
    }

    /**
     * Updates {@code AgentTask.status} in the DB mid-run so the UI's polled
     * task list reflects live progress (not just the terminal snapshot written
     * by {@code V2RunOrchestrator.applyExecutionResultToTasks}). Uses the same
     * prefixed-key convention as the orchestrator — first 8 chars of runId
     * plus the planner-assigned task id.
     */
    /**
     * Per-task tuning: tier-resolved model + iteration / max-token caps driven
     * by the planner's complexity tag.
     *
     * <ul>
     *   <li>LOW    → LIGHTWEIGHT tier, 12 iters, 4096 max tokens</li>
     *   <li>MEDIUM → WORKHORSE tier, 22 iters, 8192 max tokens</li>
     *   <li>HIGH   → PLANNING tier, 35 iters, 16384 max tokens</li>
     * </ul>
     *
     * <p>When {@link ModelResolver} / provider conversion fails (e.g. user-supplied
     * provider doesn't match any enum value), we fall back to the run-level
     * provider+model so the executor never crashes on a planner edge case.
     */
    private record TierConfig(String provider, String model, int maxIter, int maxTokens) {}

    private TierConfig tierConfigFor(PlannedTaskV2.Complexity complexity, String runProvider, String runModel) {
        AgentTier tier;
        int maxIter;
        int maxTokens;
        switch (complexity == null ? PlannedTaskV2.Complexity.MEDIUM : complexity) {
            case LOW  -> { tier = AgentTier.LIGHTWEIGHT; maxIter = 12; maxTokens = 4096; }
            case HIGH -> { tier = AgentTier.PLANNING;    maxIter = 35; maxTokens = 16384; }
            default   -> { tier = AgentTier.WORKHORSE;   maxIter = 22; maxTokens = 8192; }
        }
        try {
            LlmProvider p = LlmProvider.valueOf(mapToV1Provider(runProvider));
            ResolvedModel resolved = modelResolver.resolve("WorkerAgent", p, tier);
            String resolvedProvider = resolved.getActualProvider().name().toLowerCase();
            if ("google".equals(resolvedProvider)) resolvedProvider = "gemini";
            return new TierConfig(resolvedProvider, resolved.getModel(), maxIter, maxTokens);
        } catch (Exception e) {
            log.warn("tierConfigFor: resolve failed for provider={} tier={} — using run-level {} / {}",
                    runProvider, tier, runProvider, runModel);
            return new TierConfig(runProvider, runModel, maxIter, maxTokens);
        }
    }

    /** V2 uses "gemini"; V1 enum uses "GOOGLE". Normalize for the matrix lookup. */
    private static String mapToV1Provider(String v2Provider) {
        if (v2Provider == null) return "OPENAI";
        String upper = v2Provider.toUpperCase();
        return "GEMINI".equals(upper) ? "GOOGLE" : upper;
    }

    @org.springframework.transaction.annotation.Transactional
    public void persistTaskStatus(String runId, String taskIdShort, String status) {
        String key = runId.substring(0, 8) + "_" + taskIdShort;
        try {
            var existing = taskRepo.findById(key);
            if (existing.isEmpty()) {
                log.warn("persistTaskStatus: row not found id={} (status={} skipped)", key, status);
                return;
            }
            var t = existing.get();
            t.setStatus(status);
            taskRepo.saveAndFlush(t);
            log.info("persistTaskStatus: {} -> {}", key, status);
        } catch (Exception e) {
            log.warn("persistTaskStatus: {} -> {} failed: {}", key, status, e.getMessage(), e);
        }
    }

    // ─── prompt composition ─────────────────────────────────────────────────

    private String composeSystemPrompt(PlannedTaskV2 task, String designBlock) {
        StringBuilder sb = new StringBuilder(SYSTEM_PROMPT_BASE);
        if (!task.hardConstraints().isEmpty()) {
            sb.append("\n\nHARD CONSTRAINTS for this task (MUST follow):\n");
            for (String c : task.hardConstraints()) {
                sb.append("- ").append(c).append('\n');
            }
        }
        if (!task.touchesFiles().isEmpty()) {
            sb.append("\nExpected files to touch (hint, not a hard wall): ")
                    .append(String.join(", ", task.touchesFiles())).append('\n');
        }
        if (designBlock != null && !designBlock.isBlank()) {
            sb.append("\n\n").append(designBlock);
        }
        return sb.toString();
    }

    private String composeUserPrompt(PlannedTaskV2 task, String previousBuildError) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task ").append(task.id()).append(": ").append(task.title()).append("\n\n");
        sb.append(task.description());
        if (previousBuildError != null && !previousBuildError.isBlank()) {
            sb.append("\n\n---\n")
              .append("NOTE: A previous attempt completed but the build failed afterward. ")
              .append("Fix the issue so the build passes. Build output:\n")
              .append(previousBuildError);
        }
        return sb.toString();
    }

    // ─── smoke test helpers ─────────────────────────────────────────────────

    private SmokeTestResult runSmokeTest(ExecutionContext ctx, String plannerCommand) {
        long start = System.currentTimeMillis();
        ObjectNode args = objectMapper.createObjectNode();
        if (plannerCommand != null && !plannerCommand.isBlank()) {
            args.put("plannerCommand", plannerCommand);
        }
        ToolResult result = buildProjectTool.execute(args, ctx);
        long elapsed = System.currentTimeMillis() - start;
        if (result instanceof ToolResult.Ok ok) {
            String content = ok.content();
            int exit = extractExitCode(content);
            boolean passed = exit == 0;
            metrics.recordSmokeTest(passed ? "pass" : "fail");
            return new SmokeTestResult(passed, exit, content, elapsed);
        }
        // Tool Err — treat as smoke failure with a synthetic exit code
        metrics.recordSmokeTest("fail");
        String msg = result instanceof ToolResult.Err err ? err.message() : "(unknown tool err)";
        return new SmokeTestResult(false, -1, "tool error: " + msg, elapsed);
    }

    /** Parses "exit=N" out of BuildProjectTool's output envelope. */
    private static int extractExitCode(String output) {
        if (output == null) return -1;
        int i = output.indexOf("exit=");
        if (i < 0) return -1;
        int j = i + "exit=".length();
        int k = j;
        while (k < output.length() && Character.isDigit(output.charAt(k))) k++;
        try {
            return Integer.parseInt(output.substring(j, k));
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    private record SmokeTestResult(boolean passed, int exitCode, String output, long latencyMs) {}

    // ─── summarization ──────────────────────────────────────────────────────

    private ExecutionResult summarize(List<TaskOutcome> outcomes, long totalLatency) {
        int succ = 0, warn = 0, fail = 0, skip = 0;
        for (var o : outcomes) {
            switch (o.status()) {
                case SUCCESS, SUCCESS_AFTER_RETRY -> succ++;
                case COMPLETED_WITH_WARNING -> warn++;
                case WORKER_FAILED -> fail++;
                case SKIPPED -> skip++;
            }
        }
        ExecutionResult.Overall overall;
        if (fail > 0) overall = ExecutionResult.Overall.FAILED;
        else if (warn > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
        else overall = ExecutionResult.Overall.ALL_SUCCESS;
        return ExecutionResult.builder()
                .overall(overall)
                .tasksExecuted(outcomes.size() - skip)
                .tasksSuccessful(succ)
                .tasksWithWarning(warn)
                .tasksFailed(fail)
                .tasksSkipped(skip)
                .perTask(outcomes)
                .totalLatencyMs(totalLatency)
                .build();
    }

    /**
     * Counts successful file-writing tool calls in a worker's tool log. The log entries
     * look like {@code "writeFile({...})"}, {@code "writeFile → COMPLETED"}, or
     * {@code "editFile(...)"}. We only need a count (not file paths) to decide whether
     * an incomplete worker still produced real output.
     */
    private static int countFileWrites(java.util.List<String> toolLog) {
        if (toolLog == null || toolLog.isEmpty()) return 0;
        int n = 0;
        for (String entry : toolLog) {
            if (entry == null) continue;
            if (entry.startsWith("writeFile") || entry.startsWith("editFile")) n++;
        }
        return n;
    }
}
