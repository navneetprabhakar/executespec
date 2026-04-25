package com.navneet.executespec.v2.test;

import com.navneet.executespec.v2.agent.Classification;
import com.navneet.executespec.v2.agent.OutlinePlannerAgent;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.PlannerRequestV2;
import com.navneet.executespec.v2.harness.ExecutionRequest;
import com.navneet.executespec.v2.harness.ExecutionResult;
import com.navneet.executespec.v2.harness.SequentialExecutorV2;
import com.navneet.executespec.v2.harness.TaskOutcome;
import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2 Phase 5 verification endpoint — end-to-end spec → plan → execute → verify loop.
 *
 * <p>Uses a small self-contained spec (build a Node.js module with two functions +
 * smoke test proving they work) so the whole pipeline runs in a clean temp workspace
 * with no external dependencies. Smoke tests are deliberately simple — the default
 * spec's buildCommand is {@code node -e "..."} which exits 0 iff the produced module
 * exports the expected functions.
 *
 * <p>Verifies:
 * <ul>
 *   <li>OutlinePlannerAgent produces a valid multi-task plan (slice 5b — was PlannerAgentV2)</li>
 *   <li>SequentialExecutorV2 iterates tasks in order, each reaching COMPLETED</li>
 *   <li>Smoke test runs after each task and the aggregate verdict is ALL_SUCCESS
 *       (or PARTIAL_WITH_WARNINGS if any task retried)</li>
 *   <li>Produced artifacts exist on disk (module files, verification script)</li>
 * </ul>
 *
 * <p>Usage: {@code GET /api/v2/test/executor?provider=anthropic&model=claude-haiku-4-5-20251001}
 */
@RestController
@RequestMapping("/api/v2/test")
@RequiredArgsConstructor
@Slf4j
public class ExecutorTestController {

    /** Default spec — small enough to finish in ~30-60s with modest token usage. */
    private static final String DEFAULT_SPEC = """
            Create a small Node.js module with two utility functions:

            1. A function `sumNumbers(arr)` in `sum.js` that returns the sum of an array of integers.
               Example: sumNumbers([10, 20, 30]) === 60.

            2. A function `maxNumber(arr)` in `max.js` that returns the largest integer in an array.
               Example: maxNumber([3, 1, 4, 1, 5, 9, 2, 6]) === 9.

            Both files should use CommonJS module.exports. No external dependencies.
            """;

    /**
     * Smoke test that loads both modules and asserts the two example calls. Used as
     * the planner-declared buildCommand; we override the plan's buildCommand on the
     * test endpoint side to ensure the assertions run regardless of what the planner
     * proposed.
     */
    private static final String SMOKE_COMMAND = """
            node -e "const s=require('./sum'); const m=require('./max'); \
            if (s.sumNumbers([10,20,30]) !== 60) { console.error('sum fail'); process.exit(1); } \
            if (m.maxNumber([3,1,4,1,5,9,2,6]) !== 9) { console.error('max fail'); process.exit(2); } \
            console.log('smoke ok');"
            """;

    private final OutlinePlannerAgent planner;
    private final SequentialExecutorV2 executor;

