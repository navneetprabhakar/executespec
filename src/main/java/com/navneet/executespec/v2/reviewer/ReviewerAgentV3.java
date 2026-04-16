package com.navneet.executespec.v2.reviewer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.v2.agent.WorkerAgentV2;
import com.navneet.executespec.v2.agent.WorkerResult;
import com.navneet.executespec.v2.agent.WorkerTaskRequest;
import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.tool.BuildProjectTool;
import com.navneet.executespec.v2.tool.ExecutionContext;
import com.navneet.executespec.v2.tool.ToolResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * V2 post-execution code reviewer.
 *
 * <p>Runs after {@link com.navneet.executespec.v2.harness.SequentialExecutorV2}
 * completes. Executes two review passes as narrowly-scoped worker invocations:
 * <ol>
 *   <li><b>General review</b> — scan for logic bugs, incomplete implementations, contract
 *       violations, obvious runtime failures. Fix in place. Out of scope: style, formatting,
 *       refactoring preferences.</li>
 *   <li><b>Wiring review</b> — project-type-aware integration checks (Rust mod.rs, TS barrels,
 *       Spring component scan, Express route registration, etc.). Fix any orphans/missing
 *       registrations. This is the specialized sub-phase mentioned in the V2 plan P7.1.</li>
 * </ol>
 *
 * <p>After both passes, runs the build command once more and reports the final verdict:
 * CLEAN (nothing fixed) / FIXED_ISSUES (fixes applied + build passes) / UNRESOLVED_ISSUES
 * (build still fails) / REVIEW_FAILED (a pass didn't reach COMPLETED).
 *
 * <p>Implementation strategy: the reviewer is a thin composition over
 * {@link WorkerAgentV2} — it sends review-focused prompts through the same agent loop
 * rather than reinventing it. This gives reviewer the same native tool calling, context
 * handling, and completeTask signaling for free.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReviewerAgentV3 {

    /** Default iteration cap per review pass. Smaller than worker's 40 because review
     *  passes are fundamentally inspection + targeted edits, not generation. */
    public static final int DEFAULT_MAX_ITER_PER_PASS = 30;

    /** Wall-clock cap on the general pass — long enough for thorough review,
     *  short enough that a stuck LLM does not freeze the whole run.
     *  See v2.5.10 eval Bug C: a Gemini reviewer hung indefinitely with all
     *  worker tasks complete, blocking terminate + score persistence. */
    public static final long GENERAL_PASS_TIMEOUT_SECONDS = 90;

    /** Wall-clock cap on the wiring pass — narrower scope than general. */
    public static final long WIRING_PASS_TIMEOUT_SECONDS = 60;

    private final WorkerAgentV2 worker;
    private final BuildProjectTool buildProjectTool;
    private final ObjectMapper objectMapper;
    private final V2Metrics metrics;

    /** Per-call executor for the timeout wrapper. Cached pool so successive
     *  reviews reuse threads; daemon threads so JVM shutdown is not blocked. */
    private ExecutorService passExecutor;

    @PostConstruct
    void initExecutor() {
        passExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "reviewer-pass");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        if (passExecutor != null) {
            passExecutor.shutdownNow();
        }
    }

    public ReviewerResult review(ReviewerRequestV2 req) {
        ReviewerResult result = reviewImpl(req);
        metrics.recordReviewerVerdict(result.overall().name());
        return result;
    }

    private ReviewerResult reviewImpl(ReviewerRequestV2 req) {
        long start = System.currentTimeMillis();
        int maxIter = (req.maxIterationsPerPass() == null || req.maxIterationsPerPass() <= 0)
                ? DEFAULT_MAX_ITER_PER_PASS : req.maxIterationsPerPass();

        log.info("ReviewerAgentV3 START run={} task={} provider={} model={} skipWiring={}",
                req.context().runId(), req.context().taskId(),
                req.provider(), req.model(), req.skipWiringPass());

        // ── General pass ─────────────────────────────────────────────────────
        // A pass that hits the iteration cap has typically done meaningful fixes
        // before running out of turns — the workspace is still in a reasonable
        // state. Only a hard ERROR outcome (LLM failure, tool dispatch crash)
        // warrants REVIEW_FAILED. ITERATION_LIMIT_EXCEEDED and STOPPED_WITHOUT_COMPLETE
        // fall through to the final build, whose exit code dictates the verdict.
        WorkerResult general = runPassWithTimeout(req, maxIter,
                "reviewer-general", loadPrompt("prompts/v2/reviewer-general.md"),
                GENERAL_PASS_TIMEOUT_SECONDS);
        if (general.outcome() == WorkerResult.Outcome.ERROR) {
            log.warn("ReviewerAgentV3 general pass errored: {}", general.outcome());
            return ReviewerResult.builder()
                    .overall(ReviewerResult.Overall.REVIEW_FAILED)
                    .generalPassOutcome(general)
                    .totalLatencyMs(System.currentTimeMillis() - start)
                    .build();
        }
        if (general.outcome() != WorkerResult.Outcome.COMPLETED) {
            log.warn("ReviewerAgentV3 general pass did not call completeTask ({}), "
                    + "continuing to wiring pass with partial work", general.outcome());
        }

        // ── Wiring pass ──────────────────────────────────────────────────────
        WorkerResult wiring = null;
        if (!req.skipWiringPass()) {
            wiring = runPassWithTimeout(req, maxIter,
                    "reviewer-wiring", loadPrompt("prompts/v2/reviewer-wiring.md"),
                    WIRING_PASS_TIMEOUT_SECONDS);
            if (wiring.outcome() == WorkerResult.Outcome.ERROR) {
                log.warn("ReviewerAgentV3 wiring pass errored: {}", wiring.outcome());
                return ReviewerResult.builder()
                        .overall(ReviewerResult.Overall.REVIEW_FAILED)
                        .generalPassOutcome(general)
                        .generalPassSummary(general.completionSummary())
                        .wiringPassOutcome(wiring)
                        .totalLatencyMs(System.currentTimeMillis() - start)
                        .build();
            }
            if (wiring.outcome() != WorkerResult.Outcome.COMPLETED) {
                log.warn("ReviewerAgentV3 wiring pass did not call completeTask ({}), "
                        + "falling through to final build", wiring.outcome());
            }
        }

        // ── Final build ──────────────────────────────────────────────────────
        ObjectNode buildArgs = objectMapper.createObjectNode();
        if (req.buildCommand() != null && !req.buildCommand().isBlank()) {
            buildArgs.put("plannerCommand", req.buildCommand());
        }
        ToolResult buildResult = buildProjectTool.execute(buildArgs, req.context());
        boolean buildPassed = false;
        int buildExit = -1;
        String buildOutput = null;
        if (buildResult instanceof ToolResult.Ok ok) {
            buildOutput = ok.content();
            buildExit = extractExitCode(buildOutput);
            buildPassed = buildExit == 0;
        } else if (buildResult instanceof ToolResult.Err err) {
            buildOutput = "tool error: " + err.message();
        }

        ReviewerResult.Overall overall = determineOverall(general, wiring, buildPassed);
        long elapsed = System.currentTimeMillis() - start;
        log.info("ReviewerAgentV3 DONE run={} overall={} buildPassed={} elapsed={}ms",
                req.context().runId(), overall, buildPassed, elapsed);

        return ReviewerResult.builder()
                .overall(overall)
                .generalPassOutcome(general)
                .generalPassSummary(general.completionSummary())
                .wiringPassOutcome(wiring)
                .wiringPassSummary(wiring == null ? null : wiring.completionSummary())
                .finalBuildPassed(buildPassed)
                .finalBuildExitCode(buildExit)
                .finalBuildOutput(buildOutput)
                .totalLatencyMs(elapsed)
                .build();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private WorkerResult runPass(ReviewerRequestV2 req, int maxIter,
                                  String taskIdSuffix, String systemPrompt) {
        ExecutionContext passCtx = new ExecutionContext(
                req.context().runId(),
                req.context().taskId() + "-" + taskIdSuffix,
                req.context().workspaceRoot());
        return worker.run(WorkerTaskRequest.builder()
                .provider(req.provider())
                .model(req.model())
                .systemPrompt(systemPrompt)
                .userPrompt("Begin your review now. Use the tools to inspect, fix, build, "
                        + "and then call completeTask.")
                .context(passCtx)
                .maxIterations(maxIter)
                .temperature(0.0)
                .maxTokensPerCall(8192)
                .build());
    }

    /**
     * Runs a review pass on the worker pool with a wall-clock timeout. On
     * timeout the underlying future is cancelled (interrupting the worker
     * thread); we return a synthetic {@link WorkerResult} with outcome
     * {@code ERROR} so {@link #determineOverall} treats it as REVIEW_FAILED
     * and {@link com.navneet.executespec.v2.integration.V2RunOrchestrator#resolveTerminalStatus}
     * falls through to the build + execution signals.
     */
    private WorkerResult runPassWithTimeout(ReviewerRequestV2 req, int maxIter,
                                             String taskIdSuffix, String systemPrompt,
                                             long timeoutSeconds) {
        Future<WorkerResult> future = passExecutor.submit(
                () -> runPass(req, maxIter, taskIdSuffix, systemPrompt));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            log.warn("ReviewerAgentV3 pass {} TIMED OUT after {}s — treating as ERROR; "
                    + "final build verdict will determine run status",
                    taskIdSuffix, timeoutSeconds);
            return WorkerResult.builder()
                    .outcome(WorkerResult.Outcome.ERROR)
                    .completionSummary("review pass timeout after " + timeoutSeconds + "s")
                    .iterations(maxIter)
                    .build();
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return WorkerResult.builder()
                    .outcome(WorkerResult.Outcome.ERROR)
                    .completionSummary("review pass interrupted")
                    .iterations(0)
                    .build();
        } catch (Exception e) {
            log.error("ReviewerAgentV3 pass {} unexpected failure", taskIdSuffix, e);
            return WorkerResult.builder()
                    .outcome(WorkerResult.Outcome.ERROR)
                    .completionSummary("review pass crashed: " + e.getClass().getSimpleName())
                    .iterations(0)
                    .build();
        }
    }

    private ReviewerResult.Overall determineOverall(WorkerResult general, WorkerResult wiring,
                                                     boolean buildPassed) {
        String gSum = general.completionSummary() == null ? "" : general.completionSummary().toLowerCase();
        String wSum = wiring == null || wiring.completionSummary() == null
                ? "" : wiring.completionSummary().toLowerCase();
        // "no issues" / "no in-scope issues" / "no wiring issues" → clean signal
        boolean generalClean = gSum.contains("no issues") || gSum.contains("no in-scope")
                || gSum.contains("found 0");
        boolean wiringClean = wiring == null || wSum.contains("no wiring issues")
                || wSum.contains("no issues") || wSum.contains("found 0");
        if (generalClean && wiringClean && buildPassed) return ReviewerResult.Overall.CLEAN;
        if (buildPassed) return ReviewerResult.Overall.FIXED_ISSUES;
        return ReviewerResult.Overall.UNRESOLVED_ISSUES;
    }

    private String loadPrompt(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + path, e);
        }
    }

    private static int extractExitCode(String output) {
        if (output == null) return -1;
        int i = output.indexOf("exit=");
        if (i < 0) return -1;
        int j = i + "exit=".length();
        int k = j;
        while (k < output.length() && Character.isDigit(output.charAt(k))) k++;
        try { return Integer.parseInt(output.substring(j, k)); }
        catch (NumberFormatException nfe) { return -1; }
    }
}
