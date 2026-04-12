package com.navneet.executespec.v2.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.PlannedTaskV2;
import com.navneet.executespec.v2.agent.WorkerAgentV2;
import com.navneet.executespec.v2.agent.WorkerResult;
import com.navneet.executespec.v2.agent.WorkerTaskRequest;
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
            - writeFile creates NEW files only. To modify an existing file, use editFile.
            - editFile takes an array of {oldStr, newStr} pairs; every oldStr must occur
              exactly once in the current file.
            - After making changes, you may call buildProject to verify the project still
              compiles.
            - When the task is fully complete, call completeTask with a brief summary.
              Do not output any text that is not needed to complete the task.
            """;

    private final WorkerAgentV2 worker;
    private final BuildProjectTool buildProjectTool;
    private final ObjectMapper objectMapper;

    public ExecutionResult execute(ExecutionRequest req) {
        long runStart = System.currentTimeMillis();
        List<TaskOutcome> outcomes = new ArrayList<>();
        PlanV2 plan = req.plan();

        log.info("SequentialExecutorV2 START run={} tasks={} provider={} model={} smokeTests={}",
                req.runContext().runId(), plan.tasks().size(), req.provider(), req.model(),
                req.enableSmokeTests());

        for (PlannedTaskV2 task : plan.tasks()) {
            outcomes.add(executeOne(task, plan, req));
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

        String systemPrompt = composeSystemPrompt(task);
        String userPrompt = composeUserPrompt(task, null);

        log.info("Executor task={} ('{}') START", task.id(), task.title());
        WorkerResult primary = worker.run(WorkerTaskRequest.builder()
                .provider(req.provider())
                .model(req.model())
                .systemPrompt(systemPrompt)
                .userPrompt(userPrompt)
                .context(taskCtx)
                .maxIterations(req.maxIterationsPerTask())
                .temperature(0.0)
                .maxTokensPerCall(4096)
                .build());

        // Worker didn't reach COMPLETED — can't even attempt a smoke test meaningfully.
        if (primary.outcome() != WorkerResult.Outcome.COMPLETED) {
            log.warn("Executor task={} WORKER_FAILED outcome={} iters={}",
                    task.id(), primary.outcome(), primary.iterations());
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
                .provider(req.provider())
                .model(req.model())
                .systemPrompt(systemPrompt)
                .userPrompt(retryPrompt)
                .context(taskCtx)
                .maxIterations(req.maxIterationsPerTask())
                .temperature(0.0)
                .maxTokensPerCall(4096)
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

    // ─── prompt composition ─────────────────────────────────────────────────

    private String composeSystemPrompt(PlannedTaskV2 task) {
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
            return new SmokeTestResult(exit == 0, exit, content, elapsed);
        }
        // Tool Err — treat as smoke failure with a synthetic exit code
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
}