    @GetMapping("/executor")
    public Map<String, Object> testExecutor(
            @RequestParam(defaultValue = "anthropic") String provider,
            @RequestParam(defaultValue = "claude-haiku-4-5-20251001") String model,
            @RequestParam(required = false) String spec,
            @RequestParam(defaultValue = "MEDIUM") String classification) throws IOException {
        Path workspace = Files.createTempDirectory("v2-executor-test-");
        try {
            Classification cx = Classification.valueOf(classification.toUpperCase());
            String effectiveSpec = spec != null ? spec : DEFAULT_SPEC;
            ExecutionContext runCtx = new ExecutionContext(
                    "executor-test-run", "executor-test-root", workspace.toRealPath());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("provider", provider);
            out.put("model", model);
            out.put("classification", cx.name());
            out.put("workspace", workspace.toString());

            // ── 1. Plan ─────────────────────────────────────────────────────
            long planStart = System.currentTimeMillis();
            PlanV2 plan;
            try {
                PlannerRequestV2 req = PlannerRequestV2.builder()
                        .spec(effectiveSpec)
                        .classification(cx)
                        .provider(provider)
                        .model(model)
                        .context(runCtx)
                        .workspaceSummary("empty scratch workspace")
                        .build();
                // v2.6.2 slice 5b — SINGLE_SHOT bypass uses synthesizeSingleShot
                // (no LLM call); other classifications go through the
                // OutlinePlannerAgent FULL_PLAN path.
                plan = (cx == Classification.SINGLE_SHOT)
                        ? planner.synthesizeSingleShot(req)
                        : planner.plan(req, OutlinePlannerAgent.Mode.FULL_PLAN);
            } catch (Exception e) {
                out.put("phase", "plan");
                out.put("ok", false);
                out.put("error", e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 400));
                return out;
            }
            long planLatency = System.currentTimeMillis() - planStart;
            out.put("planLatencyMs", planLatency);
            out.put("planTaskCount", plan.tasks().size());
            out.put("planTitle", plan.planTitle());

            // Force our verification smoke command regardless of what the planner proposed —
            // ensures the test is deterministic and doesn't depend on planner ingenuity.
            PlanV2 effectivePlan = PlanV2.builder()
                    .planTitle(plan.planTitle())
                    .planSummary(plan.planSummary())
                    .projectType(plan.projectType())
                    .buildCommand(SMOKE_COMMAND.trim())
                    .smokeTestStrategy(PlanV2.SmokeTestStrategy.BUILD)
                    .classification(plan.classification())
                    .tasks(plan.tasks())
                    .build();

            // ── 2. Execute ──────────────────────────────────────────────────
            ExecutionResult result = executor.execute(ExecutionRequest.builder()
                    .plan(effectivePlan)
                    .provider(provider)
                    .model(model)
                    .runContext(runCtx)
                    .maxIterationsPerTask(20)
                    .enableSmokeTests(true)
                    .build());

            out.put("overall", result.overall().name());
            out.put("tasksExecuted", result.tasksExecuted());
            out.put("tasksSuccessful", result.tasksSuccessful());
            out.put("tasksWithWarning", result.tasksWithWarning());
            out.put("tasksFailed", result.tasksFailed());
            out.put("executeLatencyMs", result.totalLatencyMs());
            out.put("perTask", summarizeOutcomes(result.perTask()));

            // ── 3. Artifact verification ────────────────────────────────────
            Path sumJs = workspace.resolve("sum.js");
            Path maxJs = workspace.resolve("max.js");
            boolean sumExists = Files.exists(sumJs);
            boolean maxExists = Files.exists(maxJs);
            out.put("sumJsExists", sumExists);
            out.put("maxJsExists", maxExists);

            boolean overallOk =
                    (result.overall() == ExecutionResult.Overall.ALL_SUCCESS
                            || result.overall() == ExecutionResult.Overall.PARTIAL_WITH_WARNINGS)
                    && sumExists && maxExists;
            out.put("ok", overallOk);
            return out;
        } finally {
            deleteTree(workspace);
        }
    }

    private static List<Map<String, Object>> summarizeOutcomes(List<TaskOutcome> outcomes) {
        return outcomes.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", o.taskId());
            m.put("title", o.taskTitle());
            m.put("status", o.status().name());
            m.put("workerOutcome", o.workerOutcome() == null ? null : o.workerOutcome().outcome().name());
            m.put("iterations", o.workerOutcome() == null ? null : o.workerOutcome().iterations());
            m.put("toolCallsDispatched", o.toolCallsDispatched());
            m.put("retryAttempted", o.retryAttempted());
            if (o.retryAttempted()) {
                m.put("retryOutcome", o.retryWorkerOutcome() == null ? null
                        : o.retryWorkerOutcome().outcome().name());
            }
            m.put("smokeTestPassed", o.smokeTestPassed());
            m.put("smokeTestExitCode", o.smokeTestExitCode());
            m.put("latencyMs", o.totalLatencyMs());
            return m;
        }).toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void deleteTree(Path root) {
        try {
            if (!Files.exists(root)) return;
            try (var walk = Files.walk(root)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            }
        } catch (IOException ignored) {}
    }
}
