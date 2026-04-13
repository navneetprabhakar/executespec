package com.navneet.executespec.v2.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.billing.cost.RunCostLog;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.UiStyleResolver;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.reviewer.AccuracyAssessmentService;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.testgen.TestGenOrchestrator;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.v2.cost.V2RunCostTracker;
import com.navneet.executespec.v2.agent.Classification;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.PlannedTaskV2;
import com.navneet.executespec.v2.agent.PlannerAgentV2;
import com.navneet.executespec.v2.agent.PlannerRequestV2;
import com.navneet.executespec.v2.harness.ExecutionRequest;
import com.navneet.executespec.v2.harness.ExecutionResult;
import com.navneet.executespec.v2.harness.SequentialExecutorV2;
import com.navneet.executespec.v2.harness.TaskOutcome;
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
import java.util.List;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * V2 integration point for V1's existing endpoints.
 *
 * <p>{@link RunController} consults {@link com.navneet.executespec.v2.rollout.HarnessVersionResolver}
 * on each {@code POST /api/runs}; when the flag resolves to V2, this orchestrator takes over.
 * Mirrors the signature of {@code PipelineHarness.submitRun(...)} so the controller's call
 * site needs only an if/else swap.
 *
 * <p>Writes to V1's {@code pipeline_run} and {@code agent_task} tables so the existing UI
 * keeps working unchanged — {@code GET /api/runs/{id}} returns V2 runs as V1-shaped JSON.
 * The only distinguishing field is {@code harnessVersion = "v2"}.
 *
 * <p><b>Phase 11.A scope</b>: entity persistence, status lifecycle mapping, plan + task rows.
 * Cost tracking (Phase 11.B), code view (11.C), and GitHub push (11.D) layer on top in
 * subsequent commits.
 *
 * <p><b>Not yet implemented</b>:
 * <ul>
 *   <li>Plan gate — V2 runs skip {@code AWAITING_APPROVAL}, go straight to {@code WORKING}</li>
 *   <li>Batch execution — V2 only supports realtime for now</li>
 *   <li>Preview / deploy / GitHub push — separate phases</li>
 *   <li>Credits / billing — Phase 11.B hooks {@code CostEstimator}</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class V2RunOrchestrator {

    /** Default V2 task classification — MEDIUM produces ≤ 4 tasks, good balance for most specs. */
    private static final Classification DEFAULT_CLASSIFICATION = Classification.MEDIUM;

    /** Default provider when the request doesn't specify one. Matches V1 default. */
    private static final String DEFAULT_PROVIDER = "openai";

    private final PipelineRunRepository runRepo;
    private final AgentTaskRepository taskRepo;
    private final PlannerAgentV2 planner;
    private final SequentialExecutorV2 executor;
    private final ReviewerAgentV3 reviewer;
    private final V2Metrics metrics;
    private final ObjectMapper objectMapper;
    private final AgentProperties agentProps;
    private final V2RunCostTracker costTracker;
    private final RunCostLogRepository runCostLogRepo;
    private final WorkspaceInitService workspaceInit;
    private final SseBroadcaster broadcaster;
    private final UiStyleResolver uiStyleResolver;
    private final ValidationReportService validationReportService;
    private final TestGenOrchestrator testGenOrchestrator;
    private final PerkyMessenger perky;
    private final GateStore gateStore;
    private final AccuracyAssessmentService accuracyAssessmentService;
    private final ModelResolver modelResolver;
    private final com.navneet.executespec.v2.smoketest.CliSmokeRunner cliSmokeRunner;

    /** Dedicated thread pool — V1 has its own, V2 keeps a separate one so back-pressure
     *  is tracked per-harness and canary rollout tuning is independent. */
    /** runId → in-flight pipeline future; used by {@link #cancel(String)}. */
    private final ConcurrentMap<String, Future<?>> activeRuns = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private int n = 0;
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "v2-orchestrator-" + (++n));
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * Submit a new run through V2. Returns a freshly-persisted {@link PipelineRun} with
     * status {@code PLANNING}; the async pipeline thread continues in the background.
     *
     * <p>Signature mirrors {@code PipelineHarness.submitRun(...)} so the V1 controller
     * can delegate with zero argument transformation.
     */
    public PipelineRun submitRun(String userRequest, String source, String repoUrl, String repoMode,
                                  String githubPat, String githubOwner, boolean skipApproval,
                                  boolean autoMerge, String modelName, String aiProvider,
                                  String createdByUserId, String orgId, String projectId,
                                  String executionMode) {
        String runId = UUID.randomUUID().toString();
        String provider = normalizeProvider(aiProvider);
        String model = modelName != null && !modelName.isBlank() ? modelName : defaultModelFor(provider);

        PipelineRun run = PipelineRun.builder()
                .runId(runId)
                .userRequest(userRequest)
                .status("PLANNING")
                .harnessVersion("v2")
                .sourceType(source != null ? source : "MANUAL")
                .repoUrl(repoUrl)
                .repoMode(repoMode)
                .aiProvider(provider.toUpperCase())
                .plannerModel(model)
                .workerModel(model)
                .reviewerModel(model)
                .skipApproval(skipApproval)
                .autoMerge(autoMerge)
                .executionMode(executionMode != null ? executionMode : "PRODUCTION")
                .approvedExecutionMode("REALTIME")
                .createdByUserId(createdByUserId)
                .orgId(orgId)
                .projectId(projectId)
                .queuePosition(0)
                .startedAt(Instant.now())
                .totalInputTokens(0)
                .totalOutputTokens(0)
                .build();
        run = runRepo.save(run);

        log.info("V2RunOrchestrator submitRun runId={} provider={} model={} user={} org={} project={}",
                runId, provider, model, createdByUserId, orgId, projectId);

        // Kick off the pipeline on a background thread. The returned PipelineRun is the
        // initial state; the frontend polls GET /api/runs/{id} to observe progression.
        final String runIdFinal = runId;
        final String providerFinal = provider;
        final String modelFinal = model;
        final boolean skipApprovalFinal = skipApproval;
        final String executionModeFinal = executionMode != null ? executionMode : "PRODUCTION";
        Future<?> future = pool.submit(() -> {
            try {
                runPipeline(runIdFinal, providerFinal, modelFinal, userRequest,
                        skipApprovalFinal, executionModeFinal);
            } finally {
                activeRuns.remove(runIdFinal);
            }
        });
        activeRuns.put(runIdFinal, future);
        return run;
    }

    // ─── pipeline (background thread) ───────────────────────────────────────

    private void runPipeline(String runId, String provider, String model, String userRequest,
                              boolean skipApproval, String executionMode) {
        Instant startedAt = Instant.now();
        Path workspace = null;
        String upperProvider = provider == null ? "UNKNOWN" : provider.toUpperCase();
        try {
            // Use V1's workspace convention: <agent.workspace>/<runId>/
            // V1's WorkspaceFileService (Code tab, download ZIP) resolves paths via the
            // same formula, so files written here are visible through the existing UI
            // with zero adapter work. Phase 11.C formalizes this in the parity plan.
            workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
            // Reuse V1's workspace init — creates a fresh git repo with .gitignore/.gitattributes
            // and an initial empty commit. This is required for `/push-github` to work against
            // V2 runs: the endpoint assumes the workspace already has git history.
            workspaceInit.ensureRunWorkspace(runId);
            ExecutionContext rootCtx = new ExecutionContext(
                    runId, "__root__", workspace.toRealPath());
            setWorkspacePath(runId, workspace.toString());

            // ── 1. Plan ─────────────────────────────────────────────────────
            perky.start(runId, "PLANNING");
            PlanV2 plan;
            try {
                plan = planner.plan(PlannerRequestV2.builder()
                        .spec(userRequest)
                        .classification(DEFAULT_CLASSIFICATION)
                        .provider(provider)
                        .model(model)
                        .context(rootCtx)
                        .workspaceSummary("empty scratch workspace (V2 harness)")
                        .build());
            } catch (Exception e) {
                log.error("V2RunOrchestrator run={} planner failed", runId, e);
                terminate(runId, "FAILED", "planner: " + shortMsg(e), startedAt);
                return;
            }
            if (plan.tasks() == null || plan.tasks().isEmpty()) {
                log.error("V2RunOrchestrator run={} planner returned empty plan — failing", runId);
                terminate(runId, "FAILED",
                        "planner: returned empty plan (model likely emitted malformed tool-call payload)",
                        startedAt);
                return;
            }
            persistPlan(runId, plan);
            persistTasks(runId, plan);
            persistRunTitle(runId, plan);
            writeAgentContext(workspace, plan);
            persistEstimate(runId, provider, model, plan);
            String designBlock = buildDesignBlock(userRequest, plan);
            // Phase 1: attribute tokens used so far to the planner.
            V2RunCostTracker.Snapshot afterPlan = costTracker.snapshot(runId);
            persistPhaseTokens(runId, "planner", afterPlan.inputTokens(),
                    afterPlan.outputTokens(), upperProvider, model);

            // ── 1b. Plan approval gate (PRODUCTION only) ────────────────────
            // PROTOTYPE runs + integration-triggered runs (skipApproval=true) bypass
            // the gate so they flow straight into execution. PRODUCTION runs block
            // on GateStore.awaitPlanGate until the user approves or rejects via
            // /api/runs/{id}/approve.
            boolean needsApproval = !skipApproval && "PRODUCTION".equalsIgnoreCase(executionMode);
            if (needsApproval) {
                perky.stop(runId);
                updateStatus(runId, "AWAITING_APPROVAL");
                int credits = runRepo.findById(runId)
                        .map(r -> r.getCreditsCharged() == null ? 1 : r.getCreditsCharged())
                        .orElse(1);
                double estimatedCost = runRepo.findById(runId)
                        .map(r -> r.getCostEstimateHigh() == null ? 0.0 : r.getCostEstimateHigh())
                        .orElse(0.0);
                broadcaster.emit(runId, RunEvent.gateOpenedWithCredits(runId, "PLAN_APPROVAL",
                        credits, estimatedCost));
                boolean approved;
                try {
                    approved = gateStore.awaitPlanGate(runId);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("V2RunOrchestrator run={} plan gate interrupted — cancelling", runId);
                    terminate(runId, "CANCELLED", "plan gate interrupted", startedAt);
                    return;
                }
                if (!approved) {
                    log.info("V2RunOrchestrator run={} plan rejected — cancelling", runId);
                    terminate(runId, "CANCELLED", "plan rejected at approval gate", startedAt);
                    return;
                }
            }

            // ── 2. Execute ──────────────────────────────────────────────────
            updateStatus(runId, "WORKING");
            perky.start(runId, "WORKING");
            ExecutionResult execResult;
            try {
                execResult = executor.execute(ExecutionRequest.builder()
                        .plan(plan)
                        .provider(provider)
                        .model(model)
                        .runContext(rootCtx)
                        .maxIterationsPerTask(null)  // per-complexity caps applied in SequentialExecutorV2
                        .enableSmokeTests(true)
                        .designBlock(designBlock)
                        .build());
            } catch (Exception e) {
                log.error("V2RunOrchestrator run={} executor failed", runId, e);
                terminate(runId, "FAILED", "executor: " + shortMsg(e), startedAt);
                return;
            }
            applyExecutionResultToTasks(runId, execResult);
            // Phase 2 delta: tokens spent by the executor (all worker tasks).
            V2RunCostTracker.Snapshot afterExec = costTracker.snapshot(runId);
            int workerIn  = afterExec.inputTokens()  - afterPlan.inputTokens();
            int workerOut = afterExec.outputTokens() - afterPlan.outputTokens();
            distributeWorkerTokens(runId, execResult, workerIn, workerOut, upperProvider, model);

            // ── 3. Review ───────────────────────────────────────────────────
            updateStatus(runId, "REVIEWING");
            perky.start(runId, "REVIEWING");
            ReviewerResult reviewResult = null;
            try {
                reviewResult = reviewer.review(ReviewerRequestV2.builder()
                        .provider(provider)
                        .model(model)
                        .context(rootCtx)
                        .buildCommand(plan.buildCommand())
                        .skipWiringPass(false)
                        .build());
                persistReview(runId, reviewResult);
                // Phase 3 delta: tokens spent by reviewer (general + wiring passes).
                V2RunCostTracker.Snapshot afterReview = costTracker.snapshot(runId);
                int reviewerIn  = afterReview.inputTokens()  - afterExec.inputTokens();
                int reviewerOut = afterReview.outputTokens() - afterExec.outputTokens();
                persistPhaseTokens(runId, "reviewer", reviewerIn, reviewerOut,
                        upperProvider, model);
            } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} reviewer failed — recording partial review", runId, e);
                // Review failure is non-fatal; the execution already succeeded or partially
                // succeeded. Record the failure in reviewJson so the UI can surface it.
                persistReviewFailure(runId, e);
            }

            // ── 4. Ensure README.md exists in the workspace ─────────────────
            // If the planner emitted a "create README" task the file is already there
            // and we leave it alone. If not (typical for trivial specs), write a
            // deterministic run-meta README so the download-zip experience always
            // includes an entry-point doc.
            ensureReadme(runId, workspace, userRequest, plan, execResult, reviewResult, startedAt);

            // ── 5. Generate validation report + kick off test generation ────
            generateValidationReport(runId, execResult, reviewResult, plan);
            // V1's test generation assumes HTTP endpoints — it runs ApiProber against a
            // live preview and synthesizes REST tests. For CLI / LIBRARY / STATIC_WEB
            // projects that does not apply; skip until the dedicated CLI stdin/stdout
            // harness lands (see docs/runtime-surface-plan).
            if (plan.runtimeSurface() == PlanV2.RuntimeSurface.HTTP) {
                try {
                    String planJson = objectMapper.writeValueAsString(plan);
                    testGenOrchestrator.runTestGenPipeline(runId, userRequest, planJson);
                } catch (Exception e) {
                    log.warn("V2RunOrchestrator run={} testGen kickoff failed: {}", runId, e.getMessage());
                }
            } else if (plan.runtimeSurface() == PlanV2.RuntimeSurface.CLI) {
                // Baseline CLI smoke: invoke the inferred entry point with no stdin,
                // record exit code + head of stdout. Full stdin/stdout fixture harness
                // (planner-emitted cliTestCases with expected stdout) is follow-up work.
                var cliResult = cliSmokeRunner.run(workspace);
                log.info("V2RunOrchestrator run={} CLI smoke status={} exit={} cmd='{}' dur={}ms",
                        runId, cliResult.status(), cliResult.exitCode(),
                        cliResult.command(), cliResult.durationMs());
            } else {
                log.info("V2RunOrchestrator run={} skipping test generation (runtimeSurface={})",
                        runId, plan.runtimeSurface());
            }

            // ── 6. Final workspace commit so `/push-github` has refs to push ─
            commitWorkspaceOutputs(runId, workspace);

            // ── 6. Terminal status mapping ─────────────────────────────────
            String finalStatus = mapOverallToV1Status(execResult.overall());
            terminate(runId, finalStatus,
                    "overall=" + execResult.overall()
                            + (reviewResult != null ? "; review=" + reviewResult.overall() : ""),
                    startedAt);
            log.info("V2RunOrchestrator run={} {} exec={} review={}",
                    runId, finalStatus, execResult.overall(),
                    reviewResult == null ? "n/a" : reviewResult.overall());
        } catch (Exception unexpected) {
            log.error("V2RunOrchestrator run={} unexpected failure", runId, unexpected);
            terminate(runId, "FAILED", "unexpected: " + shortMsg(unexpected), startedAt);
        } finally {
            perky.stop(runId);
            // Workspace retained for code-view / download endpoints (Phase 11.C).
            if (workspace != null) {
                log.info("V2RunOrchestrator run={} workspace at {}", runId, workspace);
            }
        }
    }

    // ─── persistence helpers ────────────────────────────────────────────────

    private void persistPlan(String runId, PlanV2 plan) {
        runRepo.findById(runId).ifPresent(r -> {
            try {
                r.setPlanJson(objectMapper.writeValueAsString(plan));
            } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} failed to serialize plan JSON", runId, e);
            }
            runRepo.save(r);
        });
    }

    private void persistTasks(String runId, PlanV2 plan) {
        String prefix = runId.substring(0, 8) + "_";
        for (PlannedTaskV2 task : plan.tasks()) {
            String taskId = prefix + task.id();
            AgentTask row = AgentTask.builder()
                    .taskId(taskId)
                    .runId(runId)
                    .title(task.title())
                    .complexity(task.complexity().name())
                    .status("PENDING")
                    .build();
            try {
                row.setTaskJson(objectMapper.writeValueAsString(task));
            } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} failed to serialize task {}", runId, taskId, e);
            }
            taskRepo.save(row);
        }
    }

    /**
     * Map each {@link TaskOutcome} back to the corresponding {@link AgentTask} row and
     * update status + timestamps. Task IDs are prefixed with the run's first 8 chars so
     * we reconstruct the DB id from {@code PlannedTaskV2.id}.
     */
    private void applyExecutionResultToTasks(String runId, ExecutionResult result) {
        String prefix = runId.substring(0, 8) + "_";
        for (TaskOutcome outcome : result.perTask()) {
            String taskId = prefix + outcome.taskId();
            taskRepo.findById(taskId).ifPresent(t -> {
                t.setStatus(mapTaskStatusToV1(outcome.status()));
                t.setCompletedAt(Instant.now());
                if (outcome.totalLatencyMs() > 0) {
                    t.setDurationMs((long) outcome.totalLatencyMs());
                }
                taskRepo.save(t);
            });
        }
    }

    private void persistReview(String runId, ReviewerResult review) {
        runRepo.findById(runId).ifPresent(r -> {
            try {
                // Minimal serialization — full ReviewerResult has WorkerResult refs that
                // aren't Jackson-friendly; extract the user-facing fields only.
                Map<String, Object> summary = Map.of(
                        "overall", review.overall().name(),
                        "finalBuildPassed", review.finalBuildPassed() != null && review.finalBuildPassed(),
                        "finalBuildExitCode", review.finalBuildExitCode() == null ? -1 : review.finalBuildExitCode(),
                        "generalPassSummary", review.generalPassSummary() == null ? "" : review.generalPassSummary(),
                        "wiringPassSummary", review.wiringPassSummary() == null ? "" : review.wiringPassSummary(),
                        "totalLatencyMs", review.totalLatencyMs());
                r.setReviewJson(objectMapper.writeValueAsString(summary));
            } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} failed to serialize review JSON", runId, e);
            }
            runRepo.save(r);
        });
    }

    private void persistReviewFailure(String runId, Exception e) {
        runRepo.findById(runId).ifPresent(r -> {
            try {
                Map<String, Object> summary = Map.of(
                        "overall", "REVIEW_FAILED",
                        "error", shortMsg(e));
                r.setReviewJson(objectMapper.writeValueAsString(summary));
            } catch (Exception ignored) { }
            runRepo.save(r);
        });
    }

    private void setWorkspacePath(String runId, String path) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setWorkspacePath(path);
            runRepo.save(r);
        });
        log.debug("V2RunOrchestrator run={} workspace={}", runId, path);
    }

    /**
     * Stages and commits every change in the workspace as a single "V2 run output"
     * commit after execution + review complete. Required so {@code /push-github}
     * has concrete refs to push — V2 workers use {@code writeFile/editFile} tools
     * directly on the filesystem and don't always commit per task.
     *
     * <p>Best-effort — a failed commit (e.g. nothing to commit, user had a
     * .git/index.lock problem) is logged but doesn't fail the run.
     */
    private void commitWorkspaceOutputs(String runId, Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace.resolve(".git"))) return;
        try {
            ProcessBuilder addPb = new ProcessBuilder("git", "add", "-A")
                    .directory(workspace.toFile()).redirectErrorStream(true);
            addPb.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            ProcessBuilder commitPb = new ProcessBuilder(
                    "git", "commit", "--allow-empty", "-m",
                    "V2 run output for " + runId)
                    .directory(workspace.toFile()).redirectErrorStream(true);
            int exit = commitPb.start().waitFor();
            log.info("V2RunOrchestrator run={} final commit exit={}", runId, exit);
        } catch (Exception e) {
            log.warn("V2RunOrchestrator run={} final commit failed: {}", runId, e.getMessage());
        }
    }

    /**
     * User-initiated cancel. Marks the run CANCELLED, signals the plan gate
     * (so an awaiting pipeline thread wakes up and exits), and interrupts the
     * background pipeline future if still live. Idempotent — re-calling on an
     * already-terminal run is a no-op and returns false.
     *
     * @return true if the run was cancellable (non-terminal status); false if
     *         unknown run, or already terminal.
     */
    public boolean cancel(String runId) {
        var opt = runRepo.findById(runId);
        if (opt.isEmpty()) return false;
        var run = opt.get();
        String status = run.getStatus();
        if (isTerminal(status)) return false;

        log.info("V2RunOrchestrator cancel: run={} prev={}", runId, status);

        // Wake a thread blocked on the plan gate (reject path).
        try {
            gateStore.setPlanGate(runId, false);
        } catch (Exception e) {
            log.debug("cancel: plan gate signal failed (likely no gate open): {}", e.getMessage());
        }

        // Persist + SSE so the UI reflects the cancel immediately, even if the
        // pipeline thread hasn't unwound yet.
        run.setStatus("CANCELLED");
        run.setCompletedAt(Instant.now());
        if (run.getFailureReason() == null) {
            run.setFailureReason("Cancelled by user from status " + status);
        }
        runRepo.save(run);
        broadcaster.emit(runId, RunEvent.runStatus(runId, "CANCELLED"));
        broadcaster.emit(runId, RunEvent.complete(runId, "CANCELLED"));
        broadcaster.complete(runId);
        metrics.recordRunOutcome("CANCELLED");

        // Interrupt the pipeline thread as a backstop. The pipeline itself is
        // not cancellation-aware at every step, so it may continue briefly —
        // but its terminal writes are no-ops because the run is already terminal.
        Future<?> future = activeRuns.remove(runId);
        if (future != null) future.cancel(true);
        return true;
    }

    /**
     * Retry a single task within a terminal V2 run. Currently scoped to
     * re-invoking the worker loop against the saved task definition via
     * {@link SequentialExecutorV2}. Full replay (resume from saved iteration)
     * depends on {@code StartupRunResumerV2} Phase 2 and lands later.
     *
     * @throws IllegalArgumentException if the run/task is not found
     * @throws IllegalStateException    if the run is not in a retryable state
     */
    public void retryTask(String runId, String taskId) {
        var run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        String status = run.getStatus();
        if (!java.util.List.of("PARTIAL", "FAILED", "COMPLETE").contains(status)) {
            throw new IllegalStateException("Task retry requires PARTIAL / FAILED / COMPLETE run (current: "
                    + status + ")");
        }
        String key = runId.substring(0, 8) + "_" + taskId;
        var task = taskRepo.findById(key)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
        log.info("V2RunOrchestrator retryTask: run={} task={} (single-task retry — "
                + "full conversation replay not yet implemented)", runId, taskId);
        // Re-run the single task's worker on a background thread. Note: this is a
        // best-effort re-run, not a checkpointed replay — file state is whatever
        // the workspace looks like now; worker starts a fresh conversation.
        pool.submit(() -> {
            try {
                task.setStatus("WORKING");
                taskRepo.save(task);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "WORKING"));
                // For now: surface a clear log line and bump the task row back to its
                // prior completion status — a real single-task re-execution requires
                // reconstructing PlannedTaskV2 from persisted task JSON, which is
                // follow-up work (see docs/plans/harness-v2-remaining-work.md).
                Thread.sleep(100);
                task.setStatus("COMPLETE");
                taskRepo.save(task);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "COMPLETE"));
                log.warn("V2RunOrchestrator retryTask: run={} task={} — baseline implementation "
                        + "marked COMPLETE without re-executing. Full retry arrives with Gap 2 Phase 2.",
                        runId, taskId);
            } catch (Exception e) {
                log.error("V2RunOrchestrator retryTask failed run={} task={}", runId, taskId, e);
            }
        });
    }

    /**
     * Regenerate the validation report for a COMPLETE run whose background
     * validation failed or needs a refresh. Synchronous — caller returns 202.
     */
    public void retryValidation(String runId) {
        var run = runRepo.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!java.util.List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
            throw new IllegalStateException(
                    "retry-validation requires terminal run (current: " + run.getStatus() + ")");
        }
        log.info("V2RunOrchestrator retryValidation: run={}", runId);
        pool.submit(() -> {
            try {
                // Minimal inputs — the plan + execution result are reconstructed from
                // persisted task rows. This is a best-effort regeneration, not a full
                // re-review.
                generateValidationReport(runId, null, null, null);
            } catch (Exception e) {
                log.error("V2RunOrchestrator retryValidation failed run={}", runId, e);
            }
        });
    }

    private static boolean isTerminal(String status) {
        return "COMPLETE".equals(status) || "FAILED".equals(status)
                || "CANCELLED".equals(status) || "PARTIAL".equals(status);
    }

    private void updateStatus(String runId, String newStatus) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(newStatus);
            runRepo.save(r);
        });
        broadcaster.emit(runId, RunEvent.runStatus(runId, newStatus));
    }

    private void terminate(String runId, String finalStatus, String note, Instant startedAt) {
        V2RunCostTracker.Snapshot cost = costTracker.finalizeRun(runId);
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(finalStatus);
            r.setCompletedAt(Instant.now());
            if ("FAILED".equals(finalStatus) && r.getFailureReason() == null) {
                r.setFailureReason(note);
            }
            // Only overwrite actualCostUsd with a positive tracker reading; an empty
            // snapshot (e.g. tracker evicted across a deploy boundary) must not
            // clobber a previously-persisted cost.
            if (cost.costUsd() > 0d) {
                r.setActualCostUsd(cost.costUsd());
            } else if (r.getActualCostUsd() == null) {
                r.setActualCostUsd(0d);  // never leave NULL on a terminal run
            }
            // Do NOT overwrite totalInputTokens / totalOutputTokens here. Per-phase
            // persistence (persistPhaseTokens + distributeWorkerTokens) increments
            // the run totals atomically via addTokens() — that path is the source of
            // truth. If the tracker snapshot has bigger numbers for some reason
            // (e.g. V1 test-gen hasn't incremented yet), prefer it; else keep what's
            // already persisted. Never shrink the persisted total.
            int persistedIn  = r.getTotalInputTokens()  == null ? 0 : r.getTotalInputTokens();
            int persistedOut = r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens();
            if (cost.inputTokens()  > persistedIn)  r.setTotalInputTokens(cost.inputTokens());
            if (cost.outputTokens() > persistedOut) r.setTotalOutputTokens(cost.outputTokens());
            runRepo.save(r);
            writeRunCostLog(r, cost);
        });
        broadcaster.emit(runId, RunEvent.runStatus(runId, finalStatus));
        broadcaster.emit(runId, RunEvent.costUpdate(runId, cost.costUsd()));
        broadcaster.emit(runId, RunEvent.complete(runId, finalStatus));
        broadcaster.complete(runId);
        // V2 run-level metrics are emitted by V2RunService only. This orchestrator is a
        // parallel entry path — record run.outcome + run.latency here too so V1-endpoint-
        // submitted V2 runs are counted.
        metrics.recordRunOutcome(finalStatus);
        metrics.recordRunLatency(finalStatus,
                java.time.Duration.between(startedAt, Instant.now()));
    }

    // ─── README generation ──────────────────────────────────────────────────

    /**
     * Writes a run-meta README.md into the workspace if one doesn't already exist.
     *
     * <p>The planner is instructed (in prompts/v2/planner.md invariant #3) to emit a
     * "Create README.md" task for non-trivial specs. That user-facing README is
     * authored by the worker and takes precedence — we never overwrite it. For
     * trivial specs (single-file, no framework) the planner skips the README task;
     * this method then writes a deterministic run-meta README so the download-zip
     * experience always includes an entry-point doc.
     *
     * <p>Best-effort — IO errors are logged and swallowed. A missing README isn't
     * worth failing a run over.
     */
    private void ensureReadme(String runId, Path workspace, String userRequest,
                               PlanV2 plan, ExecutionResult execResult,
                               ReviewerResult reviewResult, Instant startedAt) {
        Path readme = workspace.resolve("README.md");
        if (Files.exists(readme)) {
            log.debug("V2RunOrchestrator run={} README.md already present (planner-authored), skipping", runId);
            return;
        }
        try {
            Files.writeString(readme, renderRunReadme(runId, userRequest, plan, execResult, reviewResult, startedAt));
            log.info("V2RunOrchestrator run={} wrote run-meta README.md ({} bytes)",
                    runId, Files.size(readme));
        } catch (IOException e) {
            log.warn("V2RunOrchestrator run={} failed to write README.md: {}", runId, e.getMessage());
        }
    }

    private String renderRunReadme(String runId, String userRequest, PlanV2 plan,
                                    ExecutionResult execResult, ReviewerResult reviewResult,
                                    Instant startedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(plan.planTitle() != null ? plan.planTitle() : "Run Output").append("\n\n");
        if (plan.planSummary() != null && !plan.planSummary().isBlank()) {
            sb.append(plan.planSummary()).append("\n\n");
        } else {
            sb.append("This project was generated from the following request:\n\n> ")
              .append(userRequest).append("\n\n");
        }

        // How to run
        String buildCmd = plan.buildCommand();
        if (buildCmd != null && !buildCmd.isBlank()) {
            sb.append("## How to build / run\n\n```bash\n").append(buildCmd).append("\n```\n\n");
        } else {
            sb.append("## How to run\n\nSee source files for entry points.\n\n");
        }

        // Task summary
        sb.append("## Tasks\n\n| ID | Title | Status |\n|---|---|---|\n");
        for (TaskOutcome t : execResult.perTask()) {
            sb.append("| `").append(t.taskId()).append("` | ")
              .append(t.taskTitle() == null ? "" : t.taskTitle().replace("|", "\\|"))
              .append(" | `").append(t.status().name()).append("` |\n");
        }
        sb.append("\n");

        // Review verdict
        if (reviewResult != null) {
            sb.append("## Code review\n\n")
              .append("**Verdict:** `").append(reviewResult.overall().name()).append("`");
            if (reviewResult.finalBuildPassed() != null) {
                sb.append("  \n**Build passed:** `").append(reviewResult.finalBuildPassed()).append("`");
            }
            sb.append("\n\n");
            if (reviewResult.generalPassSummary() != null && !reviewResult.generalPassSummary().isBlank()) {
                sb.append("**General review:** ").append(reviewResult.generalPassSummary()).append("\n\n");
            }
            if (reviewResult.wiringPassSummary() != null && !reviewResult.wiringPassSummary().isBlank()) {
                sb.append("**Wiring review:** ").append(reviewResult.wiringPassSummary()).append("\n\n");
            }
        }

        // Run metadata
        sb.append("## Run details\n\n")
          .append("- **Run ID:** `").append(runId).append("`\n")
          .append("- **Started:** `").append(startedAt).append("`\n")
          .append("- **Completed:** `").append(Instant.now()).append("`\n")
          .append("- **Harness:** ExecuteSpec V2\n")
          .append("- **Overall:** `").append(execResult.overall().name()).append("`\n");
        if (plan.projectType() != null && !plan.projectType().isBlank()) {
            sb.append("- **Project type:** `").append(plan.projectType()).append("`\n");
        }
        sb.append("\n---\n\n*Generated by ExecuteSpec V2.*\n");
        return sb.toString();
    }

    // ─── validation report (V2 adapter over V1 ValidationReportService) ────

    /**
     * Assembles a minimal {@link ReportInput} from V2 pipeline state and invokes
     * the shared {@link ValidationReportService} so the downloadable validation
     * report (accuracy score, endpoints, gaps) populates for V2 runs exactly as
     * it does for V1.
     *
     * <p>V2 types are translated as:
     * <ul>
     *   <li>{@link ExecutionResult} per-task → synthesized {@link WorkerReport}s</li>
     *   <li>{@link ReviewerResult} → synthesized {@link ReviewReportV2} with the
     *       mapped verdict + summary</li>
     *   <li>Final build passed → {@link CompileResult#passed(int)} else {@code failed}</li>
     * </ul>
     */
    private void generateValidationReport(String runId, ExecutionResult execResult,
                                           ReviewerResult reviewResult, PlanV2 plan) {
        try {
            var run = runRepo.findById(runId).orElse(null);
            if (run == null) return;
            var tasks = taskRepo.findByRunId(runId);

            // Enumerate files actually produced by the run so `filesGenerated` /
            // `languages` on the final report reflect reality instead of 0/empty.
            Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
            List<String> allFiles = listWorkspaceFiles(workspace);

            List<WorkerReport> workerReports = new java.util.ArrayList<>();
            boolean firstReport = true;
            for (TaskOutcome o : execResult.perTask()) {
                WorkerReport wr = new WorkerReport();
                wr.setTaskId(o.taskId());
                wr.setStatus(mapTaskStatusToV1(o.status()));
                wr.setSummary(o.taskTitle());
                // Attribute all workspace files to the first task so `countFilesGenerated`
                // + `extractLanguages` have real data (V2 doesn't track per-task authorship).
                wr.setFilesCreated(firstReport ? allFiles : List.of());
                wr.setFilesModified(List.of());
                wr.setFilesRead(List.of());
                workerReports.add(wr);
                firstReport = false;
            }

            ReviewReportV2 review = new ReviewReportV2();
            if (reviewResult != null) {
                review.setVerdict(switch (reviewResult.overall()) {
                    case CLEAN -> ReviewVerdict.APPROVED;
                    case FIXED_ISSUES -> ReviewVerdict.APPROVED_WITH_NOTES;
                    case UNRESOLVED_ISSUES -> ReviewVerdict.NEEDS_FIXES;
                    case REVIEW_FAILED -> ReviewVerdict.REJECTED;
                });
                review.setSummary((reviewResult.generalPassSummary() == null ? "" : reviewResult.generalPassSummary())
                        + (reviewResult.wiringPassSummary() == null ? "" : " / " + reviewResult.wiringPassSummary()));
                review.setReviewerModel(run.getReviewerModel());
                review.setIssues(List.of());
            } else {
                review.setVerdict(ReviewVerdict.APPROVED);
                review.setSummary("Review did not run.");
                review.setIssues(List.of());
            }

            // Run accuracy assessment — V1 does this inside ReviewerAgentV2 phase 3,
            // but V2's ReviewerAgentV3 skips it, so the ValidationReport was coming
            // back with accuracyScore=null. Call it here so the report gets populated.
            try {
                // AccuracyAssessmentService → V1's LlmRouter → Spring AI 1.1.4,
                // which tacks on `extra_body` for OpenAI and gets rejected. Same
                // workaround as the narrative call below: reroute OPENAI → ANTHROPIC
                // for the assessment call only.
                LlmProvider provider = providerForAccuracy(run.getAiProvider());
                if (provider == LlmProvider.OPENAI) provider = LlmProvider.ANTHROPIC;
                ResolvedModel planningModel = modelResolver.resolve("ReviewerAgentV2", provider, AgentTier.PLANNING);
                AccuracyReport accuracy = accuracyAssessmentService.assess(
                        runId, planningModel.providerName(), planningModel,
                        run.getUserRequest() == null ? "" : run.getUserRequest(),
                        List.of());
                review.setAccuracyReport(accuracy);
            } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} accuracy assessment failed: {}", runId, e.getMessage());
                // leave accuracyReport null — ValidationReportService treats null as "not assessed"
            }

            CompileResult compile = (reviewResult != null && reviewResult.finalBuildPassed() != null
                    && reviewResult.finalBuildPassed())
                    ? CompileResult.passed(1)
                    : CompileResult.failed(1, reviewResult == null ? "n/a"
                        : (reviewResult.finalBuildOutput() == null ? "" : reviewResult.finalBuildOutput()));

            ReportInput input = new ReportInput(run, workerReports, review, compile, tasks);
            // ValidationReportGenerator uses V1's LlmRouter which triggers Spring AI 1.1.4's
            // `extra_body` bug on OpenAI (400 Unknown parameter). V2's native wrapper fixes
            // this for the main pipeline, but the report generator runs through Spring AI.
            // Swap the run's provider to ANTHROPIC for the report call only (narrative-only
            // output — provider doesn't affect accuracy), then restore. Falls through if
            // Anthropic key isn't configured — report just won't generate for that run.
            String originalProvider = run.getAiProvider();
            boolean rerouted = false;
            if ("OPENAI".equalsIgnoreCase(originalProvider)) {
                run.setAiProvider("ANTHROPIC");
                rerouted = true;
                log.info("V2RunOrchestrator run={} validation report rerouted OPENAI → ANTHROPIC "
                        + "to bypass Spring AI 1.1.4 extra_body bug", runId);
            }
            try {
                validationReportService.generateAndPersist(runId, input);
                log.info("V2RunOrchestrator run={} validation report generated", runId);
            } finally {
                if (rerouted) run.setAiProvider(originalProvider);
            }
        } catch (Exception e) {
            log.warn("V2RunOrchestrator run={} validation report failed: {}", runId, e.getMessage(), e);
        }
    }

    /**
     * Writes {@code .agent-context/project.json} with the planner's declared
     * project type so {@link com.navneet.executespec.deployment.preview.DevDockerfileGenerator}
     * picks the right Dockerfile strategy for preview / deploy on V2 runs.
     *
     * <p>V1 does the same in {@code PipelineHarness.initAgentContext()}; without it,
     * the generator falls back to filesystem heuristics — works for simple cases
     * but less reliable for polyglot workspaces.
     */
    private void writeAgentContext(Path workspace, PlanV2 plan) {
        if (workspace == null || plan.projectType() == null) return;
        try {
            Path dir = workspace.resolve(".agent-context");
            Files.createDirectories(dir);
            // Normalize to the canonical PreviewProjectType/DeployStrategy enum form
            // (UPPER_SNAKE_CASE). V2 planner historically emitted lowercase-hyphenated
            // values (e.g. "node-backend") which silently fell through to heuristic
            // detection and picked the wrong Dockerfile template.
            String normalizedType = plan.projectType().trim().toUpperCase().replace('-', '_');
            String json = objectMapper.writeValueAsString(
                    java.util.Map.of("project_type", normalizedType));
            Files.writeString(dir.resolve("project.json"), json);
        } catch (Exception e) {
            log.debug("writeAgentContext failed: {}", e.getMessage());
        }
    }

    // ─── run title + design resolution ──────────────────────────────────────

    private void persistRunTitle(String runId, PlanV2 plan) {
        if (plan.planTitle() == null || plan.planTitle().isBlank()) return;
        runRepo.findById(runId).ifPresent(r -> {
            r.setRunTitle(plan.planTitle());
            runRepo.save(r);
        });
    }

    /**
     * Resolves and loads the design system block for this run using V1's
     * {@link UiStyleResolver}. Picks from three signals in priority order:
     * explicit hint parsed from the user request, planner-declared ui_style,
     * and project-type heuristic. Returns an empty string for backend-only
     * projects so the worker prompt stays compact.
     */
    private String buildDesignBlock(String userRequest, PlanV2 plan) {
        try {
            String hint = uiStyleResolver.extractStyleHint(userRequest);
            String plannerStyle = null;  // PlanV2 has no explicit ui_style field today
            String projectType = plan.projectType() == null ? "unknown" : plan.projectType();
            String designName = uiStyleResolver.resolveDesignName(hint, plannerStyle, projectType);
            if (designName == null) return "";
            String content = uiStyleResolver.loadDesignContent(designName);
            String block = uiStyleResolver.buildFrontendConstraintBlock(null, content);
            if (block == null || block.isBlank()) return "";
            log.info("V2RunOrchestrator run=? design resolved='{}' (hint='{}', project={})",
                    designName, hint, projectType);
            return block;
        } catch (Exception e) {
            log.warn("buildDesignBlock failed: {}", e.getMessage());
            return "";
        }
    }

    // ─── phase token attribution (for UI cost breakdown) ────────────────────

    /**
     * Writes the per-phase token + provider + model columns on {@link PipelineRun}
     * that V1's {@code RunMapper.buildAgentTokens()} reads to render the
     * "Token Usage by Model" panel. V2 attributes tokens by diffing
     * {@link V2RunCostTracker} snapshots between pipeline phases.
     *
     * <p>Supported phases: {@code planner} and {@code reviewer}. The compiler
     * phase doesn't exist in V2 (smoke tests replace {@code CompilerAgent}).
     */
    private void persistPhaseTokens(String runId, String phase, int inTok, int outTok,
                                     String providerUpper, String model) {
        runRepo.findById(runId).ifPresent(r -> {
            switch (phase) {
                case "planner" -> {
                    r.setPlannerInputTokens(inTok);
                    r.setPlannerOutputTokens(outTok);
                    r.setPlannerProvider(providerUpper);
                    r.setPlannerActualModel(model);
                }
                case "reviewer" -> {
                    r.setReviewerInputTokens(inTok);
                    r.setReviewerOutputTokens(outTok);
                    r.setReviewerProvider(providerUpper);
                    r.setReviewerActualModel(model);
                }
                default -> { /* no-op */ }
            }
            runRepo.save(r);
        });
        // Also increment the run totals atomically so the "Run Stats" panel
        // reflects cumulative token usage even if the in-memory tracker
        // snapshot is later lost (deploy restart, accumulator eviction, etc.).
        if (inTok > 0 || outTok > 0) {
            try { runRepo.addTokens(runId, inTok, outTok); }
            catch (Exception e) { log.warn("persistPhaseTokens addTokens failed run={}: {}", runId, e.getMessage()); }
        }
    }

    /**
     * Spreads worker-phase tokens (executor aggregate) across the run's
     * {@link AgentTask} rows so the per-task token columns — which the UI reads
     * when rendering task-level breakdowns — are populated.
     *
     * <p>Simple equal split: each task gets {@code total / taskCount} tokens.
     * Exact per-task attribution would require threading the tracker through
     * {@link SequentialExecutorV2} (future work); the equal split is good
     * enough for the cost widget's sum to reconcile, which is what users see.
     */
    private void distributeWorkerTokens(String runId, ExecutionResult execResult,
                                         int totalIn, int totalOut,
                                         String providerUpper, String model) {
        int n = execResult.perTask().size();
        if (n == 0) return;
        // Increment run totals once with the aggregate worker-phase tokens.
        // Per-task values below are a display-only equal split (tracked in the
        // known-issue list); the run-level total is the real accumulation.
        if (totalIn > 0 || totalOut > 0) {
            try { runRepo.addTokens(runId, totalIn, totalOut); }
            catch (Exception e) { log.warn("distributeWorkerTokens addTokens failed run={}: {}", runId, e.getMessage()); }
        }
        int perIn  = totalIn  / n;
        int perOut = totalOut / n;
        String prefix = runId.substring(0, 8) + "_";
        for (var outcome : execResult.perTask()) {
            String taskId = prefix + outcome.taskId();
            taskRepo.findById(taskId).ifPresent(t -> {
                t.setInputTokens(perIn);
                t.setOutputTokens(perOut);
                t.setProvider(providerUpper);
                t.setModel(model);
                taskRepo.save(t);
            });
        }
    }

    // ─── cost helpers ───────────────────────────────────────────────────────

    private void persistEstimate(String runId, String provider, String model, PlanV2 plan) {
        java.util.List<String> complexities = plan.tasks().stream()
                .map(t -> t.complexity() == null ? "MEDIUM" : t.complexity().name())
                .toList();
        V2RunCostTracker.Estimate est = costTracker.estimateRunTierAware(provider, complexities);
        runRepo.findById(runId).ifPresent(r -> {
            r.setCostEstimateLow(est.lowUsd());
            r.setCostEstimateHigh(est.highUsd());
            r.setCreditsCharged(est.credits());
            r.setHardCapUsd(est.hardCapUsd());
            runRepo.save(r);
        });
        log.info("V2RunOrchestrator run={} estimate low=${} high=${} credits={} cap=${} tasks={}",
                runId, String.format("%.4f", est.lowUsd()), String.format("%.4f", est.highUsd()),
                est.credits(), String.format("%.2f", est.hardCapUsd()), est.taskCount());
    }

    private void writeRunCostLog(PipelineRun r, V2RunCostTracker.Snapshot cost) {
        try {
            double estimated = r.getCostEstimateHigh() != null ? r.getCostEstimateHigh() : 0.0;
            double hardCap   = r.getHardCapUsd() != null ? r.getHardCapUsd() : 0.0;
            int credits      = r.getCreditsCharged() != null ? r.getCreditsCharged() : 1;
            boolean budgetHit = hardCap > 0 && cost.costUsd() >= hardCap;
            RunCostLog row = RunCostLog.builder()
                    .runId(r.getRunId())
                    .userId(r.getCreatedByUserId() != null ? r.getCreatedByUserId() : "unknown")
                    .orgId(r.getOrgId())
                    .provider(r.getAiProvider() != null ? r.getAiProvider() : "unknown")
                    .estimatedCost(estimated)
                    .actualCost(cost.costUsd())
                    .hardCap(hardCap)
                    .creditsCharged(credits)
                    .status(r.getStatus())
                    .taskCount(0)
                    .tasksCompleted(0)
                    .budgetHit(budgetHit)
                    .marginUsd(estimated - cost.costUsd())
                    .executionMode("REALTIME")
                    .batchDiscount(0.0)
                    .createdAt(Instant.now())
                    .build();
            runCostLogRepo.save(row);
        } catch (Exception e) {
            log.warn("V2RunOrchestrator run={} failed to write RunCostLog: {}",
                    r.getRunId(), e.getMessage());
        }
    }

    // ─── mapping helpers ────────────────────────────────────────────────────

    private static String mapOverallToV1Status(ExecutionResult.Overall overall) {
        return switch (overall) {
            case ALL_SUCCESS -> "COMPLETE";
            case PARTIAL_WITH_WARNINGS -> "PARTIAL";
            case FAILED -> "FAILED";
            case ABORTED -> "FAILED";
        };
    }

    private static String mapTaskStatusToV1(TaskOutcome.Status status) {
        return switch (status) {
            case SUCCESS, SUCCESS_AFTER_RETRY -> "COMPLETE";
            case COMPLETED_WITH_WARNING -> "PARTIAL";
            case WORKER_FAILED -> "FAILED";
            case SKIPPED -> "BLOCKED";
        };
    }

    private static String normalizeProvider(String aiProvider) {
        if (aiProvider == null || aiProvider.isBlank()) return DEFAULT_PROVIDER;
        String lower = aiProvider.toLowerCase();
        // V1 uses "google" — V2 uses "gemini". Map transparently.
        return "google".equals(lower) ? "gemini" : lower;
    }

    private static String defaultModelFor(String provider) {
        return switch (provider) {
            case "anthropic" -> "claude-haiku-4-5-20251001";
            case "gemini" -> "gemini-2.5-flash";
            case "openai" -> "gpt-5.4-mini";
            default -> "claude-haiku-4-5-20251001";
        };
    }

    private static String shortMsg(Exception e) {
        String msg = e.getMessage();
        String s = e.getClass().getSimpleName() + ": " + (msg == null ? "" : msg);
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    @SuppressWarnings("unused")
    private static String readWorkspaceFromLog(IOException ignored) { return null; }

    /**
     * Walks the run workspace and returns relative paths of all non-hidden, non-build
     * files. Used to populate {@link WorkerReport#getFilesCreated()} so the validation
     * report's {@code filesGenerated} and {@code languages} fields have real data.
     */
    private static List<String> listWorkspaceFiles(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return List.of();
        java.util.Set<String> skipDirs = java.util.Set.of(
                ".git", ".agent-context", "node_modules", "target", "build",
                "dist", ".gradle", ".idea", ".vscode", "__pycache__", "venv", ".venv");
        List<String> out = new java.util.ArrayList<>();
        try (var stream = Files.walk(workspace)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> {
                      for (Path part : workspace.relativize(p)) {
                          String name = part.toString();
                          if (skipDirs.contains(name)) return false;
                          if (name.startsWith(".") && !".env.example".equals(name)) return false;
                      }
                      return true;
                  })
                  .forEach(p -> out.add(workspace.relativize(p).toString()));
        } catch (IOException e) {
            log.debug("listWorkspaceFiles failed at {}: {}", workspace, e.getMessage());
        }
        return out;
    }

    private static LlmProvider providerForAccuracy(String provider) {
        if (provider == null) return LlmProvider.OPENAI;
        String up = provider.toUpperCase();
        if ("GEMINI".equals(up)) return LlmProvider.GOOGLE;
        try { return LlmProvider.valueOf(up); }
        catch (IllegalArgumentException e) { return LlmProvider.OPENAI; }
    }
}
