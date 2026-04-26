package com.navneet.executespec.v2.rollout;

import com.navneet.executespec.v2.agent.Classification;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.Classification;
import com.navneet.executespec.v2.agent.OutlinePlannerAgent;
import com.navneet.executespec.v2.agent.PlannerRequestV2;
import com.navneet.executespec.v2.harness.ExecutionRequest;
import com.navneet.executespec.v2.harness.ExecutionResult;
import com.navneet.executespec.v2.harness.SequentialExecutorV2;
import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.reviewer.ReviewerAgentV3;
import com.navneet.executespec.v2.reviewer.ReviewerRequestV2;
import com.navneet.executespec.v2.reviewer.ReviewerResult;
import com.navneet.executespec.v2.tool.ExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * V2 run orchestrator — accepts a spec, runs it through planner → executor → reviewer
 * in a background thread, updates a {@link V2Run} record as it transitions through
 * status.
 *
 * <p>Phase 9 baseline — in-memory only, no DB persistence, no cost/credit tracking, no
 * SSE event stream. Production-canary features layer on top.
 *
 * <p>Workspace: each run gets its own temp directory under the system temp dir. A future
 * pass will integrate with V1's workspace lifecycle (gs://... uploads, preview
 * deployments, etc.); Phase 9 baseline is filesystem-only.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class V2RunService {

    private final V2RunRegistry registry;
    private final OutlinePlannerAgent planner;
    private final SequentialExecutorV2 executor;
    private final ReviewerAgentV3 reviewer;
    private final V2Metrics metrics;

    /** Dedicated thread pool for V2 pipeline execution — keeps runs off the request thread. */
    private final ExecutorService pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int n = 0;
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "v2-run-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    /** v2.5.12 — graceful shutdown so SIGTERM doesn't abandon in-flight runs. */
    @jakarta.annotation.PreDestroy
    public void shutdownPool() {
        log.info("V2RunService shutting down pool");
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("V2RunService pool did not terminate in 30s; forcing shutdownNow");
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    /** Look up a run by ID (for polling / status API). */
    public java.util.Optional<V2Run> find(String runId) {
        return registry.get(runId);
    }

    /**
     * Submit a new V2 run. Returns immediately with a freshly-allocated run ID — the
     * pipeline continues in the background.
     */
    public V2Run submit(Request req) {
        String runId = "v2-" + UUID.randomUUID();
        V2Run run = new V2Run(runId, req.spec(), req.provider(), req.model(),
                req.classification().name());
        registry.put(run);
        pool.submit(() -> executePipeline(run, req));
        log.info("V2RunService submitted run={} provider={} model={} classification={}",
                runId, req.provider(), req.model(), req.classification());
        return run;
    }

    // ─── pipeline orchestration (background thread) ─────────────────────────

    private void executePipeline(V2Run run, Request req) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("v2-run-" + run.runId() + "-");
            ExecutionContext runCtx = new ExecutionContext(
                    run.runId(), "__root__", workspace.toRealPath());

            // ── 1. Plan ─────────────────────────────────────────────────────
            run.transition(V2Run.Status.PLANNING, "calling OutlinePlannerAgent");
            PlanV2 plan;
            try {
                PlannerRequestV2 plannerReq = PlannerRequestV2.builder()
                        .spec(req.spec())
                        .classification(req.classification())
                        .provider(req.provider())
                        .model(req.model())
                        .context(runCtx)
                        .workspaceSummary(req.workspaceSummary())
                        .build();
                // v2.6.2 slice 5b — SINGLE_SHOT bypass via the synthesised
                // path; everything else through OutlinePlannerAgent FULL_PLAN.
                plan = (req.classification() == Classification.SINGLE_SHOT)
                        ? planner.synthesizeSingleShot(plannerReq)
                        : planner.plan(plannerReq, OutlinePlannerAgent.Mode.FULL_PLAN);
            } catch (Exception planErr) {
                log.error("V2RunService run={} planner failed", run.runId(), planErr);
                run.setFailureMessage("planner: " + planErr.getMessage());
                run.transition(V2Run.Status.FAILED, "planner exception");
                return;
            }
            run.setPlan(plan);

            // ── 2. Execute ──────────────────────────────────────────────────
            run.transition(V2Run.Status.EXECUTING, "plan has " + plan.tasks().size() + " tasks");
            ExecutionResult execResult;
            try {
                execResult = executor.execute(ExecutionRequest.builder()
                        .plan(plan)
                        .provider(req.provider())
                        .model(req.model())
                        .runContext(runCtx)
                        .maxIterationsPerTask(req.maxIterationsPerTask())
                        .enableSmokeTests(req.enableSmokeTests())
                        .build());
            } catch (Exception execErr) {
                log.error("V2RunService run={} executor failed", run.runId(), execErr);
                run.setFailureMessage("executor: " + execErr.getMessage());
                run.transition(V2Run.Status.FAILED, "executor exception");
                return;
            }
            run.setExecutionResult(execResult);

            if (execResult.overall() == ExecutionResult.Overall.ABORTED) {
                run.transition(V2Run.Status.FAILED, "executor ABORTED");
                return;
            }

            // ── 3. Review ───────────────────────────────────────────────────
            if (req.runReviewer()) {
                run.transition(V2Run.Status.REVIEWING, "running ReviewerAgentV3");
                ReviewerResult review;
                try {
                    review = reviewer.review(ReviewerRequestV2.builder()
                            .provider(req.provider())
                            .model(req.model())
                            .context(runCtx)
                            .buildCommand(plan.buildCommand())
                            .skipWiringPass(req.skipWiringPass())
                            .build());
                } catch (Exception reviewErr) {
                    log.error("V2RunService run={} reviewer failed", run.runId(), reviewErr);
                    run.setFailureMessage("reviewer: " + reviewErr.getMessage());
                    run.transition(V2Run.Status.FAILED, "reviewer exception");
                    return;
                }
                run.setReviewerResult(review);
            }

            run.transition(V2Run.Status.COMPLETE,
                    "overall=" + execResult.overall()
                            + (run.reviewerResult() != null
                                    ? "; review=" + run.reviewerResult().overall() : ""));
            log.info("V2RunService run={} COMPLETE execOverall={} reviewOverall={}",
                    run.runId(), execResult.overall(),
                    run.reviewerResult() == null ? "n/a" : run.reviewerResult().overall());
        } catch (Exception unexpected) {
            log.error("V2RunService run={} unexpected failure", run.runId(), unexpected);
            run.setFailureMessage("unexpected: " + unexpected.getMessage());
            run.transition(V2Run.Status.FAILED, "unexpected exception");
        } finally {
            // Emit terminal-state metrics exactly once per run. Runs through here
            // regardless of which transition path hit (success, plan-fail, exec-fail,
            // review-fail, unexpected). Submission-to-terminal latency is measured
            // from run.submittedAt() to now.
            String finalOverall = run.status().name();
            metrics.recordRunOutcome(finalOverall);
            metrics.recordRunLatency(finalOverall,
                    java.time.Duration.between(run.submittedAt(), java.time.Instant.now()));
            // Keep the workspace on disk — the caller (or a test harness) may want to
            // inspect artifacts. A future pass will upload to GCS on completion and
            // clean up. For Phase 9 baseline, we leak small temp dirs; acceptable trade.
            if (workspace != null) {
                log.info("V2RunService run={} workspace retained at {}", run.runId(), workspace);
            }
        }
    }

    /** Request shape for {@link #submit}. */
    public record Request(
            String spec,
            String provider,
            String model,
            Classification classification,
            String workspaceSummary,
            Integer maxIterationsPerTask,
            boolean enableSmokeTests,
            boolean runReviewer,
            boolean skipWiringPass) {

        public Request {
            if (spec == null || spec.isBlank()) throw new IllegalArgumentException("spec required");
            if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider required");
            if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
            if (classification == null) classification = Classification.MEDIUM;
            if (workspaceSummary == null) workspaceSummary = "empty scratch workspace";
        }
    }
}
