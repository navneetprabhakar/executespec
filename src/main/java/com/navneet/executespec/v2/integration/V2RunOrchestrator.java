package com.navneet.executespec.v2.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
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
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /** Dedicated thread pool — V1 has its own, V2 keeps a separate one so back-pressure
     *  is tracked per-harness and canary rollout tuning is independent. */
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
                .skipApproval(true)          // V2 has no gate yet — skip is effectively always true
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
        pool.submit(() -> runPipeline(runIdFinal, providerFinal, modelFinal, userRequest));
        return run;
    }

    // ─── pipeline (background thread) ───────────────────────────────────────

    private void runPipeline(String runId, String provider, String model, String userRequest) {
        Instant startedAt = Instant.now();
        Path workspace = null;
        try {
            // Use V1's workspace convention: <agent.workspace>/<runId>/
            // V1's WorkspaceFileService (Code tab, download ZIP) resolves paths via the
            // same formula, so files written here are visible through the existing UI
            // with zero adapter work. Phase 11.C formalizes this in the parity plan.
            workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
            Files.createDirectories(workspace);
            ExecutionContext rootCtx = new ExecutionContext(
                    runId, "__root__", workspace.toRealPath());
            setWorkspacePath(runId, workspace.toString());

            // ── 1. Plan ─────────────────────────────────────────────────────
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
            persistPlan(runId, plan);
            persistTasks(runId, plan);

            // ── 2. Execute ──────────────────────────────────────────────────
            updateStatus(runId, "WORKING");
            ExecutionResult execResult;
            try {
                execResult = executor.execute(ExecutionRequest.builder()
                        .plan(plan)
                        .provider(provider)
                        .model(model)
                        .runContext(rootCtx)
                        .maxIterationsPerTask(null)  // use default 40
                        .enableSmokeTests(true)
                        .build());
            } catch (Exception e) {
                log.error("V2RunOrchestrator run={} executor failed", runId, e);
                terminate(runId, "FAILED", "executor: " + shortMsg(e), startedAt);
                return;
            }
            applyExecutionResultToTasks(runId, execResult);

            // ── 3. Review ───────────────────────────────────────────────────
            updateStatus(runId, "REVIEWING");
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

            // ── 5. Terminal status mapping ─────────────────────────────────
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
        // PipelineRun doesn't have a dedicated workspacePath column — V1 derives from
        // runId via a convention. V2 stashes the path inline in reviewJson metadata for
        // now; Phase 11.C adds a workspace-resolver adapter if a column is needed.
        // For 11.A, just log; the executor keeps the path in its ExecutionContext.
        log.debug("V2RunOrchestrator run={} workspace={}", runId, path);
    }

    private void updateStatus(String runId, String newStatus) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(newStatus);
            runRepo.save(r);
        });
    }

    private void terminate(String runId, String finalStatus, String note, Instant startedAt) {
        runRepo.findById(runId).ifPresent(r -> {
            r.setStatus(finalStatus);
            r.setCompletedAt(Instant.now());
            if ("FAILED".equals(finalStatus) && r.getFailureReason() == null) {
                r.setFailureReason(note);
            }
            runRepo.save(r);
        });
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
}
