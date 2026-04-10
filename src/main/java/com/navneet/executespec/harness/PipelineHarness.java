package com.navneet.executespec.harness;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.LlmProvider;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.batch.BatchClientRouter;
import com.navneet.executespec.batch.BatchRequest;
import com.navneet.executespec.batch.BatchRequestBuilder;
import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agent.CostEstimator;
import com.navneet.executespec.agent.RunCostEstimate;
import com.navneet.executespec.agent.ResolvedKeys;
import com.navneet.executespec.agent.PlannerAgent;
import com.navneet.executespec.agent.RunKeyStore;
import com.navneet.executespec.agent.model.TaskPlan;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.billing.cost.RunCostLog;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.recovery.RecoveryProperties;
import com.navneet.executespec.recovery.RunRecoveryService;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.compiler.CompileResult;
import com.navneet.executespec.testgen.event.TestExecutionCompletedEvent;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.testgen.model.TestResult;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.tool.FileLockTool;
import com.navneet.executespec.tool.FileSystemTool;
import com.navneet.executespec.tool.GitTool;
import io.micrometer.core.instrument.Counter;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import static com.navneet.executespec.config.CustomMetricsConfig.*;
import com.navneet.executespec.config.CustomMetricsConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineHarness {

    /**
     * Task IDs handled by system agents (not WorkerAgent).
     * Legacy plans in the DB may still carry these tasks; filtering them here prevents
     * the worker pool from processing them.
     */
    private static final String OPENAPI_TASK_ID = "generate_openapi_spec";

    /** Stable identifier for this JVM instance — used for instance tracking in pipeline_run. */
    private static final String INSTANCE_ID = resolveInstanceId();

    private static String resolveInstanceId() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName()
                    + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "unknown-" + ProcessHandle.current().pid();
        }
    }

    private final PlannerAgent plannerAgent;
    private final WorkerPool workerPool;
    private final CostEstimator costEstimator;
    private final ApprovalGate approvalGate;
    private final GitMergeService mergeService;
    private final StateStore stateStore;
    private final FileLockTool lockTool;
    private final FileSystemTool fsTool;
    private final GitTool gitTool;
    private final GitHubRepoService gitHubRepoService;
    private final BitbucketRepoService bitbucketRepoService;
    private final WorkspaceInitService workspaceInitService;
    private final ObjectMapper mapper;
    private final AgentProperties props;
    private final SseBroadcaster broadcaster;

    // ── Tier 5: Recovery service + event publisher ────────────────────────────
    private final RunRecoveryService recoveryService;
    private final ApplicationEventPublisher eventPublisher;

    // ── Tier 8: Compile agent + Preview agent ─────────────────────────────────
    private final com.navneet.executespec.compiler.CompilerAgent compilerAgent;

    // ── Sequential execution with validation + model routing ──────────────────
    private final SequentialTaskExecutor sequentialTaskExecutor;
    private final RunBudgetTracker runBudgetTracker;
    private final RunCostBudgetService runCostBudgetService;

    // ── Reviewer V2: agentic two-phase (inspect → fix) reviewer ──────────────
    private final com.navneet.executespec.reviewer.ReviewerAgentV2 reviewerAgentV2;

    // ── API key-aware provider resolution ────────────────────────────────────
    private final com.navneet.executespec.router.AvailableProviderResolver availableProviderResolver;

    // ── Multi-tenant API key routing ──────────────────────────────────────────
    private final ApiKeyResolver apiKeyResolver;
    private final RunKeyStore runKeyStore;

    // ── Validation report ──────────────────────────────────────────────────
    private final com.navneet.executespec.report.ValidationReportService validationReportService;

    // ── Auto-retry infrastructure ──────────────────────────────────────────
    private final com.navneet.executespec.auth.repository.TierConfigRepository tierConfigRepo;
    private final com.navneet.executespec.auth.repository.OrgRepository orgRepo;

    // ── Tier enforcement (complexity gate) ────────────────────────────────────
    private final com.navneet.executespec.auth.service.TierEnforcementService tierEnforcementService;

    // ── Cost audit log ────────────────────────────────────────────────────────
    private final RunCostLogRepository runCostLogRepository;

    // ── Batch execution ──────────────────────────────────────────────────────
    private final BatchClientRouter batchClientRouter;
    private final BatchRequestBuilder batchRequestBuilder;

    // ── JIRA/Confluence context extraction ─────────────────────────────────
    private final com.navneet.executespec.integration.context.ReferenceExtractorService referenceExtractorService;

    // ── GCS workspace restore (on-demand after VM restart) ────────────────
    private final com.navneet.executespec.deployment.WorkspaceRestoreService restoreService;

    // ── Distributed tracing (Micrometer Observation API) ────────────────────
    private final ObservationRegistry observationRegistry;

    // ── Micrometer metrics registry ──────────────────────────────────────────
    private final MeterRegistry meterRegistry;

    // ── Preview service (auto-start for test execution) ─────────────────────
    private final com.navneet.executespec.deployment.preview.PreviewService previewService;
    private final com.navneet.executespec.auth.repository.AppUserRepository appUserRepository;

    // ── LLM routing ────────────────────────────────────────────────────────────
    private final com.navneet.executespec.agent.LlmRouter llmRouter;

    // ── Project repository (for aiProvider copy at submission) ────────────────
    private final com.navneet.executespec.auth.repository.ProjectRepository projectRepository;

    // ── UI style resolution ────────────────────────────────────────────────────
    private final UiStyleResolver uiStyleResolver;

    // ── Language validation for existing repos ─────────────────────────────────
    private final LanguageValidator languageValidator;

    // ── Model resolution (for label fields on provider change) ──────────────
    private final ModelResolver modelResolver;

    // ── Single-shot fast path (Phase 1 simplification) ──────────────────────
    private final com.navneet.executespec.agent.RequestTriageService requestTriageService;
    private final SingleShotExecutor singleShotExecutor;
    private final com.navneet.executespec.agent.TriageEscalationDetector triageEscalationDetector;

    // ── Test generation pipeline ─────────────────────────────────────────────
    private final com.navneet.executespec.testgen.TestGenOrchestrator testGenOrchestrator;
    private final com.navneet.executespec.testgen.TestPreviewCoordinator testPreviewCoordinator;
    private final com.navneet.executespec.testgen.repository.TestPlanRepository testPlanRepository;
    private final com.navneet.executespec.testgen.repository.TestResultRepository testResultRepository;

    /**
     * Tracks in-flight test execution completions so the validation report can wait for test results.
     * Key = runId, completed when TestExecutionCompletedEvent fires (or test pipeline fails/skips).
     */
    private final Cache<String, CompletableFuture<TestExecutionSummary>> testCompletionFutures =
            Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofHours(24))
                    .maximumSize(10_000)
                    .build();

    /**
     * DB-backed tracker for background validation lifecycle.
     * Replaces the in-memory {@code ConcurrentHashSet} so app restarts don't lose state.
     */
    private final BackgroundValidationService backgroundValidationService;

    /**
     * Tracks runs with an active pipeline thread (executeRunAsync or runResumeAsync).
     * Used by OrphanedRunWatchdog and StuckRunMonitor to avoid triggering recovery
     * on runs that already have a live execution thread.
     */
    private final Set<String> activeRunThreads = ConcurrentHashMap.newKeySet();

    // ── Async infrastructure ──────────────────────────────────────────────────

    /**
     * Redis-backed gate store — replaces in-memory CompletableFuture maps so that
     * plan/merge approval REST calls work regardless of which MIG instance receives them.
     */
    private final GateStore gateStore;
    private final ModelRegistry modelRegistry;

    /** Per-provider isolated run pools with priority queuing. */
    private final ProviderRunPoolManager providerRunPoolManager;

    /** Checks combined circuit-breaker + status-page health across the failover chain. */
    private final com.navneet.executespec.router.ProviderStatusService providerStatusService;

    /** Limits simultaneous active pipeline runs. */
    private Semaphore runSlots;
    /** Thread pool for async pipeline execution (unbounded — threads block on semaphore). */
    private ExecutorService executor;

    /**
     * Guards against double-handling of a run by both GateFallback and StartupRunResumer.
     * Key = runId; presence means a thread is already processing the resume.
     */
    private final ConcurrentHashMap<String, Boolean> activeResumeGuard = new ConcurrentHashMap<>();

    /**
     * Tracks running pipeline threads so we can interrupt them on cancel.
     * Key = runId, value = the thread executing the pipeline.
     */
    private final ConcurrentHashMap<String, Thread> pipelineThreads = new ConcurrentHashMap<>();

    /** Single scanner for CLI stdin path — avoids buffering issues. */
    private final Scanner stdin = new Scanner(System.in);

    @PostConstruct
    void init() {
        int maxRuns = props.getConcurrency() != null ? props.getConcurrency().getMaxRuns() : 3;
        runSlots = new Semaphore(maxRuns);
        executor = new java.util.concurrent.ThreadPoolExecutor(
                maxRuns, maxRuns * 3,
                60L, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(maxRuns * 2),
                r -> {
                    Thread t = new Thread(r, "pipeline-run");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        );
        log.info("PipelineHarness ready — max concurrent runs: {}, per-provider pools: {}",
                maxRuns, providerRunPoolManager.totalActiveRuns());
    }

    // ── Auto-retry ───────────────────────────────────────────────────────────

    private boolean autoRetryRunIfEligible(PipelineRun run) {
        // Look up tier
        String tier = "FREE";
        if (run.getOrgId() != null) {
            var org = orgRepo.findById(run.getOrgId());
            if (org.isPresent()) tier = org.get().getTier();
        }

        int maxRetries = tierConfigRepo.findById(tier)
            .map(com.navneet.executespec.auth.entity.TierConfig::getMaxRunAutoRetries)
            .orElse(0);

        if (run.getRunRetryCount() >= maxRetries) {
            log.info("Run {} — auto-retry exhausted ({}/{})", run.getRunId(), run.getRunRetryCount(), maxRetries);
            return false;
        }

        // Don't retry user-rejected runs
        if (run.getFailureReason() != null &&
            (run.getFailureReason().contains("Rejected") || run.getFailureReason().contains("declined"))) {
            return false;
        }

        log.info("Run {} — auto-retrying ({}/{})", run.getRunId(), run.getRunRetryCount() + 1, maxRetries);

        // Create a new run as retry
        PipelineRun retryRun = stateStore.createRun(run.getUserRequest(), run.getSourceType(),
            run.getRepoUrl(), run.getRepoMode(),
            run.getPlannerModel(), run.getWorkerModel(), run.getReviewerModel());
        retryRun.setRunRetryCount(run.getRunRetryCount() + 1);
        retryRun.setCreatedByUserId(run.getCreatedByUserId());
        retryRun.setOrgId(run.getOrgId());
        retryRun.setProjectId(run.getProjectId());
        retryRun.setSourceRunId(run.getRunId());
        if (run.isSkipApproval()) retryRun.setSkipApproval(true);
        if (run.isAutoMerge()) retryRun.setAutoMerge(true);
        if (run.getGithubPat() != null) retryRun.setGithubPat(run.getGithubPat());
        if (run.getGithubOwner() != null) retryRun.setGithubOwner(run.getGithubOwner());
        retryRun.setExecutionMode(run.getExecutionMode());
        retryRun.setAiProvider(run.getAiProvider());
        stateStore.save(retryRun);

        // Update failed run with retry link
        run.setFailureReason(run.getFailureReason() + " — auto-retrying as " + retryRun.getRunId());
        stateStore.save(run);

        broadcaster.emit(run.getRunId(), RunEvent.log(run.getRunId(), "harness",
            "Auto-retrying as run " + retryRun.getRunId() + " (attempt " + retryRun.getRunRetryCount() + "/" + maxRetries + ")"));

        // Submit the retry run
        String retryRunId = retryRun.getRunId();
        executor.submit(() -> {
            boolean acquired = false;
            try {
                runSlots.acquire();
                acquired = true;
                executeRunAsync(retryRunId, run.getUserRequest());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateStore.markFailed(retryRunId, "Interrupted during auto-retry");
            } finally {
                if (acquired) runSlots.release();
            }
        });

        return true;
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
            TaskPlan plan = plannerAgent.plan(userRequest, run.getRunId(), run.getPlannerModel());
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
            WorkerPool.WorkerPoolResult poolResult = workerPool.runAll(tasks, run.getRunId(),
                props.getRoles().getWorker());
            List<WorkerReport> reports = poolResult.reports();

            stateStore.markReviewing(run.getRunId());
            run = stateStore.findRun(run.getRunId()).orElse(run);
            var providers = availableProviderResolver.resolve(run.getRunId());
            ReviewReportV2 review = reviewerAgentV2.review(run, tasks, run.getUserRequest(),
                    CompileResult.skip(), providers);
            stateStore.saveReviewV2(run.getRunId(), review);

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
                                  String repoUrl, String repoMode,
                                  String githubPat, String githubOwner) {
        return submitRun(userRequest, source, repoUrl, repoMode, githubPat, githubOwner,
                         false, false, null, null, null, null, null, "PRODUCTION");
    }

    /**
     * Overloaded submit that applies skipApproval, autoMerge, modelName, AND ownership
     * fields (createdByUserId, orgId, projectId) BEFORE the async thread starts,
     * avoiding the race condition where the async pipeline thread re-loads and re-saves
     * the run entity before the controller can stamp these fields.
     */
    public PipelineRun submitRun(String userRequest, String source,
                                  String repoUrl, String repoMode,
                                  String githubPat, String githubOwner,
                                  boolean skipApproval, boolean autoMerge,
                                  String modelName, String aiProvider,
                                  String createdByUserId, String orgId, String projectId,
                                  String executionMode) {
        PipelineRun run = stateStore.createRun(userRequest, source, repoUrl, repoMode,
            props.getRoles().getPlanner(),
            props.getRoles().getWorker(),
            props.getRoles().getReviewer());

        // Apply skipApproval and model overrides BEFORE launching async thread
        boolean needsSave = false;
        if (skipApproval) {
            run.setSkipApproval(true);
            needsSave = true;
        }
        if (autoMerge) {
            run.setAutoMerge(true);
            needsSave = true;
        }
        if (modelName != null && !modelName.isBlank()) {
            run.setPlannerModel(modelName);
            run.setWorkerModel(modelName);
            run.setReviewerModel(modelName);
            needsSave = true;
        }

        // Capture submitting user's GitHub credentials onto the run for later push/clone
        if (githubPat != null && !githubPat.isBlank()) {
            run.setGithubPat(githubPat);
            needsSave = true;
        }
        if (githubOwner != null && !githubOwner.isBlank()) {
            run.setGithubOwner(githubOwner);
            needsSave = true;
        }

        // Capture submitting user's Bitbucket credentials onto the run for later push
        if (createdByUserId != null) {
            var bbUser = appUserRepository.findById(createdByUserId).orElse(null);
            if (bbUser != null && bbUser.getBitbucketUsername() != null && bbUser.getBitbucketAppPassword() != null) {
                run.setBitbucketUsername(bbUser.getBitbucketUsername());
                run.setBitbucketAppPassword(bbUser.getBitbucketAppPassword());
                run.setBitbucketWorkspace(bbUser.getBitbucketWorkspace());
                run.setBitbucketRepoSlug(bbUser.getBitbucketRepoSlug());
                run.setScmProvider("BITBUCKET");
                needsSave = true;
            }
        }

        // Stamp ownership BEFORE async thread starts (critical for visibility queries)
        if (createdByUserId != null) {
            run.setCreatedByUserId(createdByUserId);
            needsSave = true;
        }
        if (orgId != null) {
            run.setOrgId(orgId);
            needsSave = true;
        }
        if (projectId != null && !projectId.isBlank()) {
            run.setProjectId(projectId);
            // Copy project's AI provider to the run (frozen at submission time)
            // Also resolve model labels so RunDetail shows the correct provider's models
            projectRepository.findById(projectId).ifPresent(project -> {
                run.setAiProvider(project.getAiProvider().name());
                LlmProvider prov = LlmProvider.valueOf(project.getAiProvider().name());
                run.setPlannerModel(modelResolver.resolve("PlannerAgent", prov).getModel());
                run.setWorkerModel(modelResolver.resolve("WorkerAgent", prov).getModel());
                run.setReviewerModel(modelResolver.resolve("ReviewerAgentV2", prov).getModel());
            });
            needsSave = true;
        }

        // Explicit per-run AI provider override (takes precedence over project default)
        if (aiProvider != null && !aiProvider.isBlank()) {
            run.setAiProvider(aiProvider.toUpperCase());
            // Update model label fields to match the selected provider
            LlmProvider prov = LlmProvider.valueOf(aiProvider.toUpperCase());
            run.setPlannerModel(modelResolver.resolve("PlannerAgent", prov).getModel());
            run.setWorkerModel(modelResolver.resolve("WorkerAgent", prov).getModel());
            run.setReviewerModel(modelResolver.resolve("ReviewerAgentV2", prov).getModel());
            needsSave = true;
        }

        // Apply execution mode — PROTOTYPE forces skipApproval + autoMerge + FAST-tier planner
        if (executionMode != null && !executionMode.isBlank()) {
            run.setExecutionMode(executionMode);
            needsSave = true;
        }
        if ("PROTOTYPE".equals(executionMode)) {
            run.setSkipApproval(true);
            run.setAutoMerge(true);
            // PROTOTYPE mode: planner model resolved from matrix at runtime (no override needed)
            needsSave = true;
        }

        if (needsSave) {
            stateStore.save(run);
        }

        String runId = run.getRunId();

        // Resolve user tier for priority queuing (best-effort; FREE is safe default)
        String runUserTier = "FREE";
        try {
            runUserTier = tierEnforcementService.resolveUserTier(run.getCreatedByUserId());
        } catch (Exception e) {
            log.debug("Could not resolve tier for run {} — defaulting to FREE", runId);
        }
        final String submitUserTier = runUserTier;

        // Provider outage guard — if all providers in the failover chain are unhealthy,
        // queue the run rather than immediately failing it. StuckRunMonitor retries every 2 min.
        {
            var availableProviders = availableProviderResolver.resolve(runId);
            LlmProvider primaryProvider = run.getAiProviderEnum() != null
                    ? run.getAiProviderEnum() : LlmProvider.OPENAI;
            if (!providerStatusService.isAnyProviderHealthy(primaryProvider, availableProviders)) {
                run.setStatus("QUEUED");
                run.setQueuePosition(0);
                run.setFailureReason("LLM providers unavailable — run will retry automatically when providers recover");
                stateStore.save(run);
                providerStatusService.recordQueuedDueToOutage();
                broadcaster.emit(runId, RunEvent.queued(runId, 0));
                log.warn("[PipelineHarness] All providers unhealthy for run {} — queued for outage recovery", runId);
                return run;
            }
        }

        if (!runSlots.tryAcquire()) {
            // No global slot available — queue in provider pool (which will acquire slot when ready)
            Metrics.QUEUED_RUNS.incrementAndGet();
            run.setStatus("QUEUED");
            run.setQueuePosition(countQueued() + 1);
            stateStore.save(run);
            broadcaster.emit(runId, RunEvent.queued(runId, run.getQueuePosition()));
            log.info("Run {} queued at position {} (provider: {})", runId, run.getQueuePosition(), run.getAiProvider());

            final PipelineRun queuedRun = run;
            executor.submit(() -> {
                pipelineThreads.put(runId, Thread.currentThread());
                boolean acquired = false;
                try {
                    runSlots.acquire();
                    acquired = true;
                    Metrics.QUEUED_RUNS.decrementAndGet();
                    PipelineRun r = stateStore.findRun(runId).orElse(queuedRun);
                    r.setStatus("PLANNING");
                    r.setQueuePosition(0);
                    stateStore.save(r);
                    // Route through provider pool after acquiring global slot
                    dispatchToProviderPool(r.getAiProvider(), runId, submitUserTier, () -> {
                        try {
                            executeRunAsync(runId, userRequest);
                        } finally {
                            pipelineThreads.remove(runId);
                            runSlots.release();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stateStore.markFailed(runId, "Interrupted while waiting for run slot");
                } catch (ProviderRunPool.ProviderQueueFullException e) {
                    runSlots.release();
                    stateStore.markFailed(runId, e.getMessage());
                    log.warn("Run {} rejected — provider pool full: {}", runId, e.getMessage());
                } finally {
                    pipelineThreads.remove(runId);
                    if (acquired && !Thread.currentThread().isInterrupted()) {
                        // Only release here on exceptional path; normal path releases inside the pool task
                    }
                }
            });

        } else {
            // Global slot available — route directly to provider pool
            try {
                final PipelineRun frozenRun = run;
                dispatchToProviderPool(run.getAiProvider(), runId, submitUserTier, () -> {
                    pipelineThreads.put(runId, Thread.currentThread());
                    try {
                        executeRunAsync(runId, userRequest);
                    } finally {
                        pipelineThreads.remove(runId);
                        runSlots.release();
                    }
                });
            } catch (ProviderRunPool.ProviderQueueFullException e) {
                runSlots.release();
                stateStore.markFailed(runId, e.getMessage());
                log.warn("Run {} rejected — provider pool full at submit: {}", runId, e.getMessage());
            }
        }

        return run;
    }

    /**
     * Routes a task into the appropriate provider pool.
     * The task is responsible for releasing the global run slot when done.
     */
    private void dispatchToProviderPool(String providerName, String runId, String userTier, Runnable task) {
        ProviderRunPool pool = providerRunPoolManager.poolFor(providerName);
        pool.submit(runId, userTier, Instant.now(), task);
        log.debug("Run {} dispatched to {} pool (queued={})",
                runId, providerName, pool.queueDepth());
    }

    /**
     * Resolves the plan-approval gate for a REST-submitted run.
     *
     * <p>Signals via Redis so the pipeline thread's polling loop picks it up within ~500 ms,
     * regardless of which MIG instance receives this HTTP call.
     *
     * @return false if the run is not in AWAITING_APPROVAL (unknown runId or wrong state → 404)
     */
    public boolean resolvePlanGate(String runId, boolean approved, String comment) {
        Optional<PipelineRun> optRun = stateStore.findRun(runId);
        if (optRun.isEmpty()) return false;
        if (!"AWAITING_APPROVAL".equals(optRun.get().getStatus())) return false;

        // Signal via Redis — pipeline thread on ANY instance picks it up within ~500 ms
        gateStore.setPlanGate(runId, approved);

        // Clear currentGate immediately so the UI banner disappears on next poll.
        // (markApproved() also clears it but runs asynchronously on the pipeline thread,
        //  causing a race where the frontend refetch still sees the old gate value.)
        clearGateSync(runId);
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("SSE emit failed on plan gate resolve for run {} — client likely disconnected", runId);
        }
        return true;
    }

    /**
     * Resolves the context-clarification gate for a REST-submitted run.
     *
     * @param response the user's clarification text (e.g., a JIRA key or Confluence URL)
     * @return false if the run is not in AWAITING_CLARIFICATION state
     */
    public boolean resolveClarificationGate(String runId, String response) {
        Optional<PipelineRun> optRun = stateStore.findRun(runId);
        if (optRun.isEmpty()) return false;
        if (!"AWAITING_CLARIFICATION".equals(optRun.get().getStatus())) return false;

        gateStore.setClarificationGate(runId, response);
        clearGateSync(runId);
        return true;
    }

    /**
     * Resolves the merge-approval gate for a REST-submitted run.
     *
     * <p>Signals via Redis so the pipeline thread's polling loop picks it up within ~500 ms,
     * regardless of which MIG instance receives this HTTP call.
     *
     * <p>Note: {@link #performMergeOnRestart} is preserved for the crash-recovery path
     * (triggered by {@code StuckRunMonitor} when a run is stuck in AWAITING_MERGE after an
     * instance replacement). It is no longer called from here — the Redis signal is
     * sufficient for the normal and cross-instance cases.
     *
     * @return false only if the runId is unknown or not in AWAITING_MERGE state (→ 404)
     */
    public boolean resolveMergeGate(String runId, boolean approved, String comment) {
        Optional<PipelineRun> optRun = stateStore.findRun(runId);
        if (optRun.isEmpty()) return false;
        if (!"AWAITING_MERGE".equals(optRun.get().getStatus())) return false;

        MergeDecision decision = approved ? MergeDecision.MERGE : MergeDecision.DECLINE;
        // Signal via Redis — pipeline thread on ANY instance picks it up within ~500 ms
        gateStore.setMergeGate(runId, decision);

        // Clear currentGate immediately — same race-condition fix as resolvePlanGate.
        clearGateSync(runId);
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("SSE emit failed on merge gate resolve for run {} — client likely disconnected", runId);
        }

        // Gate-loss fallback: the normal pipeline thread resolves via in-memory future
        // almost instantly. Wait one poll cycle (500ms) to let it consume the key first.
        // If the key is still in Redis after that, the pipeline thread is dead — handle directly.
        // The activeResumeGuard prevents double-handling with StartupRunResumer.
        executor.submit(() -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            MergeDecision orphaned = gateStore.consumeMergeGateIfPending(runId);
            if (orphaned != null) {
                PipelineRun run = stateStore.findRun(runId).orElse(null);
                if (run != null && "AWAITING_MERGE".equals(run.getStatus())) {
                    if (activeResumeGuard.putIfAbsent(runId, Boolean.TRUE) != null) {
                        log.info("[GateFallback] Run {} already being resumed — skipping fallback", runId);
                        return;
                    }
                    log.info("[GateFallback] No pipeline thread for run {} — handling {} directly", runId, orphaned);
                    try {
                        if (orphaned == MergeDecision.MERGE) {
                            doReviewAndMerge(run, false, false);
                        } else {
                            // SECURITY: DECLINE must NOT merge. Mark run as failed.
                            log.info("[GateFallback] User declined merge for run {}", runId);
                            run.setStatus("FAILED");
                            run.setFailureReason("Merge declined by user");
                            stateStore.saveRun(run);
                            broadcaster.emit(runId, RunEvent.runStatus(runId, "FAILED"));
                        }
                    } finally {
                        activeResumeGuard.remove(runId);
                    }
                }
            }
        });

        return true;
    }

    /**
     * Performs a full merge for a run whose in-memory gate was lost (server restart).
     * Reconstructs everything it needs from the database: review JSON, worker reports,
     * and the run itself.  Mirrors the post-{@code mergeGate.get()} logic in
     * {@link #executeRunAsync} exactly, including cost save, GitHub push, and
     * post-merge event publishing.
     */
    /**
     * Backwards-compat: handles runs stuck in AWAITING_MERGE from before the gate removal.
     * Delegates to doReviewAndMerge which now does: merge → review → report → complete.
     */
    private void performMergeOnRestart(String runId, boolean approved, String comment) {
        PipelineRun run = stateStore.findRun(runId).orElse(null);
        if (run == null) {
            log.warn("[Gate] performMergeOnRestart: run {} not found in DB", runId);
            return;
        }

        clearGateSync(runId);
        try {
            broadcaster.emit(runId, RunEvent.gateResolved(runId, approved, comment));
        } catch (Exception e) {
            log.debug("[Gate] SSE emit failed on restart-merge gate resolve for run {}", runId);
        }

        if (!approved) {
            String declineReason = "Merge declined by user";
            stateStore.markPartial(runId, declineReason);
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            broadcaster.complete(runId);
            return;
        }

        // Review may already exist — skip review if so
        boolean hasReview = run.getReviewJson() != null && !run.getReviewJson().isBlank();
        doReviewAndMerge(run, false, !hasReview);
    }

    /** Cancels a run. Returns false if run is already terminal. */
    public boolean cancelRun(String runId) {
        // Signal open gates via Redis so the pipeline thread's polling loop unblocks.
        // The pipeline thread will read the rejection value and bail out normally.
        gateStore.setPlanGate(runId, false);
        gateStore.setMergeGate(runId, MergeDecision.DECLINE);

        // Cancel in-flight workers (same as pauseRun)
        workerPool.cancelAllTasksForRun(runId);

        boolean cancelled = stateStore.cancelRun(runId);
        if (cancelled) {
            // Cancel provider-side batch job if one is active
            PipelineRun run = stateStore.findRun(runId).orElse(null);
            if (run != null && run.getBatchId() != null && run.getBatchProvider() != null) {
                try {
                    LlmProvider provider = LlmProvider.valueOf(run.getBatchProvider());
                    batchClientRouter.cancelBatch(run.getBatchId(), runId, provider);
                    log.info("[Cancel] Cancelled provider batch {} for run {}", run.getBatchId(), runId);
                } catch (Exception e) {
                    log.warn("[Cancel] Failed to cancel provider batch for run {}: {}", runId, e.getMessage());
                }
            }

            // Interrupt the pipeline thread so it stops between phases
            Thread pipelineThread = pipelineThreads.get(runId);
            if (pipelineThread != null && pipelineThread.isAlive()) {
                log.info("[Cancel] Interrupting pipeline thread for run {}", runId);
                pipelineThread.interrupt();
            }

            broadcaster.emit(runId, RunEvent.complete(runId, "CANCELLED"));
            broadcaster.complete(runId);
        }
        return cancelled;
    }

    /**
     * Pauses a run: signals open gates via Redis, cancels in-flight workers,
     * and marks the run PAUSED. The user can then edit the goal and restart.
     */
    public boolean pauseRun(String runId) {
        gateStore.setPlanGate(runId, false);
        gateStore.setMergeGate(runId, MergeDecision.DECLINE);
        workerPool.cancelAllTasksForRun(runId);

        boolean paused = stateStore.pauseRun(runId);
        if (paused) {
            broadcaster.emit(runId, RunEvent.runStatus(runId, "PAUSED"));
            broadcaster.emit(runId, RunEvent.complete(runId, "PAUSED"));
            broadcaster.complete(runId);
        }
        return paused;
    }

    /**
     * Creates a fresh run with an edited goal, linking it back to the paused source run.
     * Carries forward credentials, ownership, org, project, and model settings.
     */
    public PipelineRun restartWithNewGoal(PipelineRun pausedRun, String newGoal) {
        log.info("Restarting paused run {} with new goal", pausedRun.getRunId());

        // Pass ownership INTO submitRun so it's stamped BEFORE the async thread starts
        PipelineRun newRun = submitRun(
            newGoal,
            pausedRun.getSourceType() != null ? pausedRun.getSourceType() : "MANUAL",
            pausedRun.getRepoUrl(),
            pausedRun.getRepoMode(),
            pausedRun.getGithubPat(),
            pausedRun.getGithubOwner(),
            pausedRun.isSkipApproval(),
            false,
            pausedRun.getPlannerModel(),
            pausedRun.getAiProvider(),
            pausedRun.getCreatedByUserId(),
            pausedRun.getOrgId(),
            pausedRun.getProjectId(),
            pausedRun.getExecutionMode()
        );

        // Carry forward additional context (not part of submitRun params)
        newRun.setReviewerModel(pausedRun.getReviewerModel());
        newRun.setSourceRunId(pausedRun.getRunId());
        stateStore.saveRun(newRun);

        // Update paused run's failure reason with forward link
        pausedRun.setFailureReason("Paused by user — restarted as " + newRun.getRunId());
        stateStore.saveRun(pausedRun);

        broadcaster.emit(pausedRun.getRunId(), RunEvent.log(
            pausedRun.getRunId(), "harness",
            "🔄 Restarted with edited goal — new run: " + newRun.getRunId()));

        return newRun;
    }

    /**
     * Returns true if the given run has an active pipeline execution thread
     * (either executeRunAsync or runResumeAsync). Used by recovery components
     * (OrphanedRunWatchdog, StuckRunMonitor) to avoid spawning duplicate threads.
     */
    public boolean isRunActive(String runId) {
        return activeRunThreads.contains(runId);
    }

    /**
     * Resumes a PAUSED run — continues the same run from the worker phase,
     * preserving completed tasks and re-running incomplete ones.
     * Unlike restartWithNewGoal(), this does NOT create a new run.
     */
    public boolean resumeRun(String runId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!"PAUSED".equals(run.getStatus())) {
            return false;
        }

        // Acquire semaphore slot to respect concurrency limits
        if (!runSlots.tryAcquire()) {
            log.warn("[Resume] No available run slots for run {}", runId);
            return false;
        }

        boolean resumed = stateStore.resumeRun(runId);
        if (!resumed) {
            runSlots.release();
            return false;
        }

        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "▶️ Resuming paused run — re-running incomplete tasks"));

        final PipelineRun frozenRun = stateStore.findRun(runId).orElse(run);
        String resumeUserTier = "FREE";
        try {
            resumeUserTier = tierEnforcementService.resolveUserTier(frozenRun.getCreatedByUserId());
        } catch (Exception e) {
            log.debug("Could not resolve tier for resume of run {} — defaulting to FREE", runId);
        }
        final String finalResumeTier = resumeUserTier;
        try {
            dispatchToProviderPool(frozenRun.getAiProvider(), runId, finalResumeTier, () -> {
                try {
                    runResumeAsync(frozenRun);
                } finally {
                    runSlots.release();
                }
            });
        } catch (ProviderRunPool.ProviderQueueFullException e) {
            runSlots.release();
            log.warn("Run {} resume rejected — provider pool full: {}", runId, e.getMessage());
            return false;
        }
        return true;
    }

    // ── Startup auto-resume ───────────────────────────────────────────────────

    /**
     * Called by {@link StartupRunResumer} for each non-terminal run discovered after restart.
     * Routes to the correct resume path based on run status. Acquires a semaphore slot
     * (blocking if at capacity) to respect concurrency limits.
     */
    public void resumeOnStartup(PipelineRun run) {
        String runId = run.getRunId();
        String status = run.getStatus();

        // Resolve API keys for this run's owner
        ResolvedKeys keys = apiKeyResolver.resolve(run.getCreatedByUserId());
        runKeyStore.put(runId, keys);

        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "♻️ Resuming after server restart (was " + status + ")"));

        executor.submit(() -> {
            // Guard: if the gate fallback thread is already handling this run, skip.
            if (activeResumeGuard.putIfAbsent(runId, Boolean.TRUE) != null) {
                log.info("[resumeOnStartup] Run {} already being handled by gate fallback — skipping", runId);
                return;
            }

            Observation resumeObs = Observation.createNotStarted("executespec.run.resume_on_startup", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .lowCardinalityKeyValue("resume.from_status", status)
                .start();
            boolean acquired = false;
            try {
                runSlots.acquire();
                acquired = true;

                // Re-fetch — status may have changed while waiting for a slot
                PipelineRun fresh = stateStore.findRun(runId).orElse(run);
                String freshStatus = fresh.getStatus();

                switch (freshStatus) {
                    case "PLANNING" -> {
                        // Re-run full pipeline from scratch
                        executeRunAsync(runId, fresh.getUserRequest());
                    }
                    case "AWAITING_APPROVAL" -> {
                        // Plan exists, re-enter gate polling
                        resumeFromPlanGate(fresh);
                    }
                    case "WORKING", "STUCK" -> {
                        if ("STUCK".equals(freshStatus)) {
                            fresh.setStatus("WORKING");
                            stateStore.saveRun(fresh);
                            broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
                        }
                        runResumeAsync(fresh);
                    }
                    case "REVIEWING" -> {
                        // All tasks done, re-run reviewer → merge gate
                        doReviewAndMerge(fresh, true, true);
                    }
                    case "AWAITING_MERGE" -> {
                        // Review done, re-enter merge gate (skip re-review)
                        doReviewAndMerge(fresh, true, false);
                    }
                    default -> {
                        log.info("Run {} now in terminal status {} — skipping resume", runId, freshStatus);
                        return; // don't hold slot
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                resumeObs.error(e);
                log.warn("Interrupted while waiting for slot to resume run {}", runId);
            } catch (Exception e) {
                resumeObs.error(e);
                log.error("Error resuming run {} on startup: {}", runId, e.getMessage(), e);
                stateStore.markFailed(runId, "Startup resume failed: " + e.getMessage());
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                broadcaster.complete(runId);
            } finally {
                resumeObs.stop();
                activeResumeGuard.remove(runId);
                if (acquired) runSlots.release();
            }
        });
    }

    /**
     * Re-enters the plan-approval gate for a run in AWAITING_APPROVAL.
     * The plan already exists in DB; we just need to re-poll the gate (decision may be in Redis).
     */
    private void resumeFromPlanGate(PipelineRun run) {
        String runId = run.getRunId();

        broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_APPROVAL"));
        String planGateId = stateStore.recordGateOpened(runId, "PLAN_APPROVAL");
        broadcaster.emit(runId, RunEvent.gateOpened(runId, "PLAN_APPROVAL"));

        // Re-check skipApproval flag (integration runs)
        PipelineRun current = stateStore.findRun(runId).orElse(run);
        boolean planApproved;

        if (current.isSkipApproval()) {
            planApproved = true;
            stateStore.recordGateResolved(planGateId, true);
            broadcaster.emit(runId, RunEvent.gateResolved(runId, true, "Auto-approved (integration run)"));
            log.info("Plan gate auto-approved for integration run {} on startup resume", runId);
        } else {
            // Release slot while waiting at gate — don't block other runs
            log.info("[resumeFromPlanGate] Run {} entering plan-approval gate — releasing compute slot", runId);
            runSlots.release();
            try {
                planApproved = gateStore.awaitPlanGate(runId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                try { runSlots.acquire(); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            stateStore.recordGateResolved(planGateId, planApproved);
        }

        if (!planApproved) {
            PipelineRun checkPaused = stateStore.findRun(runId).orElse(null);
            if (checkPaused != null && "PAUSED".equals(checkPaused.getStatus())) {
                log.info("Run {} was paused during plan gate — exiting cleanly", runId);
                return;
            }
            stateStore.markRejected(runId, "Rejected at plan approval gate");
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Rejected at plan approval gate"));
            broadcaster.complete(runId);
            return;
        }

        stateStore.markApproved(runId);
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

        // Continue with task execution
        runResumeAsync(current);
    }

    /**
     * Async continuation of resumeRun: workers → compiler → reviewer → merge gate.
     * Closely follows runRetryAsync but uses sequentialTaskExecutor for tiered parallel execution.
     */
    void runResumeAsync(PipelineRun run) {
        String runId = run.getRunId();
        activeRunThreads.add(runId);

        Observation resumeObs = Observation.createNotStarted("executespec.run.resume", observationRegistry)
            .lowCardinalityKeyValue("run.id", runId)
            .start();

        // Re-resolve API keys (user may have updated keys while paused)
        ResolvedKeys resolvedKeys = apiKeyResolver.resolve(run.getCreatedByUserId());
        runKeyStore.put(runId, resolvedKeys);
        run.setKeySource(resolvedKeys.keySourceMode());
        stateStore.saveRun(run);

        try {
            // ── 1. Gather all tasks and init agent context ──────────────────
            List<AgentTask> allTasks = stateStore.getAllTasks(runId).stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            try {
                TaskPlan plan = mapper.readValue(run.getPlanJson(), TaskPlan.class);
                initAgentContext(runId, mapper.writeValueAsString(plan));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Cannot parse plan JSON for resume", e);
            }

            // Validate file ownership for pending tasks only
            List<AgentTask> pendingTasks = allTasks.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .toList();
            validateFileOwnership(pendingTasks, runId);

            int completedBefore = (int) allTasks.stream()
                .filter(t -> "COMPLETE".equals(t.getStatus())).count();
            int pendingCount = pendingTasks.size();
            log.info("Resume run {}: {} tasks total, {} already complete, {} to re-run",
                runId, allTasks.size(), completedBefore, pendingCount);

            // ── 2. Tiered parallel execution ────────────────────────────────
            // Pass ALL tasks (including COMPLETE) so the tier computation
            // respects the full dependency graph. COMPLETE tasks are skipped
            // naturally: lockTask only succeeds for PENDING status.
            SequentialTaskExecutor.SequentialExecutionResult execResult =
                sequentialTaskExecutor.execute(run, allTasks);
            List<WorkerReport> reports = execResult.reports();

            // Merge in reports from previously-COMPLETE tasks
            List<WorkerReport> allReports = new ArrayList<>(reports);
            stateStore.getAllTasks(runId).stream()
                .filter(t -> t.getWorkerReportJson() != null)
                .filter(t -> allReports.stream()
                    .noneMatch(r -> r.getTaskId().equals(t.getTaskId())))
                .forEach(t -> {
                    try {
                        allReports.add(mapper.readValue(t.getWorkerReportJson(), WorkerReport.class));
                    } catch (Exception e) { /* skip unparseable reports */ }
                });

            // Mark openapi task complete (backwards compat)
            markOpenapiTaskComplete(runId, stateStore.getAllTasks(runId));

            // ── 3. Compiler pass ────────────────────────────────────────────
            compilerAgent.runCompileAndFix(runId);

            // ── 4. Merge-gate eligibility ───────────────────────────────────
            List<AgentTask> workerTasksNow = stateStore.getAllTasks(runId).stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            if (!checkTaskCompletionEligibility(workerTasksNow, runId, "after resume")) {
                broadcaster.complete(runId);
                return;
            }

            // ── 5. Cleanup stale worktrees before merge ──────────────────
            gitTool.pruneAllWorktrees(runId);

            // ── 6. Merge → Review → Report → Auto-fix → Complete ─────────
            PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
            doReviewAndMerge(freshRun, false);

        } catch (Exception e) {
            log.error("Resume of run {} failed: {}", runId, e.getMessage(), e);
            resumeObs.error(e);
            stateStore.markFailed(runId, "Resume failed: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
        } finally {
            activeRunThreads.remove(runId);
            resumeObs.stop();
            broadcaster.complete(runId);
            runKeyStore.remove(runId);
            runCostBudgetService.remove(runId);
        }
    }

    /**
     * Re-runs the reviewer and re-opens the merge gate for a PARTIAL or AWAITING_MERGE run
     * where all worker tasks are already COMPLETE.
     * Executes asynchronously. Validates that the run is in the correct status and all tasks
     * are COMPLETE before submitting; throws a descriptive exception otherwise so the caller
     * can return 4xx.
     */
    /**
     * Re-runs background validation for a COMPLETE run whose prior validation failed.
     * Called from the retry-validation API endpoint after {@link BackgroundValidationService#prepareRetry}
     * has already incremented the attempt counter and reset status to PENDING.
     */
    public void retryBackgroundValidation(String runId) {
        PipelineRun run = stateStore.findRun(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        List<WorkerReport> reports = java.util.Collections.emptyList();
        String planJson = run.getPlanJson() != null ? run.getPlanJson() : "{}";
        CompileResult compileResult = CompileResult.skip();
        backgroundValidationService.create(runId);
        var snapshot = ContextSnapshotFactory.builder().build().captureAll();
        Thread.ofVirtual().name("bg-validation-retry-" + runId).start(snapshot.wrap(() ->
                runBackgroundValidation(runId, run, reports, planJson,
                        run.getUserRequest(), compileResult, false, true)));
        log.info("[PipelineHarness] Retry background validation queued for run {}", runId);
    }

    public void reopenMergeGate(String runId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!"PARTIAL".equals(run.getStatus()) && !"AWAITING_MERGE".equals(run.getStatus()))
            throw new IllegalStateException("Run " + runId + " is not PARTIAL or AWAITING_MERGE (status: " + run.getStatus() + ")");

        List<AgentTask> tasks = stateStore.getAllTasks(runId);
        boolean allComplete = tasks.stream()
            .filter(t -> !isSystemTask(t))
            .allMatch(t -> "COMPLETE".equals(t.getStatus()));
        if (!allComplete)
            throw new IllegalStateException(
                "Cannot reopen merge gate: some tasks are not COMPLETE. Retry them first.");

        final PipelineRun frozenRun = run;
        executor.submit(() -> doReviewAndMerge(frozenRun, false));
    }

    /**
     * Implements post-merge suggestions (MEDIUM/LOW reviewer issues) by re-running the
     * affected worker tasks with suggestion feedback injected, then re-running the reviewer
     * and re-opening the merge gate.
     *
     * <p>Two call paths:
     * <ul>
     *   <li><b>AWAITING_MERGE</b>: resolves the live merge gate with
     *       {@link MergeDecision#IMPLEMENT_SUGGESTIONS}; the pipeline thread handles the rest.</li>
     *   <li><b>PARTIAL</b> (suggestions-failed retry): dispatches
     *       {@link #doReviewAndMerge} on the executor directly.</li>
     * </ul>
     */
    public void implementSuggestions(String runId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        String status = run.getStatus();
        if ("AWAITING_MERGE".equals(status)) {
            // Signal via Redis so the pipeline thread's polling loop picks it up
            gateStore.setMergeGate(runId, MergeDecision.IMPLEMENT_SUGGESTIONS);
        } else if ("PARTIAL".equals(status)
                && run.getFailureReason() != null
                && run.getFailureReason().contains("Suggestions implementation failed")) {
            final PipelineRun frozenRun = run;
            executor.submit(() -> doReviewAndMerge(frozenRun, false));
        } else {
            throw new IllegalStateException(
                "Cannot implement suggestions in status: " + status
                + ". Run must be AWAITING_MERGE (with live gate) or PARTIAL after a "
                + "failed suggestion implementation.");
        }
    }

    /**
     * User-triggered rework: re-runs the tasks flagged by the reviewer with
     * remediation feedback injected, then re-reviews and re-opens the gate.
     *
     * <p>Two call paths:
     * <ul>
     *   <li><b>AWAITING_MERGE</b>: resolves the live merge gate with
     *       {@link MergeDecision#REWORK}; the pipeline thread handles the rest.</li>
     *   <li><b>PARTIAL</b> (rework-failed retry): dispatches
     *       {@link #doReviewAndMerge} on the executor directly.</li>
     * </ul>
     */
    public void reworkRun(String runId) {
        PipelineRun run = stateStore.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        String status = run.getStatus();
        if ("AWAITING_MERGE".equals(status)) {
            // Signal via Redis so the pipeline thread's polling loop picks it up
            gateStore.setMergeGate(runId, MergeDecision.REWORK);
        } else if ("PARTIAL".equals(status)
                && run.getFailureReason() != null
                && run.getFailureReason().contains("Rework failed")) {
            final PipelineRun frozenRun = run;
            executor.submit(() -> doReviewAndMerge(frozenRun, false));
        } else {
            throw new IllegalStateException(
                "Cannot rework in status: " + status
                + ". Run must be AWAITING_MERGE (with live gate) or PARTIAL after a "
                + "failed rework.");
        }
    }

    /**
     * Re-runs the reviewer for a PARTIAL run and opens the merge gate.
     * Used by {@link #reopenMergeGate}, {@link #implementSuggestions}, and
     * {@link #reworkRun} to resume runs. Supports a while-loop to allow the user
     * to click action buttons repeatedly.
     */
    /**
     * @param holdsComputeSlot true when called from {@link #executeRunAsync} (which holds a
     *   semaphore slot); false when called from standalone handlers or {@link #runRetryAsync}
     *   (which do not hold a slot). The gate wait releases the slot iff this flag is true.
     */
    private void doReviewAndMerge(PipelineRun run, boolean holdsComputeSlot) {
        doReviewAndMerge(run, holdsComputeSlot, true);
    }

    /**
     * Merge → Review → Report → Auto-fix → Re-validate → Complete.
     * Used by retry/resume paths. No manual merge gate.
     */
    private void doReviewAndMerge(PipelineRun run, boolean holdsComputeSlot, boolean needsReview) {
        String runId = run.getRunId();

        // Guard: refuse to regress a terminal run back into the review/merge loop.
        PipelineRun guardCheck = stateStore.findRun(runId).orElse(run);
        String guardStatus = guardCheck.getStatus();
        if ("COMPLETE".equals(guardStatus) || "FAILED".equals(guardStatus)) {
            log.warn("[doReviewAndMerge] Skipping — run {} is already {} (would regress)",
                runId, guardStatus);
            return;
        }

        try {
            // ── 1. Merge all task branches ────────────────────────────────
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "Merging all task branches..."));
            List<String> conflicted = mergeService.mergeAll(runId, stateStore.getAllTasks(runId));
            lockTool.releaseAll(runId);

            if (!conflicted.isEmpty()) {
                stateStore.getAllTasks(runId).stream()
                    .filter(t -> conflicted.contains(t.getBranchName()))
                    .forEach(t -> {
                        stateStore.failTask(t.getTaskId(),
                            "Merge conflict — retry this task to regenerate the changes cleanly.");
                        broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "FAILED"));
                    });
                String conflictReason = "Merge conflict in " + conflicted.size()
                    + " task branch(es). Retry the highlighted tasks.";
                stateStore.markPartial(runId, conflictReason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + conflictReason));
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, conflictReason));
                return;
            }
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "✅ All task branches merged"));

            // ── 2. Post-merge compile ─────────────────────────────────────
            CompileResult postMergeCompile = null;
            try {
                postMergeCompile = compilerAgent.runCompileAndFix(runId);
            } catch (Exception e) {
                log.warn("Post-merge compile failed for run {}: {}", runId, e.getMessage());
            }

            // ── 3. Review merged code ─────────────────────────────────────
            ReviewReportV2 review = needsReview ? null : stateStore.getReviewReportV2(runId);
            if (needsReview) {
                List<WorkerReport> reports = getWorkerReports(runId);
                stateStore.markReviewing(runId);
                broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
                PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
                var providers = availableProviderResolver.resolve(runId);
                review = reviewerAgentV2.review(freshRun, stateStore.getAllTasks(runId),
                        freshRun.getUserRequest(), postMergeCompile != null ? postMergeCompile : CompileResult.skip(),
                        providers);
                stateStore.saveReviewV2(runId, review);

                // ── 4. Validation Report (non-fatal) ─────────────────────
                testPreviewCoordinator.cancelIfPending(runId);
                try {
                    generateValidationReport(runId, freshRun, reports, review, postMergeCompile);
                } catch (Exception reportEx) {
                    log.warn("Validation report generation failed for run {} (non-fatal): {}", runId, reportEx.getMessage());
                }
            }

            // ── 5. Re-validate after auto-fix ─────────────────────────────
            if (review != null && review.getAutoFixSucceeded() > 0) {
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "Re-validating after " + review.getAutoFixSucceeded() + " auto-fix(es)..."));
                try {
                    CompileResult revalidation = compilerAgent.runCompileAndFix(runId);
                    if (revalidation.passed() || revalidation.skipped()) {
                        broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                            "✅ Re-validation passed — all fixes confirmed"));
                    } else {
                        broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                            "⚠️ Re-validation found issues: " + revalidation.errorSummary()));
                    }
                } catch (Exception e) {
                    log.warn("Post-fix re-validation failed for run {}: {}", runId, e.getMessage());
                }
            }

            // ── 6. Complete ───────────────────────────────────────────────
            stateStore.markComplete(runId);
            saveActualCost(runId);
            broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
            log.info("Run {} complete — merged, reviewed, and validated", runId);

            try {
                PipelineRun scmRun = stateStore.findRun(runId).orElse(run);
                var scmUser = scmRun.getCreatedByUserId() != null
                        ? appUserRepository.findById(scmRun.getCreatedByUserId()).orElse(null) : null;
                pushToScmIfConfigured(scmRun, scmUser);
            } catch (Exception pushEx) {
                log.warn("SCM push failed for run {} (run is COMPLETE): {}", runId, pushEx.getMessage());
            }

            List<WorkerReport> reports = getWorkerReports(runId);
            final PipelineRun completedRun = stateStore.findRun(runId).orElse(run);
            boolean hadFailures = reports.stream()
                .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                               "partial".equalsIgnoreCase(r.getStatus()));
            eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                runId, completedRun, reports, review, hadFailures));
            eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

        } catch (Exception e) {
            log.error("Review+merge for run {} failed: {}", runId, e.getMessage(), e);
            stateStore.markFailed(runId, "Review+merge failed: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
        } finally {
            broadcaster.complete(runId);
        }
    }

    // ── REST async path: apply suggestion to existing run ──────────────────

    /**
     * Executes newly appended suggestion tasks on an existing COMPLETE/PARTIAL run.
     * Only the new tasks are executed. After completion, runs compiler + reviewer,
     * and restarts preview if it was previously active.
     *
     * @param runId   the existing run ID
     * @param taskIds IDs of the newly appended tasks to execute
     */
    public void executeApplySuggestion(String runId, List<String> taskIds) {
        // Acquire semaphore slot to respect concurrency limits
        if (!runSlots.tryAcquire()) {
            log.warn("[ApplySuggestion] No available run slots for run {}", runId);
            throw new IllegalStateException("Server at capacity — cannot apply suggestion right now. Try again shortly.");
        }

        PipelineRun applyRun = stateStore.findRun(runId).orElseThrow();
        String applyUserTier = "FREE";
        try {
            applyUserTier = tierEnforcementService.resolveUserTier(applyRun.getCreatedByUserId());
        } catch (Exception e) {
            log.debug("Could not resolve tier for apply-suggestion run {} — defaulting to FREE", runId);
        }
        final String applySuggestionTier = applyUserTier;
        try {
            dispatchToProviderPool(applyRun.getAiProvider(), runId, applySuggestionTier, () -> {
                log.info("[ApplySuggestion] Starting execution of {} tasks for run {}", taskIds.size(), runId);
                PipelineRun run = stateStore.findRun(runId).orElseThrow();
                String previousStatus = run.getStatus();

            try {
                // Ensure workspace is available (may restore from GCS after VM restart)
                restoreService.ensureWorkspaceAvailable(runId);

                // Set run to WORKING
                run.setStatus("WORKING");
                run.setCurrentGate(null);
                stateStore.save(run);
                broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

                // Load only the new tasks
                List<AgentTask> newTasks = taskIds.stream()
                        .map(stateStore::getTask)
                        .toList();

                // Execute using existing sequential task executor
                sequentialTaskExecutor.execute(run, newTasks);

                // Run compiler on full workspace
                broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
                CompileResult compileResult = null;
                try {
                    compileResult = compilerAgent.runCompileAndFix(runId);
                } catch (Exception e) {
                    log.warn("[ApplySuggestion] Compiler failed for run {}: {}", runId, e.getMessage());
                }

                // Run reviewer on the changes
                broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));
                ReviewReportV2 reviewResult = null;
                try {
                    var freshRun = stateStore.findRun(runId).orElseThrow();
                    var providers = availableProviderResolver.resolve(runId);
                    reviewResult = reviewerAgentV2.review(freshRun, newTasks, freshRun.getUserRequest(),
                            compileResult != null ? compileResult : CompileResult.skip(), providers);
                    stateStore.saveReviewV2(runId, reviewResult);
                } catch (Exception e) {
                    log.warn("[ApplySuggestion] Reviewer failed for run {}: {}", runId, e.getMessage());
                }

                // ── Test generation + execution (mirrors main pipeline Phase 4c) ──
                if ("PRODUCTION".equals(run.getExecutionMode())) {
                    try {
                        String planJson = stateStore.findRun(runId).map(PipelineRun::getPlanJson).orElse("{}");
                        String userReq = run.getUserRequest();
                        testCompletionFutures.put(runId, new CompletableFuture<>());
                        var snapshot = ContextSnapshotFactory.builder().build().captureAll();
                        Thread.ofVirtual().name("testgen-apply-" + runId).start(snapshot.wrap(() ->
                            testGenOrchestrator.runTestGenPipeline(runId, userReq, planJson)
                        ));
                        log.info("[ApplySuggestion] Test gen pipeline started for run {}", runId);
                    } catch (Exception e) {
                        testCompletionFutures.invalidate(runId);
                        log.warn("[ApplySuggestion] Failed to start test gen for run {}: {}", runId, e.getMessage());
                    }
                }

                // Generate validation report (afterImprovements=true for post-edit indicator)
                try {
                    var freshRun = stateStore.findRun(runId).orElseThrow();
                    List<WorkerReport> reports = getWorkerReports(runId);
                    generateValidationReport(runId, freshRun, reports, reviewResult, compileResult, true);
                    log.info("[ApplySuggestion] Validation report generated for run {}", runId);
                } catch (Exception e) {
                    log.warn("[ApplySuggestion] Validation report failed for run {}: {}", runId, e.getMessage());
                }

                // Determine final status based on task outcomes
                boolean allComplete = newTasks.stream()
                        .map(t -> stateStore.getTask(t.getTaskId()))
                        .allMatch(t -> "COMPLETE".equals(t.getStatus()));

                String finalStatus = allComplete ? "COMPLETE" : "PARTIAL";
                run = stateStore.findRun(runId).orElseThrow();
                run.setStatus(finalStatus);
                run.setCurrentGate(null);
                stateStore.save(run);

                broadcaster.emit(runId, RunEvent.complete(runId, finalStatus));

                log.info("[ApplySuggestion] run={} completed with status={}", runId, finalStatus);

            } catch (Exception e) {
                log.error("[ApplySuggestion] Failed for run {}: {}", runId, e.getMessage(), e);
                run = stateStore.findRun(runId).orElse(run);
                // Restore to previous status rather than marking FAILED
                run.setStatus(previousStatus);
                run.setCurrentGate(null);
                stateStore.save(run);
                broadcaster.emit(runId, RunEvent.runStatus(runId, previousStatus));
            } finally {
                runSlots.release();
            }
            });
        } catch (ProviderRunPool.ProviderQueueFullException e) {
            runSlots.release();
            log.warn("[ApplySuggestion] Run {} rejected — provider pool full: {}", runId, e.getMessage());
            throw new IllegalStateException(e.getMessage());
        }
    }

    // ── REST async path: iteration run (starts from a completed run's codebase) ──

    /**
     * Starts a new pipeline run that was created as an iteration of a completed run.
     * The workspace is populated by copying tracked files from the source run's workspace,
     * then the standard async pipeline executes as normal.
     *
     * @param runId      the new run's ID (already persisted via StateStore.createIterationRun())
     * @param newRequest the user's refined request
     */
    public void startIterationRun(String runId, String newRequest) {
        PipelineRun run = stateStore.findRun(runId).orElseThrow();
        String iterUserTier = "FREE";
        try {
            iterUserTier = tierEnforcementService.resolveUserTier(run.getCreatedByUserId());
        } catch (Exception e) {
            log.debug("Could not resolve tier for iteration run {} — defaulting to FREE", runId);
        }
        final String iterTier = iterUserTier;

        if (!runSlots.tryAcquire()) {
            run.setStatus("QUEUED");
            run.setQueuePosition(countQueued() + 1);
            stateStore.save(run);
            broadcaster.emit(runId, RunEvent.queued(runId, run.getQueuePosition()));
            log.info("Iteration run {} queued at position {} (provider: {})", runId, run.getQueuePosition(), run.getAiProvider());
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
                    dispatchToProviderPool(r.getAiProvider(), runId, iterTier, () -> {
                        try {
                            executeRunAsync(runId, newRequest);
                        } finally {
                            runSlots.release();
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stateStore.markFailed(runId, "Interrupted while waiting for run slot");
                } catch (ProviderRunPool.ProviderQueueFullException e) {
                    if (acquired) runSlots.release();
                    stateStore.markFailed(runId, e.getMessage());
                    log.warn("Iteration run {} rejected — provider pool full: {}", runId, e.getMessage());
                } finally {
                    // slot released inside dispatchToProviderPool task; catch blocks release on error
                }
            });
        } else {
            try {
                dispatchToProviderPool(run.getAiProvider(), runId, iterTier, () -> {
                    try {
                        executeRunAsync(runId, newRequest);
                    } finally {
                        runSlots.release();
                    }
                });
            } catch (ProviderRunPool.ProviderQueueFullException e) {
                runSlots.release();
                stateStore.markFailed(runId, e.getMessage());
                log.warn("Iteration run {} rejected — provider pool full at dispatch: {}", runId, e.getMessage());
            }
        }
    }

    // ── Internal async pipeline execution ────────────────────────────────────

    /** Returns true if the run has been cancelled (interrupted or DB-cancelled). */
    private boolean isCancelled(String runId) {
        if (Thread.currentThread().isInterrupted()) return true;
        PipelineRun r = stateStore.findRun(runId).orElse(null);
        return r != null && "CANCELLED".equals(r.getStatus());
    }

    private void executeRunAsync(String runId, String userRequest) {
        log.info("Executing async run {}", runId);
        activeRunThreads.add(runId);
        Metrics.ACTIVE_RUNS.incrementAndGet();
        Instant metricsRunStart = Instant.now();

        // ── Root observation span for the entire run ────────────────────────
        PipelineRun runForSource = stateStore.findRun(runId).orElse(null);
        String source = runForSource != null ? runForSource.getSourceType() : null;
        Observation runObs = Observation.createNotStarted("executespec.run", observationRegistry)
            .lowCardinalityKeyValue("run.id", runId)
            .lowCardinalityKeyValue("source", source != null ? source : "api")
            .start();

        // Initialise this run's isolated workspace (workspace/{runId}/) before doing anything else.
        // Priority order:
        //   1. ITERATION run (sourceRunId set)  — copy the source run's tracked files
        //   2. EXISTING mode (repoUrl set)      — clone the source GitHub repo
        //   3. Default                           — init a fresh empty git workspace
        PipelineRun run = stateStore.findRun(runId).orElseThrow();

        // ── Resolve execution mode — controls which pipeline phases are active ──
        ExecutionMode execMode = run.getExecutionModeEnum();
        log.info("Run {} execution mode: {}", runId, execMode);

        // ── Resolve per-user/org API keys and register for this run ──────────
        // Resolution hierarchy: user key → org key → system key (application.yml).
        // Keys are stored in RunKeyStore so LlmRouter can use them without any agent
        // signature changes. Cleaned up in the finally block below.
        ResolvedKeys resolvedKeys = apiKeyResolver.resolve(run.getCreatedByUserId());
        runKeyStore.put(runId, resolvedKeys);

        // Stamp billing flag so downstream accounting knows which key source was used
        run.setKeySource(resolvedKeys.keySourceMode());

        // Stamp user tier for async enforcement (model lock, cost budget)
        String userTier = tierEnforcementService.resolveUserTier(run.getCreatedByUserId());
        run.setUserTier(userTier);
        llmRouter.setRunUserTier(runId, userTier);

        // Stamp executing instance + initial heartbeat for cross-instance recovery detection
        run.setExecutingInstance(INSTANCE_ID);
        run.setExecutionHeartbeat(Instant.now());

        stateStore.saveRun(run);

        if (!initializeWorkspace(runId, run)) {
            return;
        }

        broadcaster.emit(runId, RunEvent.runStatus(runId, "PLANNING"));
        eventPublisher.publishEvent(new PipelineEvent.RunStarted(runId));

        // ── 0. JIRA/Confluence context extraction ─────────────────────────
        // Detect references in the user request and fetch content before planning.
        // If vague mentions found, pause and ask the user for clarification.
        String effectiveRequest;
        try {
            effectiveRequest = resolveContextReferences(runId, run, userRequest);
            if (effectiveRequest == null) {
                // Interrupted during clarification gate
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            stateStore.markFailed(runId, "Interrupted during context clarification");
            return;
        }

        // ── Check for cancellation before proceeding ────────────────────
        if (isCancelled(runId)) {
            log.info("Run {} cancelled before triage — aborting", runId);
            return;
        }

        // ── 0. Triage: classify request as SINGLE_SHOT or MULTI_TASK ─────
        var triageResult = requestTriageService.classify(effectiveRequest, runId, run.getAiProviderEnum());
        if (triageResult == com.navneet.executespec.agent.RequestTriageService.TriageResult.SINGLE_SHOT) {
            log.info("[PipelineHarness] Run {} classified as SINGLE_SHOT — bypassing planner + approval gate", runId);
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "Simple request detected — running single-shot fast path"));

            try {
                // Resolve and persist ui_style for frontend task generation (single-shot path)
                try {
                    String explicitHint = uiStyleResolver.extractStyleHint(effectiveRequest);
                    String designName = uiStyleResolver.resolveDesignName(
                            explicitHint, null, null); // no planner style or project type in single-shot
                    if (designName == null) designName = "vercel"; // default for single-shot frontend
                    String uiStyleJson = uiStyleResolver.resolve(effectiveRequest, null);
                    uiStyleJson = uiStyleResolver.enrichWithDesignName(uiStyleJson, designName);
                    stateStore.setUiStyle(runId, uiStyleJson);
                    log.info("[Run {}] Single-shot ui_style: {} (design={})", runId, uiStyleJson, designName);
                } catch (Exception ue) {
                    log.debug("[Run {}] Could not resolve ui_style for single-shot: {}", runId, ue.getMessage());
                }

                // Execute single-shot: synthetic plan → auto-approve → worker on main branch
                Instant singleShotStart = Instant.now();
                var singleShotResult = singleShotExecutor.execute(run, effectiveRequest);

                if (!singleShotResult.success()) {
                    stateStore.markFailed(runId, "Single-shot worker failed");
                    broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Single-shot worker failed"));
                    return;
                }

                // ── Post-worker phases: compile → review → test → report → complete ──
                // These are the same as the normal pipeline — just no merge needed.
                // Note: initAgentContext() is called inside SingleShotExecutor before the worker.

                // Compile & fix (non-fatal)
                broadcaster.emit(runId, RunEvent.runStatus(runId, "COMPILING"));
                broadcaster.emit(runId, RunEvent.log(runId, "harness", "Compiling and validating..."));
                CompileResult compileResult = null;
                try {
                    compileResult = compilerAgent.runCompileAndFix(runId);
                } catch (Exception compileEx) {
                    log.warn("[SingleShot] Compile failed for run {}: {}", runId, compileEx.getMessage());
                }

                // ── Triage escalation detection (feedback loop) ─────────────
                // Records whether thresholds (iterations/files/errors/duration) were exceeded.
                // Does not re-execute — outcome stored in triage_outcome for analytics.
                var escalation = triageEscalationDetector.evaluate(
                        runId, singleShotResult.reports(), compileResult, singleShotStart, "COMPLETE");
                if (escalation.shouldEscalate()) {
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                            "[Triage] This request exceeded single-shot thresholds (" + escalation.reason()
                            + "). Future similar requests will be routed to multi-task."));
                }

                // ── Mark COMPLETE + auto-start preview (both modes) ─────────
                // Same pattern as multi-task: user sees COMPLETE immediately
                // after compile. Review/test/report run in background.
                List<WorkerReport> currentReports = singleShotResult.reports();
                PipelineRun currentRun = stateStore.findRun(runId).orElse(run);
                final CompileResult finalCompileResult = compileResult;

                stateStore.markComplete(runId);
                saveActualCost(runId);
                broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
                log.info("[SingleShot] Run {} complete — compiled (preview + validation in background)", runId);
                eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

                // Auto-start preview for both modes
                broadcaster.emit(runId, RunEvent.log(runId, "harness", "Code ready — starting preview..."));
                autoStartPreview(runId, currentRun);

                // ── Background validation (all modes — includes test gen) ──
                {
                    final String bgUserReq = effectiveRequest;
                    final boolean runTests = true;
                    String bgPlanJson;
                    try {
                        bgPlanJson = mapper.writeValueAsString(singleShotResult.syntheticPlan());
                    } catch (JsonProcessingException jsonEx) {
                        bgPlanJson = "{}";
                        log.warn("[SingleShot] Could not serialize synthetic plan for run {}: {}", runId, jsonEx.getMessage());
                    }
                    final String finalBgPlanJson = bgPlanJson;
                    backgroundValidationService.create(runId);
                    var bgSnapshot = ContextSnapshotFactory.builder().build().captureAll();
                    Thread.ofVirtual().name("bg-validation-" + runId).start(bgSnapshot.wrap(() -> {
                        runBackgroundValidation(runId, currentRun, currentReports,
                                finalBgPlanJson, bgUserReq, finalCompileResult, false, runTests);
                    }));
                }

            } catch (Exception e) {
                log.error("[SingleShot] Run {} failed: {}", runId, e.getMessage(), e);
                stateStore.markFailed(runId, "Single-shot execution error: " + e.getMessage());
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
            } finally {
                runSlots.release();
                // If background validation is running, defer cleanup to that thread
                if (!backgroundValidationService.isActive(runId)) {
                    broadcaster.complete(runId);
                    runKeyStore.remove(runId);
                    runBudgetTracker.remove(runId);
                    runCostBudgetService.remove(runId);
                }
            }
            return; // single-shot path complete — skip normal pipeline
        }

        try {
            // ── 1. Plan ────────────────────────────────────────────────────
            Observation planObs = Observation.createNotStarted("executespec.run.phase.planning", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .start();
            TaskPlan plan;
            try (Observation.Scope planScope = planObs.openScope()) {
                // Pass the stored plannerModel so UI model-overrides are honoured.
                // run was already fetched from DB above, no extra DB call needed.
                plan = plannerAgent.plan(effectiveRequest, runId, run.getPlannerModel());
                stateStore.savePlan(runId, plan);

                // Resolve and persist ui_style for frontend task generation (3-signal priority)
                try {
                    // Signal 1: explicit hint from user request text
                    String explicitHint = uiStyleResolver.extractStyleHint(effectiveRequest);
                    // Signal 2: planner-declared ui_style from plan JSON
                    String plannerStyle = plan.getUiStyle();
                    // Signal 3: project type heuristic (inside resolveDesignName)
                    String designName = uiStyleResolver.resolveDesignName(
                            explicitHint, plannerStyle, plan.getProjectType());

                    // Build the legacy ui_style JSON and enrich with resolved design_name
                    String uiStyleJson = uiStyleResolver.resolve(effectiveRequest, null);
                    if (designName != null) {
                        uiStyleJson = uiStyleResolver.enrichWithDesignName(uiStyleJson, designName);
                    }
                    stateStore.setUiStyle(runId, uiStyleJson);
                    log.info("[Run {}] Resolved ui_style: {} (design={})", runId, uiStyleJson, designName);
                } catch (Exception ue) {
                    log.debug("[Run {}] Could not resolve ui_style: {}", runId, ue.getMessage());
                }

                // Compute and persist cost estimate + credits for plan approval UI
                try {
                    var costBreakdown = costEstimator.estimate(plan, run.getAiProviderEnum());
                    stateStore.setCostEstimate(runId, costBreakdown.lowUsd(), costBreakdown.highUsd());
                    log.info("[Run {}] {}", runId, costBreakdown.formatted());

                    // Calculate credits from buffered estimate (dual: real-time + batch)
                    String costTier = run.getUserTier() != null ? run.getUserTier() : "FREE";
                    RunCostEstimate creditEstimate = costEstimator.estimateWithCredits(
                            plan, run.getAiProviderEnum(), props.getCost(), costTier);
                    stateStore.setCreditsCharged(runId, creditEstimate.runCredits());
                    stateStore.setHardCapUsd(runId, creditEstimate.hardCap());
                    stateStore.setBatchCostFields(runId,
                            creditEstimate.batchAvailable() ? creditEstimate.batchCredits() : null,
                            creditEstimate.batchAvailable(),
                            creditEstimate.creditSavings());
                    log.info("[Run {}] Credits: {} (estimate=${}, hardCap=${}, batchAvail={}, batchCredits={})",
                            runId, creditEstimate.runCredits(),
                            String.format("%.2f", creditEstimate.totalEstimatedCost()),
                            String.format("%.2f", creditEstimate.hardCap()),
                            creditEstimate.batchAvailable(), creditEstimate.batchCredits());
                } catch (Exception ce) {
                    log.debug("[Run {}] Could not compute cost estimate: {}", runId, ce.getMessage());
                }

                // ── Complexity gate: block FREE / non-BYOK users from high-complexity plans ──
                if (run.getCreatedByUserId() != null) {
                    try {
                        tierEnforcementService.assertComplexityAllowed(run.getCreatedByUserId(), plan);
                    } catch (ResponseStatusException cex) {
                        log.warn("[PipelineHarness] Complexity gate blocked run {} for user {}: {}",
                                runId, run.getCreatedByUserId(), cex.getReason());
                        stateStore.markFailed(runId, cex.getReason());
                        broadcaster.emit(runId, RunEvent.log(runId, "harness", cex.getReason()));
                        broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                        eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, cex.getReason()));
                        // Refund the run count since the plan was blocked before execution
                        tierEnforcementService.decrementRunCount(run.getCreatedByUserId());
                        return;
                    }
                }

                broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_APPROVAL"));
            } catch (Exception e) {
                planObs.error(e);
                throw e;
            } finally {
                planObs.stop();
            }
            Instant planEnd = Instant.now();
            Instant planGateStart = Instant.now();

            // ── 2. Plan approval gate ──────────────────────────────────────
            Observation gateObs = Observation.createNotStarted("executespec.run.phase.approval_gate", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .start();
            boolean planApproved;
            try (Observation.Scope gateScope = gateObs.openScope()) {
                String planGateId = stateStore.recordGateOpened(runId, "PLAN_APPROVAL");
                // Emit gate event with credits info if available
                PipelineRun gateRun = stateStore.findRun(runId).orElse(run);
                int gateCredits = gateRun.getCreditsCharged() != null ? gateRun.getCreditsCharged() : 1;
                double gateEstimate = gateRun.getCostEstimateHigh() != null ? gateRun.getCostEstimateHigh() : 0.0;
                broadcaster.emit(runId, RunEvent.gateOpenedWithCredits(
                        runId, "PLAN_APPROVAL", gateCredits, gateEstimate));

                // Re-fetch the run: the controller may have set skipApproval / model overrides
                // after submitRun() returned but before we reach this point.
                run = stateStore.findRun(runId).orElse(run);

                // Tier 7: integration-triggered runs may skip the human approval gate
                if (run.isSkipApproval()) {
                    planApproved = true;
                    stateStore.recordGateResolved(planGateId, true);
                    broadcaster.emit(runId, RunEvent.gateResolved(runId, true, "Auto-approved (integration run)"));
                    log.info("Plan gate auto-approved for integration run {}", runId);
                } else {
                    // Release the compute slot while the run is idle at the approval gate.
                    // Gate-waiting runs must not block new submissions from starting.
                    log.info("[PipelineHarness] Run {} entering plan-approval gate — releasing compute slot", runId);
                    runSlots.release();
                    try {
                        // Poll Redis every 500 ms — works across any MIG instance
                        planApproved = gateStore.awaitPlanGate(runId);  // throws InterruptedException → outer catch
                    } finally {
                        try { runSlots.acquire(); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    log.info("[PipelineHarness] Run {} plan-approval gate resolved — compute slot reacquired", runId);
                    // Record gate resolution on the pipeline thread (it owns the local planGateId)
                    stateStore.recordGateResolved(planGateId, planApproved);
                }
            } catch (Exception e) {
                gateObs.error(e);
                throw e;
            } finally {
                gateObs.stop();
            }
            Instant approvalEnd = Instant.now();
            Timer.builder(GATE_WAIT_TIME).tag("gate", "plan_approval")
                    .register(meterRegistry).record(java.time.Duration.between(planGateStart, approvalEnd));
            Timer.builder(HANDOVER_DURATION).tag("from_phase", "planning").tag("to_phase", "approval_gate")
                    .register(meterRegistry).record(java.time.Duration.between(planEnd, approvalEnd));

            if (!planApproved) {
                // Guard: if run was paused (not rejected), don't overwrite PAUSED with FAILED
                PipelineRun currentState = stateStore.findRun(runId).orElse(null);
                if (currentState != null && "PAUSED".equals(currentState.getStatus())) {
                    log.info("Run {} was paused during plan gate — exiting cleanly", runId);
                    return;
                }
                stateStore.markRejected(runId, "Rejected at plan approval gate");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "Rejected at plan approval gate"));
                broadcaster.complete(runId);
                return;
            }

            // ── Charge credits at plan approval ─────────────────────────────
            run = stateStore.findRun(runId).orElse(run);
            boolean isBatchMode = "BATCH".equals(run.getApprovedExecutionMode());
            int creditsToCharge;
            if (isBatchMode && run.getBatchCredits() != null) {
                creditsToCharge = run.getBatchCredits();
            } else {
                creditsToCharge = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
            }
            String approvalUserId = run.getCreatedByUserId();

            if (approvalUserId != null) {
                // FREE tier: block specs requiring more than maxCreditsFree
                int maxFree = props.getCost().getMaxCreditsFree();
                String approvalTier = run.getUserTier() != null ? run.getUserTier() : "FREE";
                boolean hasByok = "OWN_KEYS".equals(run.getKeySource()) || "HYBRID".equals(run.getKeySource());
                boolean isEnterprise = "ENTERPRISE".equalsIgnoreCase(approvalTier);

                if ("FREE".equalsIgnoreCase(approvalTier) && !hasByok && creditsToCharge > maxFree) {
                    String msg = "This spec requires " + creditsToCharge + " credits but free tier supports " +
                            maxFree + "-credit specs only. Upgrade to Pro or purchase a top-up pack to run complex specifications.";
                    stateStore.markRejected(runId, msg);
                    broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, msg));
                    broadcaster.complete(runId);
                    return;
                }

                // Charge N credits (BYOK/ENTERPRISE unlimited — skip charge)
                if (!hasByok && !isEnterprise) {
                    try {
                        tierEnforcementService.assertAndIncrementRunCount(approvalUserId, creditsToCharge);
                    } catch (ResponseStatusException qex) {
                        String msg = "This spec requires " + creditsToCharge + " credits but you have insufficient " +
                                "remaining runs. Purchase a top-up pack or upgrade your tier to continue.";
                        stateStore.markRejected(runId, msg);
                        broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                        eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, msg));
                        broadcaster.complete(runId);
                        return;
                    }
                }
            }

            stateStore.markApproved(runId);

            // ── Batch routing: if user chose BATCH, submit to batch API and return ──
            run = stateStore.findRun(runId).orElseThrow();
            log.info("[Run {}] Post-approval execution mode: {}, batch enabled: {}",
                    runId, run.getApprovedExecutionMode(), props.getCost().getBatch().isEnabled());
            if ("BATCH".equals(run.getApprovedExecutionMode())
                    && props.getCost().getBatch().isEnabled()) {
                try {
                    broadcaster.emit(runId, RunEvent.runStatus(runId, "QUEUED_BATCH"));
                    stateStore.updateStatus(runId, "QUEUED_BATCH");

                    var batchBuildResult = batchRequestBuilder.buildRequests(
                            plan, run.getAiProviderEnum(), run.getPlanJson());

                    // Create subtask DB rows for decomposed multi-file tasks
                    String tenantId = run.getTenantId() != null ? run.getTenantId() : "default";
                    for (var entry : batchBuildResult.decomposedTasks().entrySet()) {
                        stateStore.createSubtasks(entry.getKey(), runId, entry.getValue(), tenantId);
                        log.info("[Run {}] Created {} subtasks for parent task {}",
                                runId, entry.getValue().size(), entry.getKey());
                    }

                    String batchId = batchClientRouter.submitBatch(
                            runId, batchBuildResult.requests(), run.getAiProviderEnum());

                    stateStore.setBatchInfo(runId, batchId, run.getAiProviderEnum().name());
                    stateStore.updateBatchProgress(runId, 0, batchBuildResult.requests().size());
                    stateStore.updateStatus(runId, "IN_PROGRESS_BATCH");
                    broadcaster.emit(runId, RunEvent.runStatus(runId, "IN_PROGRESS_BATCH"));
                    int decomposed = batchBuildResult.decomposedTasks().size();
                    String decompMsg = decomposed > 0
                            ? " (" + decomposed + " multi-file tasks decomposed into subtasks)"
                            : "";
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                            "Batch submitted to " + run.getAiProviderEnum() + " (" +
                            batchBuildResult.requests().size() + " requests)." + decompMsg +
                            " Polling for completion..."));
                    log.info("[Run {}] Batch submitted: batchId={}, provider={}, requests={}, decomposed={}",
                            runId, batchId, run.getAiProviderEnum(),
                            batchBuildResult.requests().size(), decomposed);
                    // Pipeline pauses here — BatchPollService will resume when complete
                    return;
                } catch (Exception batchEx) {
                    log.error("[Run {}] Batch submission failed, falling back to real-time: {}",
                            runId, batchEx.getMessage(), batchEx);
                    stateStore.setApprovedExecutionMode(runId, "REALTIME");
                    stateStore.updateStatus(runId, "WORKING");
                    // Notify user of fallback via SSE — both batchFailed event and log message
                    broadcaster.emit(runId, RunEvent.batchFailed(runId,
                            "Batch submission failed: " + batchEx.getMessage() + ". Falling back to real-time execution."));
                    broadcaster.emit(runId, RunEvent.log(runId, "harness",
                            "⚠ Batch submission to " + run.getAiProviderEnum() + " failed — running tasks in real-time instead. " +
                            "You will be charged at real-time rates."));
                    // Fall through to normal execution
                }
            }

            broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));

            // ── 3. Workers ─────────────────────────────────────────────────
            Observation workObs = Observation.createNotStarted("executespec.run.phase.working", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .start();
            List<AgentTask> tasks;
            List<WorkerReport> reports;
            try (Observation.Scope workScope = workObs.openScope()) {
                tasks = stateStore.getPendingTasks(runId);
                try {
                    initAgentContext(runId, mapper.writeValueAsString(plan));
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Cannot serialize plan", e);
                }

                // Filter out legacy system tasks (generate_preview, generate_openapi_spec)
                // that may still exist in old plans stored in the DB.
                List<AgentTask> workerTasks = tasks.stream()
                    .filter(t -> !isSystemTask(t))
                    .toList();

                // Check for cancellation before starting workers
                if (isCancelled(runId)) {
                    log.info("Run {} cancelled before worker execution — aborting", runId);
                    return;
                }

                // Validate file ownership — detect overlap before workers start
                validateFileOwnership(workerTasks, runId);

                // Initialize per-run cost budget — dynamic ceiling from credits × multiplier × margin
                boolean hasByok = "OWN_KEYS".equals(run.getKeySource()) || "HYBRID".equals(run.getKeySource());
                boolean isEnterprise = "ENTERPRISE".equalsIgnoreCase(
                        run.getUserTier() != null ? run.getUserTier() : "FREE");

                // Initialize run-level call-count budget tracker
                // ENTERPRISE/BYOK: unlimited. Multi-credit runs: ceiling scales with credits.
                int budgetCredits = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
                runBudgetTracker.initBudget(runId, plan, budgetCredits, hasByok || isEnterprise);
                double costBudget;
                if (hasByok || isEnterprise) {
                    costBudget = -1; // unlimited
                } else {
                    int credits = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
                    double multiplier = props.getCost().getCreditMultiplierUsd();
                    double margin = props.getCost().getBudgetMargin();
                    costBudget = credits * multiplier * margin;
                }
                runCostBudgetService.initBudget(runId, costBudget, run.getUserTier());

                // === SEQUENTIAL EXECUTION WITH VALIDATION + MODEL ROUTING ===
                SequentialTaskExecutor.SequentialExecutionResult execResult =
                    sequentialTaskExecutor.execute(run, workerTasks, execMode);
                reports = execResult.reports();

                // Budget exceeded between tiers — stop pipeline and fail the run
                if (execResult.budgetExceeded()) {
                    String budgetMsg = "Run stopped — cost budget exceeded. Upgrade your plan for higher limits.";
                    log.warn("Run {} stopped due to cost budget exceeded between tasks", runId);
                    stateStore.markFailed(runId, budgetMsg);
                    broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, budgetMsg));
                    return;
                }

                if (execResult.hasFailures()) {
                    log.warn("Run {} has {} failed tasks out of {}",
                        runId, execResult.failedTasks().size(), execResult.totalTasks());
                    if (execResult.completedTasks().isEmpty()) {
                        stateStore.markFailed(runId, "All tasks failed validation");
                        broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                        eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, "All tasks failed validation"));
                        PipelineRun failedAllRun = stateStore.findRun(runId).orElse(null);
                        if (failedAllRun != null) autoRetryRunIfEligible(failedAllRun);
                        return;
                    }
                }

                // Auto-complete generate_openapi_spec (backwards compat with old plans that still
                // carry this task in the DB).  ApiTestSuiteAgent generates the real spec post-merge.
                markOpenapiTaskComplete(runId, tasks);
            } catch (Exception e) {
                workObs.error(e);
                throw e;
            } finally {
                workObs.stop();
            }
            Instant workEnd = Instant.now();
            Timer.builder(HANDOVER_DURATION).tag("from_phase", "approval_gate").tag("to_phase", "working")
                    .register(meterRegistry).record(java.time.Duration.between(approvalEnd, workEnd));

            // ── Tier 8: Compile & Fix (non-fatal, sync before reviewer) ───
            broadcaster.emit(runId, RunEvent.runStatus(runId, "COMPILING"));
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "Compile & fix pass..."));
            CompileResult mainCompileResult = null;
            Observation compileObs = Observation.createNotStarted("executespec.run.phase.compile", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .start();
            try (Observation.Scope compileScope = compileObs.openScope()) {
                mainCompileResult = compilerAgent.runCompileAndFix(runId);
            } catch (Exception e) {
                compileObs.error(e);
                throw e;
            } finally {
                compileObs.stop();
            }
            Instant compileEnd = Instant.now();
            Timer.builder(HANDOVER_DURATION).tag("from_phase", "working").tag("to_phase", "compile")
                    .register(meterRegistry).record(java.time.Duration.between(workEnd, compileEnd));

            // ── Merge-gate eligibility: only open when all WORKER tasks complete ──
            // Worker tasks = all tasks except the preview task (its BLOCKED status
            // must not block the merge gate since it is non-fatal).
            List<AgentTask> workerTasksNow = stateStore.getAllTasks(runId).stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            boolean allWorkerTasksBlocked = workerTasksNow.stream()
                .allMatch(t -> "BLOCKED".equals(t.getStatus()) || "FAILED".equals(t.getStatus()));

            if (allWorkerTasksBlocked) {
                String reason = "All tasks blocked or failed — nothing to merge";
                log.warn("Auto-failing run {} — {}", runId, reason);
                stateStore.markFailed(runId, reason);
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
                PipelineRun failedRun = stateStore.findRun(runId).orElse(run);
                return;
            }

            boolean allWorkerTasksComplete = workerTasksNow.stream()
                .allMatch(t -> "COMPLETE".equals(t.getStatus()));

            if (!allWorkerTasksComplete) {
                // Some tasks did not reach COMPLETE — mark run PARTIAL so the user
                // can retry individual tasks. The merge gate does NOT open here;
                // it opens after a successful retry that completes all remaining tasks.
                List<String> incompleteTitles = workerTasksNow.stream()
                    .filter(t -> !"COMPLETE".equals(t.getStatus()))
                    .map(t -> t.getTitle() + " [" + t.getStatus() + "]")
                    .toList();
                String partialReason = "Incomplete tasks: " + String.join("; ", incompleteTitles);
                log.warn("Run {} — {} — marking PARTIAL (no merge gate)", runId, partialReason);
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⚠️ Some tasks did not complete:\n  • " + String.join("\n  • ", incompleteTitles)
                    + "\n\nRun marked PARTIAL. Use 'Retry Task' on each incomplete task — "
                    + "the merge gate will open once all tasks are COMPLETE."));
                stateStore.markPartial(runId, partialReason);
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, partialReason));
                // Attempt auto-retry before giving up
                PipelineRun partialRun = stateStore.findRun(runId).orElse(null);
                if (partialRun != null) autoRetryRunIfEligible(partialRun);
                return;
            }

            // ── 3b. Cleanup stale worktrees before merge ──────────────────
            gitTool.pruneAllWorktrees(runId);

            // ══════════════════════════════════════════════════════════════════
            // ── 4. MERGE ALL TASK BRANCHES (no gate — automatic) ─────────────
            // ══════════════════════════════════════════════════════════════════
            broadcaster.emit(runId, RunEvent.runStatus(runId, "MERGING"));
            Observation mergeObs = Observation.createNotStarted("executespec.run.phase.merge", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .start();
            try (Observation.Scope mergeScope = mergeObs.openScope()) {
                broadcaster.emit(runId, RunEvent.log(runId, "harness", "Merging all task branches..."));

                List<String> conflicted = mergeService.mergeAll(runId, stateStore.getAllTasks(runId));
                lockTool.releaseAll(runId);

                if (!conflicted.isEmpty()) {
                    List<AgentTask> allTasksForConflict = stateStore.getAllTasks(runId);
                    allTasksForConflict.stream()
                        .filter(t -> conflicted.contains(t.getBranchName()))
                        .forEach(t -> {
                            stateStore.failTask(t.getTaskId(),
                                "Merge conflict — retry this task to regenerate the changes cleanly.");
                            broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "FAILED"));
                        });
                    String conflictReason = "Merge conflict in " + conflicted.size() + " task branch(es): "
                        + String.join(", ", conflicted) + ". Retry the highlighted tasks.";
                    stateStore.markPartial(runId, conflictReason);
                    broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + conflictReason));
                    broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
                    eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, conflictReason));
                    log.warn("Async run {} — partial merge: {}", runId, conflictReason);
                    PipelineRun conflictRun = stateStore.findRun(runId).orElse(null);
                    if (conflictRun != null) autoRetryRunIfEligible(conflictRun);
                    return;
                }

                broadcaster.emit(runId, RunEvent.log(runId, "harness", "✅ All task branches merged successfully"));
            } catch (Exception e) {
                mergeObs.error(e);
                throw e;
            } finally {
                mergeObs.stop();
            }
            Instant mergeEnd = Instant.now();
            Timer.builder(HANDOVER_DURATION).tag("from_phase", "compile").tag("to_phase", "merge")
                    .register(meterRegistry).record(java.time.Duration.between(compileEnd, mergeEnd));

            // ── 4b. Post-merge compile validation ────────────────────────────
            // Catches semantic conflicts that pass textual merge but break compilation.
            CompileResult postMergeCompile = null;
            try {
                postMergeCompile = compilerAgent.runCompileAndFix(runId);
                if (!postMergeCompile.passed() && !postMergeCompile.skipped()) {
                    broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                        "⚠️ Post-merge compile issues: " + postMergeCompile.errorSummary()));
                }
            } catch (Exception compileEx) {
                log.warn("Post-merge compile failed for run {}: {}", runId, compileEx.getMessage());
            }

            // ══════════════════════════════════════════════════════════════════
            // ── 5. MARK COMPLETE + AUTO-START PREVIEW ────────────────────────
            // ══════════════════════════════════════════════════════════════════
            // Both PROTOTYPE and PRODUCTION: mark complete immediately after
            // compile+merge. User gets preview fast; review/tests run in background.
            List<WorkerReport> currentReports = new ArrayList<>(reports);
            PipelineRun currentRun = stateStore.findRun(runId).orElse(run);

            stateStore.markComplete(runId);
            saveActualCost(runId);
            broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
            log.info("Run {} complete — compiled and merged (preview + validation in background)", runId);
            eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

            // Auto-start preview for both modes
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "Code ready — starting preview..."));
            autoStartPreview(runId, currentRun);

            // SCM push (non-blocking)
            try {
                var scmUser = currentRun.getCreatedByUserId() != null
                        ? appUserRepository.findById(currentRun.getCreatedByUserId()).orElse(null) : null;
                pushToScmIfConfigured(currentRun, scmUser);
            } catch (Exception pushEx) {
                log.warn("SCM push failed for run {} (non-fatal): {}", runId, pushEx.getMessage());
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⚠️ SCM push failed (run is COMPLETE, code was merged locally): "
                    + pushEx.getMessage()));
            }

            // ══════════════════════════════════════════════════════════════════
            // ── 6. BACKGROUND VALIDATION (all modes) ─────────────────────────
            // ══════════════════════════════════════════════════════════════════
            // Review and validation report run in background for ALL modes.
            // Test generation/execution runs in all modes.
            {
                final CompileResult finalPostMergeCompile = postMergeCompile != null ? postMergeCompile : mainCompileResult;
                final boolean runTests = true;
                String bgPlanJson;
                try {
                    bgPlanJson = mapper.writeValueAsString(plan);
                } catch (JsonProcessingException jsonEx) {
                    bgPlanJson = "{}";
                    log.warn("Could not serialize plan for run {}: {}", runId, jsonEx.getMessage());
                }
                final String finalBgPlanJson = bgPlanJson;
                backgroundValidationService.create(runId);
                var bgSnapshot = ContextSnapshotFactory.builder().build().captureAll();
                Thread.ofVirtual().name("bg-validation-" + runId).start(bgSnapshot.wrap(() -> {
                    runBackgroundValidation(runId, currentRun, currentReports,
                            finalBgPlanJson, currentRun.getUserRequest(), finalPostMergeCompile,
                            true, runTests);
                }));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runObs.error(e);
            // Guard: don't overwrite PAUSED with FAILED
            PipelineRun intState = stateStore.findRun(runId).orElse(null);
            if (intState != null && "PAUSED".equals(intState.getStatus())) {
                log.info("Run {} was paused (interrupted) — exiting cleanly", runId);
            } else {
                String reason = "Interrupted: " + e.getMessage();
                stateStore.markFailed(runId, reason);
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
            }
        } catch (Exception e) {
            log.error("Async run {} failed: {}", runId, e.getMessage(), e);
            runObs.error(e);
            // Guard: don't overwrite PAUSED with FAILED
            PipelineRun exState = stateStore.findRun(runId).orElse(null);
            if (exState != null && "PAUSED".equals(exState.getStatus())) {
                log.info("Run {} was paused — suppressing FAILED transition", runId);
            } else {
                stateStore.markFailed(runId, e.getMessage());
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, e.getMessage()));
                PipelineRun failedRunEx = stateStore.findRun(runId).orElse(null);
                if (failedRunEx != null) autoRetryRunIfEligible(failedRunEx);
            }
        } finally {
            activeRunThreads.remove(runId);
            Metrics.ACTIVE_RUNS.decrementAndGet();
            // Record run duration and count
            String finalStatus = "UNKNOWN";
            try {
                PipelineRun finalRun = stateStore.findRun(runId).orElse(null);
                if (finalRun != null) finalStatus = finalRun.getStatus();
            } catch (Exception ignored) {}
            java.time.Duration runDuration = java.time.Duration.between(metricsRunStart, Instant.now());
            Timer.builder(RUN_DURATION).tag("status", finalStatus)
                    .register(meterRegistry).record(runDuration);
            Counter.builder(RUN_TOTAL).tag("status", finalStatus)
                    .register(meterRegistry).increment();

            runObs.stop();
            // If background validation is running, defer cleanup to that thread
            if (!backgroundValidationService.isActive(runId)) {
                broadcaster.complete(runId);
                runKeyStore.remove(runId);  // clean up per-run API keys
                runBudgetTracker.remove(runId);  // clean up per-run budget tracking
            }
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
                var providers = availableProviderResolver.resolve(run.getRunId());
                ReviewReportV2 review = reviewerAgentV2.review(run, stateStore.getAllTasks(run.getRunId()),
                        run.getUserRequest(), CompileResult.skip(), providers);
                stateStore.saveReviewV2(run.getRunId(), review);
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
        WorkerPool.WorkerPoolResult poolResult = workerPool.runAll(incomplete, run.getRunId(),
            props.getRoles().getWorker());
        List<WorkerReport> reports = poolResult.reports();
        stateStore.markReviewing(run.getRunId());
        PipelineRun fresh = stateStore.findRun(run.getRunId()).orElse(run);
        var providers = availableProviderResolver.resolve(run.getRunId());
        ReviewReportV2 review = reviewerAgentV2.review(fresh, stateStore.getAllTasks(run.getRunId()),
                fresh.getUserRequest(), CompileResult.skip(), providers);
        stateStore.saveReviewV2(run.getRunId(), review);
        runMergeGate(run);
    }

    private void runMergeGate(PipelineRun run) {
        ReviewReportV2 review = parseJson(run.getReviewJson(), ReviewReportV2.class);
        boolean shouldMerge = approvalGate.requestMerge(review);
        if (shouldMerge) {
            List<String> conflicted = mergeService.mergeAll(run.getRunId(), stateStore.getAllTasks(run.getRunId()));
            lockTool.releaseAll(run.getRunId());
            if (!conflicted.isEmpty()) {
                stateStore.getAllTasks(run.getRunId()).stream()
                    .filter(t -> conflicted.contains(t.getBranchName()))
                    .forEach(t -> stateStore.failTask(t.getTaskId(),
                        "Merge conflict — retry this task to regenerate the changes cleanly."));
                stateStore.markPartial(run.getRunId(),
                    "Merge conflict in " + conflicted.size() + " task branch(es).");
            } else {
                var scmUser = run.getCreatedByUserId() != null
                        ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null) : null;
                pushToScmIfConfigured(run, scmUser);
                stateStore.markComplete(run.getRunId());
            }
        }
    }

    // ── Shared background validation logic ─────────────────────────────────────

    /**
     * Runs the full background validation pipeline: test gen, review, re-validation,
     * test preview cancellation, validation report, MergeCompleted event, and cost update.
     * Called from both the single-shot and normal pipeline background threads.
     *
     * @param runId              the pipeline run ID
     * @param run                the current PipelineRun snapshot
     * @param reports            worker reports from the completed run
     * @param planJson           serialized plan JSON (synthetic for single-shot, full for normal)
     * @param userRequest        the user's original request text
     * @param compileResult      compile result to include in the validation report
     * @param computeHadFailures if true, compute hadFailures from reports; if false, pass false
     */
    private void runBackgroundValidation(String runId, PipelineRun run,
                                         List<WorkerReport> reports, String planJson,
                                         String userRequest, CompileResult compileResult,
                                         boolean computeHadFailures) {
        runBackgroundValidation(runId, run, reports, planJson, userRequest, compileResult,
                computeHadFailures, true);
    }

    private void runBackgroundValidation(String runId, PipelineRun run,
                                         List<WorkerReport> reports, String planJson,
                                         String userRequest, CompileResult compileResult,
                                         boolean computeHadFailures, boolean runTests) {
        backgroundValidationService.markRunning(runId);
        broadcaster.emit(runId, RunEvent.log(runId, "harness", "Background validation started"));
        try {
            // Test generation (async — fires TestsReadyEvent when done)
            // Skipped for PROTOTYPE mode to keep lightweight
            if (runTests) {
                backgroundValidationService.setPhase(runId, "TEST_GEN");
                try {
                    testCompletionFutures.put(runId, new CompletableFuture<>());
                    testGenOrchestrator.runTestGenPipeline(runId, userRequest, planJson);
                    backgroundValidationService.recordPhaseResult(runId, "TEST_GEN", "completed");
                    log.info("Run {} — test gen pipeline completed in background", runId);
                } catch (Exception e) {
                    testCompletionFutures.invalidate(runId);
                    backgroundValidationService.recordPhaseResult(runId, "TEST_GEN", "failed: " + e.getMessage());
                    log.warn("Run {} — background test gen failed: {}", runId, e.getMessage());
                }
            } else {
                log.info("Run {} — skipping test gen (PROTOTYPE mode)", runId);
            }

            // Review merged code
            backgroundValidationService.setPhase(runId, "REVIEW");
            ReviewReportV2 review = null;
            Observation reviewObs = Observation.createNotStarted("executespec.run.phase.review", observationRegistry)
                .lowCardinalityKeyValue("run.id", runId)
                .start();
            try (Observation.Scope reviewScope = reviewObs.openScope()) {
                broadcaster.emit(runId, RunEvent.log(runId, "harness", "Background review starting..."));
                var providers = availableProviderResolver.resolve(runId);
                review = reviewerAgentV2.review(run, stateStore.getAllTasks(runId),
                        run.getUserRequest(), compileResult != null ? compileResult : CompileResult.skip(),
                        providers);
                stateStore.saveReviewV2(runId, review);
                backgroundValidationService.recordPhaseResult(runId, "REVIEW",
                        review.getVerdict() != null ? review.getVerdict().name() : "UNKNOWN");

                if (review.getVerdict() == ReviewVerdict.NEEDS_FIXES
                        || review.getVerdict() == ReviewVerdict.REJECTED) {
                    broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                        "⚠️ Reviewer found issues: " + review.getSummary()));
                } else {
                    broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                        "✅ Review passed: " + review.getSummary()));
                }
            } catch (Exception e) {
                reviewObs.error(e);
                log.warn("Run {} — background review failed: {}", runId, e.getMessage());
                broadcaster.emit(runId, RunEvent.log(runId, "reviewer",
                    "⚠️ Background review failed: " + e.getMessage()));
            } finally {
                reviewObs.stop();
            }

            // Re-validate after auto-fix
            backgroundValidationService.setPhase(runId, "RE_VALIDATE");
            boolean fixesApplied = review != null && review.getAutoFixSucceeded() > 0;
            if (fixesApplied) {
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "Re-validating after " + review.getAutoFixSucceeded() + " auto-fix(es)..."));
                try {
                    CompileResult revalidation = compilerAgent.runCompileAndFix(runId);
                    backgroundValidationService.recordPhaseResult(runId, "RE_VALIDATE",
                            revalidation.passed() ? "passed" : "issues");
                    if (revalidation.passed() || revalidation.skipped()) {
                        broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                            "✅ Re-validation passed — all fixes confirmed"));
                    } else {
                        broadcaster.emit(runId, RunEvent.log(runId, "compiler",
                            "⚠️ Re-validation found issues: " + revalidation.errorSummary()));
                    }
                } catch (Exception revalEx) {
                    backgroundValidationService.recordPhaseResult(runId, "RE_VALIDATE", "failed");
                    log.warn("Post-fix re-validation failed for run {}: {}", runId, revalEx.getMessage());
                }
            }

            // Cancel pending test execution if preview never started
            testPreviewCoordinator.cancelIfPending(runId);

            // Validation report
            backgroundValidationService.setPhase(runId, "REPORT");
            try {
                generateValidationReport(runId, run, reports, review, compileResult);
                backgroundValidationService.recordPhaseResult(runId, "REPORT", "completed");
            } catch (Exception reportEx) {
                backgroundValidationService.recordPhaseResult(runId, "REPORT", "failed");
                log.warn("Background validation report failed for run {} (non-fatal): {}", runId, reportEx.getMessage());
                broadcaster.emit(runId, RunEvent.log(runId, "report",
                    "⚠️ Report generation failed"));
            }

            // Publish MergeCompleted for post-merge agents / integration adapters
            boolean hadFailures = computeHadFailures && reports.stream()
                .anyMatch(r -> "blocked".equalsIgnoreCase(r.getStatus()) ||
                               "partial".equalsIgnoreCase(r.getStatus()));
            eventPublisher.publishEvent(new PipelineEvent.MergeCompleted(
                runId, run, reports, review, hadFailures));

            // Update actual cost after background work
            saveActualCost(runId);

            backgroundValidationService.markCompleted(runId);
            log.info("Run {} — background validation complete", runId);
        } catch (Exception e) {
            log.error("Background validation failed for run {}: {}", runId, e.getMessage(), e);
            backgroundValidationService.markFailed(runId, e.getMessage());
            broadcaster.emit(runId, RunEvent.validationFailed(runId, null,
                "Validation could not complete: " + e.getMessage()));
        } finally {
            runKeyStore.remove(runId);
            runBudgetTracker.remove(runId);
            runCostBudgetService.remove(runId);
            broadcaster.complete(runId);
        }
    }

    // ── Actual cost computation ───────────────────────────────────────────────

    /**
     * Computes the cache-aware actual cost for a completed run and persists it.
     * Uses per-task provider/model pricing with Anthropic cache multipliers.
     * Non-fatal: a failure here does not affect run status.
     */
    private void saveActualCost(String runId) {
        try {
            double cost = costEstimator.computeCostForRun(runId);
            stateStore.setActualCost(runId, cost);
            log.info("[PipelineHarness] Actual cost for run {}: ${}", runId, String.format("%.4f", cost));

            // Write cost audit log for pricing analytics
            writeRunCostLog(runId, cost);
        } catch (Exception e) {
            log.warn("[PipelineHarness] Could not compute actual cost for run {}: {}", runId, e.getMessage());
        }
        // Record E2E duration for SLO tracking (non-fatal)
        recordE2eDuration(runId, "completed");
    }

    /**
     * Records the end-to-end pipeline duration from run start to the current instant.
     * Tagged with execution mode (real_time/batch) and outcome (completed/failed).
     * Non-fatal — any failure is logged and ignored.
     */
    private void recordE2eDuration(String runId, String outcome) {
        try {
            stateStore.findRun(runId).ifPresent(run -> {
                if (run.getStartedAt() != null) {
                    java.time.Duration e2e = java.time.Duration.between(run.getStartedAt(), java.time.Instant.now());
                    String mode = "BATCH".equals(run.getApprovedExecutionMode()) ? "batch" : "real_time";
                    io.micrometer.core.instrument.Timer.builder(
                                    com.navneet.executespec.config.CustomMetricsConfig.RUN_E2E_DURATION)
                            .tag("mode", mode)
                            .tag("status", outcome)
                            .register(meterRegistry)
                            .record(e2e);
                }
            });
        } catch (Exception ex) {
            log.debug("[PipelineHarness] Could not record E2E duration for run {}: {}", runId, ex.getMessage());
        }
    }

    /** Persists a RunCostLog entry for pricing analytics. Non-fatal. */
    private void writeRunCostLog(String runId, double actualCost) {
        try {
            PipelineRun run = stateStore.findRun(runId).orElse(null);
            if (run == null) return;

            double estimatedCost = run.getCostEstimateHigh() != null ? run.getCostEstimateHigh() : 0.0;
            int credits = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
            double hardCap = run.getHardCapUsd() != null ? run.getHardCapUsd() : 0.0;
            boolean budgetHit = hardCap > 0 && actualCost >= hardCap;
            double margin = estimatedCost - actualCost;

            // Count tasks
            List<AgentTask> tasks = stateStore.getAllTasks(runId);
            int taskCount = tasks.size();
            int tasksCompleted = (int) tasks.stream()
                    .filter(t -> "COMPLETE".equals(t.getStatus()))
                    .count();

            RunCostLog logEntry = RunCostLog.builder()
                    .runId(runId)
                    .userId(run.getCreatedByUserId() != null ? run.getCreatedByUserId() : "unknown")
                    .orgId(run.getOrgId())
                    .provider(run.getAiProvider() != null ? run.getAiProvider() : "unknown")
                    .estimatedCost(estimatedCost)
                    .actualCost(actualCost)
                    .hardCap(hardCap)
                    .creditsCharged(credits)
                    .status(run.getStatus() != null ? run.getStatus() : "UNKNOWN")
                    .taskCount(taskCount)
                    .tasksCompleted(tasksCompleted)
                    .budgetHit(budgetHit)
                    .marginUsd(margin)
                    .build();
            runCostLogRepository.save(logEntry);
            log.info("[PipelineHarness] Cost log: run={} estimated=${} actual=${} margin=${} credits={} budgetHit={}",
                    runId, String.format("%.4f", estimatedCost), String.format("%.4f", actualCost),
                    String.format("%.4f", margin), credits, budgetHit);
        } catch (Exception e) {
            log.warn("[PipelineHarness] Could not write cost log for run {}: {}", runId, e.getMessage());
        }
    }

    // ── GitHub push ───────────────────────────────────────────────────────────

    /**
     * After a successful merge, pushes main to GitHub if the run has repo config.
     * - repoMode=NEW  + no repoUrl → creates a new repo first, then pushes.
     * - repoMode=EXISTING or repoUrl set → pushes to the existing remote.
     * Non-fatal: logs a warning but does not fail the run.
     */
    /** Public entry point for manual SCM push from RunController. */
    public void pushToScmManual(PipelineRun run) {
        var scmUser = run.getCreatedByUserId() != null
                ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null) : null;
        pushToScmIfConfigured(run, scmUser);
    }

    /** @deprecated Use {@link #pushToScmManual(PipelineRun)} instead. */
    @Deprecated
    public void pushToGitHubManual(PipelineRun run) {
        pushToScmManual(run);
    }

    /**
     * Route SCM push to the correct provider based on run/user credentials.
     * Bitbucket takes precedence if credentials present on the run; falls back to GitHub.
     */
    private void pushToScmIfConfigured(PipelineRun run, com.navneet.executespec.auth.entity.AppUser user) {
        // Check Bitbucket first (on run, then on user)
        if (StringUtils.hasText(run.getBitbucketUsername()) && StringUtils.hasText(run.getBitbucketWorkspace())) {
            pushToBitbucket(run);
            return;
        }
        if (user != null && StringUtils.hasText(user.getBitbucketUsername()) && StringUtils.hasText(user.getBitbucketWorkspace())) {
            // Capture from user to run
            run.setBitbucketUsername(user.getBitbucketUsername());
            run.setBitbucketAppPassword(user.getBitbucketAppPassword());
            run.setBitbucketWorkspace(user.getBitbucketWorkspace());
            run.setBitbucketRepoSlug(user.getBitbucketRepoSlug());
            run.setScmProvider("BITBUCKET");
            pushToBitbucket(run);
            return;
        }
        // Fallback to GitHub
        pushToGitHubIfConfigured(run);
    }

    private void pushToBitbucket(PipelineRun run) {
        String workspace = run.getBitbucketWorkspace();
        String repoSlug = run.getBitbucketRepoSlug();
        String username = run.getBitbucketUsername();
        String appPassword = run.getBitbucketAppPassword();

        if (!StringUtils.hasText(repoSlug)) {
            log.warn("[{}] Bitbucket workspace set but repo slug missing — skipping push", run.getRunId());
            return;
        }

        try {
            String branchName = "executespec/" + run.getRunId().substring(0, 8);
            String cloneUrl = "https://bitbucket.org/" + workspace + "/" + repoSlug + ".git";

            // Push branch using GitTool
            gitTool.pushToBranchBitbucket(run.getRunId(), cloneUrl, username, appPassword, branchName);

            // Create PR
            String title = StringUtils.hasText(run.getRunTitle()) ? run.getRunTitle() : "ExecuteSpec changes";
            String body = StringUtils.hasText(run.getRunSummary()) ? run.getRunSummary() : run.getUserRequest();
            String prUrl = bitbucketRepoService.createPullRequest(
                workspace, repoSlug, branchName, title, body, username, appPassword);

            if (prUrl != null) {
                run.setScmPrUrl(prUrl);
                run.setScmProvider("BITBUCKET");
                stateStore.save(run);
                log.info("[{}] Bitbucket PR created: {}", run.getRunId(), prUrl);
                broadcaster.emit(run.getRunId(),
                        RunEvent.log(run.getRunId(), "harness", "Bitbucket pull request created: " + prUrl));
            }
        } catch (Exception e) {
            log.warn("[{}] Bitbucket push failed: {}", run.getRunId(), e.getMessage());
        }
    }

    private void pushToGitHubIfConfigured(PipelineRun run) {
        String repoMode = run.getRepoMode();
        String repoUrl  = run.getRepoUrl();

        // Nothing configured — skip silently
        if ((repoMode == null || "NONE".equals(repoMode)) && repoUrl == null) return;

        // Prefer the run's per-user PAT; fall back to system-level PAT
        String pat = (run.getGithubPat() != null && !run.getGithubPat().isBlank())
                ? run.getGithubPat()
                : (props.getGithub() != null ? props.getGithub().getPat() : null);
        String owner = (run.getGithubOwner() != null && !run.getGithubOwner().isBlank())
                ? run.getGithubOwner()
                : (props.getGithub() != null ? props.getGithub().getDefaultOwner() : null);

        if (pat == null || pat.isBlank()) {
            log.warn("GitHub PAT not configured — skipping push for run {}", run.getRunId());
            return;
        }

        String remote = repoUrl;
        if ("NEW".equals(repoMode) && (repoUrl == null || repoUrl.isBlank())) {
            log.info("Creating new GitHub repo for run {} (owner={})", run.getRunId(), owner);
            remote = gitHubRepoService.createRepo(run.getUserRequest(), null, "private", pat, owner);
            if (remote != null) {
                // Reload from DB to avoid overwriting status changes made by markComplete()
                // (the caller's 'run' reference may be stale).
                PipelineRun freshRun = stateStore.findRun(run.getRunId()).orElse(run);
                freshRun.setRepoUrl(remote);
                stateStore.save(freshRun);
                run.setRepoUrl(remote); // keep the caller's reference in sync for downstream use
                log.info("Created GitHub repo: {}", remote);
            }
        }

        if (remote == null || remote.isBlank()) {
            log.warn("No remote URL available — skipping push for run {}", run.getRunId());
            return;
        }

        log.info("Pushing workspace to GitHub: {}", remote);

        if ("EXISTING".equals(repoMode)) {
            // For existing repos: create a branch, push to it, and open a PR
            String branchName = "executespec/" + run.getRunId().substring(0, 8);
            com.navneet.executespec.tool.ToolResult result = gitTool.pushToBranch(run.getRunId(), remote, pat, branchName);
            if (!result.isOk()) {
                log.warn("GitHub branch push failed for run {}: {}", run.getRunId(), result.value());
                return;
            }

            // Determine default branch to target the PR against
            String defaultBranch = gitHubRepoService.getDefaultBranch(remote, pat);

            // Create a PR
            String prTitle = run.getRunTitle() != null ? run.getRunTitle() : "ExecuteSpec changes";
            String prBody = run.getRunSummary() != null ? run.getRunSummary() : run.getUserRequest();
            String prUrl = gitHubRepoService.createPullRequest(remote, branchName, defaultBranch,
                    prTitle, prBody, pat);

            if (prUrl != null) {
                // Store PR URL on the run
                PipelineRun freshRun = stateStore.findRun(run.getRunId()).orElse(run);
                freshRun.setGithubPrUrl(prUrl);
                freshRun.setScmPrUrl(prUrl);
                freshRun.setScmProvider("GITHUB");
                stateStore.save(freshRun);
                run.setGithubPrUrl(prUrl);
                run.setScmPrUrl(prUrl);
                run.setScmProvider("GITHUB");
                log.info("Created PR for run {}: {}", run.getRunId(), prUrl);
                broadcaster.emit(run.getRunId(),
                        RunEvent.log(run.getRunId(), "harness", "Pull request created: " + prUrl));
            }

            gitHubRepoService.updateRepoDescription(remote, run.getUserRequest(), pat);
        } else {
            // For NEW repos: push main directly
            com.navneet.executespec.tool.ToolResult result = gitTool.pushToRemote(run.getRunId(), remote, pat);
            if (!result.isOk()) {
                log.warn("GitHub push failed for run {}: {}", run.getRunId(), result.value());
            }
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
        if (!"PARTIAL".equals(ts) && !"BLOCKED".equals(ts) && !"FAILED".equals(ts)
                && !"PENDING".equals(ts) && !"LOCKED".equals(ts)) {
            throw new IllegalStateException(
                "Task " + taskId + " is not retryable (status: " + ts
                + "). Only PARTIAL, BLOCKED, FAILED, PENDING, or LOCKED tasks can be retried.");
        }

        // ── Special case: generate_openapi_spec is auto-completed by the harness (ApiTestSuiteAgent
        // handles the real spec post-merge). Just re-run the auto-complete and return.
        if (OPENAPI_TASK_ID.equals(taskId)) {
            log.info("Retrying {} — auto-completing (spec generated post-merge) for run {}", OPENAPI_TASK_ID, runId);
            markOpenapiTaskComplete(runId, stateStore.getAllTasks(runId));
            return true;
        }

        String rs = run.getStatus();
        if (!"PARTIAL".equals(rs) && !"FAILED".equals(rs)) {
            throw new IllegalStateException(
                "Run " + runId + " cannot be retried in state " + rs
                + ". Run must be PARTIAL or FAILED before retrying individual tasks.");
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

        // Re-resolve API keys so retries pick up any keys the user may have added since the initial run.
        ResolvedKeys resolvedKeys = apiKeyResolver.resolve(run.getCreatedByUserId());
        runKeyStore.put(runId, resolvedKeys);

        // Update billing flag in case org settings changed since original run
        run.setKeySource(resolvedKeys.keySourceMode());
        stateStore.saveRun(run);

        try {
            // ── 1. Lock + run the retried worker task ──────────────────────
            String branchName = "agent/" + runId.substring(0, 8) + "/" + taskId;
            stateStore.lockTask(taskId, props.getRoles().getWorker(), branchName);
            WorkerReport retryReport = workerPool.executeTask(task, runId);

            broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId,
                "complete".equalsIgnoreCase(retryReport.getStatus())
                    ? "COMPLETE" : retryReport.getStatus().toUpperCase()));

            // ── 2. Auto-run any other incomplete worker tasks ──────────────
            // After one task is retried, check whether other worker tasks are
            // still incomplete (PARTIAL, BLOCKED, FAILED, PENDING, or LOCKED).
            // Reset and run them all before proceeding to reviewer — otherwise
            // the merge gate would open prematurely with missing work.
            List<AgentTask> otherIncomplete = stateStore.getAllTasks(runId).stream()
                .filter(t -> !taskId.equals(t.getTaskId()))
                .filter(t -> !isSystemTask(t))
                .filter(t -> {
                    String s = t.getStatus();
                    return "PARTIAL".equals(s) || "BLOCKED".equals(s) || "FAILED".equals(s)
                        || "PENDING".equals(s) || "LOCKED".equals(s);
                })
                .toList();

            if (!otherIncomplete.isEmpty()) {
                log.info("[Harness] {} additional incomplete task(s) found after retry — re-running: {}",
                    otherIncomplete.size(),
                    otherIncomplete.stream().map(AgentTask::getTaskId).toList());
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                    "⟳ Running " + otherIncomplete.size() + " remaining incomplete task(s)..."));
                // Reset each to PENDING so the worker pool can pick them up
                for (AgentTask t : otherIncomplete) {
                    stateStore.markRetrying(runId, t.getTaskId());
                    broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
                }
                // Reload refreshed task objects (markRetrying clears fields)
                List<AgentTask> toRerun = otherIncomplete.stream()
                    .map(t -> stateStore.getTask(t.getTaskId()))
                    .toList();
                workerPool.runAll(toRerun, runId, props.getRoles().getWorker());
            }

            // Ensure generate_openapi_spec is marked COMPLETE before eligibility checks
            markOpenapiTaskComplete(runId, stateStore.getAllTasks(runId));

            // ── 3. Collect all task reports ────────────────────────────────
            List<AgentTask> allTasks = stateStore.getAllTasks(runId);
            List<WorkerReport> reports = allTasks.stream()
                .filter(t -> t.getWorkerReportJson() != null)
                .map(t -> {
                    try { return mapper.readValue(t.getWorkerReportJson(), WorkerReport.class); }
                    catch (Exception e) { return null; }
                })
                .filter(java.util.Objects::nonNull)
                .toList();

            // ── Tier 8: Compile & Fix (non-fatal, sync before reviewer) ───
            compilerAgent.runCompileAndFix(runId);

            // ── 4. Merge-gate eligibility check ────────────────────────────
            List<AgentTask> workerTasksNow = allTasks.stream()
                .filter(t -> !isSystemTask(t))
                .toList();

            if (!checkTaskCompletionEligibility(workerTasksNow, runId, "after force-retry")) {
                broadcaster.complete(runId);
                return;
            }

            // ── 4b. Cleanup stale worktrees before merge ──────────────
            gitTool.pruneAllWorktrees(runId);

            // ── 5. Merge → Review → Report → Auto-fix → Complete ─────────
            // Delegate to doReviewAndMerge which handles the full post-task flow.
            PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
            doReviewAndMerge(freshRun, false);

        } catch (Exception e) {
            log.error("Force-retry of task {} in run {} failed: {}", taskId, runId, e.getMessage(), e);
            stateStore.markFailed(runId, "Force-retry failed: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
        } finally {
            broadcaster.complete(runId);
            runKeyStore.remove(runId);  // clean up per-run API keys
            runCostBudgetService.remove(runId);
        }
    }

    // ── Tier 5: Run restart ───────────────────────────────────────────────────

    /** Records E2E duration for failed runs (for SLO tracking). */
    @EventListener
    public void onRunFailed(PipelineEvent.RunFailed event) {
        recordE2eDuration(event.runId(), "failed");
    }

    /**
     * Handles restart requests published by {@link com.navneet.executespec.recovery.RunRecoveryService}.
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
            event.repoMode(),
            null, null   // github credentials not available for auto-restarts
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
        // Pass ownership INTO submitRun so it's stamped BEFORE the async thread starts
        PipelineRun newRun = submitRun(
            original.getUserRequest(),
            original.getSourceType() != null ? original.getSourceType() : "RECOVERY",
            original.getRepoUrl(),
            original.getRepoMode(),
            original.getGithubPat(), original.getGithubOwner(),
            original.isSkipApproval(), original.isAutoMerge(),
            original.getPlannerModel(),
            original.getAiProvider(),
            original.getCreatedByUserId(),
            original.getOrgId(),
            original.getProjectId(),
            original.getExecutionMode()
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
     * <p>Called by {@link com.navneet.executespec.recovery.RunRecoveryService#autoRestartIfTransient}
     * when a run fails due to transient provider failures (rate-limits, outages, etc.).
     *
     * @param original the failed run whose goal is being retried
     * @return the new run ID
     */
    public String restartRunWithCount(PipelineRun original) {
        int newCount = original.getAutoRestartCount() + 1;
        log.info("Auto-restarting run {} due to transient failure (restart #{})",
            original.getRunId(), newCount);
        // Pass ownership INTO submitRun so it's stamped BEFORE the async thread starts
        PipelineRun newRun = submitRun(
            original.getUserRequest(),
            original.getSourceType() != null ? original.getSourceType() : "RECOVERY",
            original.getRepoUrl(),
            original.getRepoMode(),
            original.getGithubPat(), original.getGithubOwner(),
            original.isSkipApproval(), original.isAutoMerge(),
            original.getPlannerModel(),
            original.getAiProvider(),
            original.getCreatedByUserId(),
            original.getOrgId(),
            original.getProjectId(),
            original.getExecutionMode()
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
        // skipApproval passed directly into submitRun so it's set before the async thread starts
        // Integration runs also auto-merge when skipApproval is true (fully automated)
        return submitRun(userRequest, source, null, null, null, null, skipApproval, skipApproval, null, null, null, null, null, "PRODUCTION");
    }

    // ── File-ownership validation ────────────────────────────────────────────

    /**
     * Validates that no two tasks claim the same file in their owned_files lists.
     * If conflicts are detected, auto-injects dependencies to serialize the conflicting tasks
     * (the task appearing later in the plan depends on the earlier one).
     * This is non-fatal — logs warnings and auto-corrects.
     */
    private void validateFileOwnership(List<AgentTask> workerTasks, String runId) {
        Map<String, List<String>> fileToOwners = new java.util.HashMap<>();
        Map<String, com.navneet.executespec.agent.model.TaskDefinition> taskDefs = new java.util.HashMap<>();

        for (AgentTask wt : workerTasks) {
            try {
                com.navneet.executespec.agent.model.TaskDefinition def =
                    mapper.readValue(wt.getTaskJson(), com.navneet.executespec.agent.model.TaskDefinition.class);
                taskDefs.put(wt.getTaskId(), def);
                if (def.getOwnedFiles() != null) {
                    for (String file : def.getOwnedFiles()) {
                        fileToOwners.computeIfAbsent(file, k -> new java.util.ArrayList<>())
                                    .add(wt.getTaskId());
                    }
                }
            } catch (JsonProcessingException ignore) { /* non-fatal */ }
        }

        List<String> conflicts = fileToOwners.entrySet().stream()
            .filter(e -> e.getValue().size() > 1)
            .map(e -> e.getKey() + " claimed by: " + e.getValue())
            .toList();

        if (!conflicts.isEmpty()) {
            log.warn("File ownership conflicts detected in plan for run {}: {}", runId, conflicts);
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "⚠️ File ownership overlap detected: " + String.join("; ", conflicts)
                + " — tasks with shared files will run sequentially."));
        }
    }

    // ── Extracted pipeline phase helpers ─────────────────────────────────────

    /**
     * Initialises the run's isolated workspace directory. Handles three modes:
     * iteration (copy from source), existing repo (clone), or fresh workspace.
     * Also performs language validation for existing/iteration repos.
     *
     * @return {@code true} if workspace is ready; {@code false} if the run was
     *         marked FAILED and the caller should return immediately.
     */
    private boolean initializeWorkspace(String runId, PipelineRun run) {
        if (run.getSourceRunId() != null && !run.getSourceRunId().isBlank()) {
            try {
                log.info("Copying workspace from source run {} to iteration run {}",
                    run.getSourceRunId(), runId);
                workspaceInitService.copyFromRun(run.getSourceRunId(), runId);
            } catch (Exception copyEx) {
                stateStore.markFailed(runId, "Failed to copy source workspace: " + copyEx.getMessage());
                log.error("Workspace copy failed for iteration run {} (source={}): {}",
                    runId, run.getSourceRunId(), copyEx.getMessage());
                return false;
            }
        } else if ("EXISTING".equals(run.getRepoMode())
                && run.getRepoUrl() != null && !run.getRepoUrl().isBlank()) {
            try {
                workspaceInitService.cloneFromRepo(runId, run.getRepoUrl(), run.getGithubPat());
            } catch (Exception cloneEx) {
                stateStore.markFailed(runId, "Failed to clone source repo: " + cloneEx.getMessage());
                log.error("Clone failed for run {} from {}: {}", runId, run.getRepoUrl(), cloneEx.getMessage());
                return false;
            }
        } else {
            workspaceInitService.ensureRunWorkspace(runId);
        }

        // ── Language validation for existing repos ──────────────────────────
        // Detect whether the cloned workspace uses a supported language.
        // Fail fast with a clear message if only unsupported languages are found.
        if ("EXISTING".equals(run.getRepoMode()) || (run.getSourceRunId() != null && !run.getSourceRunId().isBlank())) {
            Path workspace = workspaceInitService.runWorkspacePath(runId);
            String langError = languageValidator.detectAndValidateLanguage(workspace);
            if (langError != null) {
                log.warn("Language validation failed for run {}: {}", runId, langError);
                stateStore.markFailed(runId, langError);
                broadcaster.emit(runId, RunEvent.log(runId, "harness", langError));
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                return false;
            }
        }
        return true;
    }

    /**
     * Detects JIRA/Confluence references in the user request, optionally pausing
     * to ask for clarification. Returns the enriched request text, or {@code null}
     * if the pipeline was interrupted during the clarification gate (caller should return).
     *
     * @throws InterruptedException if interrupted while waiting at the clarification gate
     */
    private String resolveContextReferences(String runId, PipelineRun run, String userRequest)
            throws InterruptedException {
        String effectiveRequest = userRequest;
        try {
            var extraction = referenceExtractorService.extractAndEnrich(userRequest, run.getOrgId());

            if (extraction.clarificationNeeded()) {
                // Pause pipeline — ask user for clarification
                broadcaster.emit(runId, RunEvent.runStatus(runId, "AWAITING_CLARIFICATION"));
                String clarificationGateId = stateStore.recordGateOpened(runId, "CONTEXT_CLARIFICATION");
                broadcaster.emit(runId, RunEvent.clarificationNeeded(runId, extraction.clarificationPrompt()));

                log.info("[PipelineHarness] Run {} entering clarification gate", runId);
                runSlots.release();
                String clarificationResponse;
                try {
                    clarificationResponse = gateStore.awaitClarificationGate(runId);
                } finally {
                    try { runSlots.acquire(); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
                log.info("[PipelineHarness] Run {} clarification received", runId);
                stateStore.recordGateResolved(clarificationGateId, true);
                broadcaster.emit(runId, RunEvent.clarificationResolved(runId));

                // Re-extract with the clarification response
                if (clarificationResponse != null && !clarificationResponse.isBlank()) {
                    var enriched = referenceExtractorService.extractFromClarification(
                            userRequest, clarificationResponse, run.getOrgId());
                    effectiveRequest = enriched.enrichedRequest();
                    if (!enriched.extractedRefs().isEmpty()) {
                        String refSummary = enriched.extractedRefs().stream()
                                .map(r -> r.type() + ":" + r.identifier())
                                .reduce((a, b) -> a + ", " + b).orElse("");
                        broadcaster.emit(runId, RunEvent.contextExtracted(runId, refSummary));
                    }
                }
                broadcaster.emit(runId, RunEvent.runStatus(runId, "PLANNING"));

            } else if (!extraction.extractedRefs().isEmpty()) {
                // Concrete references found — enrich and continue
                effectiveRequest = extraction.enrichedRequest();
                String refSummary = extraction.extractedRefs().stream()
                        .map(r -> r.type() + ":" + r.identifier())
                        .reduce((a, b) -> a + ", " + b).orElse("");
                broadcaster.emit(runId, RunEvent.contextExtracted(runId, refSummary));
                log.info("[RefExtractor] Enriched run {} with: {}", runId, refSummary);
            }
        } catch (InterruptedException ie) {
            throw ie; // propagate to caller
        } catch (Exception e) {
            // Non-fatal — log and continue with original request
            log.warn("[RefExtractor] Failed to extract references for run {}: {}", runId, e.getMessage());
        }
        return effectiveRequest;
    }

    /**
     * Checks whether all worker tasks have completed, marking the run FAILED or PARTIAL
     * as appropriate. Used by {@link #runResumeAsync}, {@link #runRetryAsync}, and
     * the main pipeline in {@link #executeRunAsync}.
     *
     * @param workerTasks the non-system tasks to check
     * @param runId       the run ID
     * @param context     a human-readable context string for log messages (e.g. "after resume", "after force-retry")
     * @return {@code true} if all tasks are COMPLETE and execution should continue;
     *         {@code false} if the run was marked FAILED or PARTIAL and the caller should return.
     */
    private boolean checkTaskCompletionEligibility(List<AgentTask> workerTasks, String runId, String context) {
        boolean allBlocked = workerTasks.stream()
            .allMatch(t -> "BLOCKED".equals(t.getStatus()) || "FAILED".equals(t.getStatus()));
        if (allBlocked) {
            stateStore.markFailed(runId, "All tasks blocked " + context);
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
            return false;
        }

        boolean allComplete = workerTasks.stream()
            .allMatch(t -> "COMPLETE".equals(t.getStatus()));
        if (!allComplete) {
            List<String> stillIncomplete = workerTasks.stream()
                .filter(t -> !"COMPLETE".equals(t.getStatus()))
                .map(t -> t.getTitle() + " [" + t.getStatus() + "]")
                .toList();
            String partialReason = "Incomplete tasks: " + String.join("; ", stillIncomplete);
            log.warn("Run {} still has incomplete tasks {} — {}", runId, context, partialReason);
            broadcaster.emit(runId, RunEvent.log(runId, "harness",
                "⚠️ Some tasks still incomplete:\n  • " + String.join("\n  • ", stillIncomplete)
                + "\n\nRun marked PARTIAL. Use 'Retry Task' on remaining incomplete tasks."));
            stateStore.markPartial(runId, partialReason);
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            return false;
        }
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void initAgentContext(String runId, String planJson) {
        fsTool.writeFile(runId, ".agent-context/task-assignments.json", planJson);
        fsTool.writeFile(runId, ".agent-context/file-locks.json", "{}");
        fsTool.writeFile(runId, ".agent-context/shared-contracts.json", "{}");

        // Write planner-declared project type for preview/deploy detection
        String projectType = "UNKNOWN";
        try {
            var node = mapper.readTree(planJson);
            String declared = node.path("project_type").asText(null);
            if (declared != null && !declared.isBlank()) {
                projectType = declared;
            }
        } catch (Exception e) {
            log.debug("Could not extract project_type from plan JSON: {}", e.getMessage());
        }
        fsTool.writeFile(runId, ".agent-context/project.json",
                String.format("{\"project_type\": \"%s\"}", projectType));
        fsTool.writeFile(runId, ".agent-context/CODEBASE_CONTEXT.md", """
            # Workspace Context

            This is a **fresh, isolated workspace** for this agent run. No project exists yet —
            create all files from scratch unless the task says otherwise.

            ## Environment

            - Git is initialised on branch `main` with one empty commit.
            - You are on your own feature branch (shown in your task assignment).
            - The git identity is pre-configured — `git_commit` will work immediately.
            - Available build tools: `mvn` (Maven 3.x), `npm`, `python`.
            - Java version: 21. Spring Boot: 3.3.x (use for Java/Spring projects).

            ## Coding conventions (Java / Spring Boot)

            - Package root: `com.example` (match pom.xml groupId if one exists).
            - Use Java 21 features: records, sealed classes, pattern matching, `var`.
            - Use Lombok: `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`.
            - SLF4J for all logging — no `System.out.println`.
            - REST controllers: `@RestController`, `@RequestMapping`.
            - Tests: JUnit 5 + `@SpringBootTest`.

            ## Git workflow

            - Commit after every file write: git_commit with message "feat(task_id): ..."
            - Do NOT run `git push` — the harness handles pushing.

            ## Constraints

            - Do NOT touch files owned by other tasks (check file-locks.json).
            - Do NOT add dependencies unless the task explicitly requires it.
            - Updating README.md is mandatory before reporting DONE.
            """);
        // Commit .agent-context/ to main so worktrees inherit it.
        // Without this, `git worktree add -b <branch> main` checks out main
        // which has no .agent-context/, causing workers to fail reading file-locks.json.
        gitTool.commitIfDirty(runId, "chore: init agent context");
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
        // Prefer live pool queue depth for accurate real-time position
        int poolQueued = providerRunPoolManager.totalQueuedRuns();
        if (poolQueued > 0) return poolQueued;
        // Fallback to DB count (handles queued runs from previous instance restarts)
        return (int) stateStore.listRuns("QUEUED",
            org.springframework.data.domain.PageRequest.of(0, 1000)).getTotalElements();
    }

    /**
     * Returns the set of task IDs that have unfixed CRITICAL issues.
     * V2 review has a flat issue list (no per-task breakdown), so when unfixed
     * CRITICAL issues exist, all non-system task IDs are returned for rework.
     * Returns empty set when no unfixed CRITICAL issues remain.
     */
    private Set<String> getFailedTaskIds(ReviewReportV2 review, String runId) {
        boolean hasUnfixedCritical = review.getIssues() != null && review.getIssues().stream()
            .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
            .anyMatch(i -> !i.isAutoFixed() || !i.isFixSucceeded());

        if (!hasUnfixedCritical) return Set.of();

        // V2 flat issues can't be mapped to specific tasks — re-run all
        return stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .map(AgentTask::getTaskId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Builds a human-readable remediation feedback string from a V2 review report.
     * V2 uses a flat issue list (no per-task breakdown), so all unfixed CRITICAL
     * issues are included as context for the worker to fix.
     */
    private String buildRemediationFeedback(ReviewReportV2 review, String taskId) {
        StringBuilder sb = new StringBuilder();

        if (review.getSummary() != null && !review.getSummary().isBlank()) {
            sb.append("Overall: ").append(review.getSummary()).append("\n");
        }

        if (review.getMergeBlockers() != null && !review.getMergeBlockers().isEmpty()) {
            sb.append("\nMerge blockers (MUST FIX):\n");
            review.getMergeBlockers().forEach(b -> sb.append("  - ").append(b).append("\n"));
        }

        if (review.getIssues() != null) {
            List<ReviewIssue> unfixed = review.getIssues().stream()
                .filter(i -> i.getSeverity() == IssueSeverity.CRITICAL)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .toList();
            if (!unfixed.isEmpty()) {
                sb.append("\nUnresolved issues:\n");
                for (ReviewIssue issue : unfixed) {
                    String loc = issue.getFile() != null ? " [" + issue.getFile() + "]" : "";
                    sb.append("  - [").append(issue.getSeverity()).append("]")
                      .append(loc).append(" ").append(issue.getDescription()).append("\n");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * Implements post-merge suggestions for a run.
     *
     * <p>Identifies tasks that the reviewer flagged with MEDIUM or LOW severity issues,
     * injects suggestion feedback into each task's definition, re-runs those workers,
     * runs the compiler agent, and checks whether all tasks completed successfully.
     *
     * @return {@code true} if all targeted tasks completed (caller should re-review and
     *         re-open the merge gate); {@code false} if any task failed (run is already
     *         marked PARTIAL with a "Suggestions implementation failed" reason).
     */
    private boolean doSuggestionImplementation(PipelineRun run) {
        String runId = run.getRunId();

        // Load the latest review from DB
        PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
        if (freshRun.getReviewJson() == null || freshRun.getReviewJson().isBlank()) {
            log.warn("[Suggestions] No reviewJson for run {} — skipping suggestion implementation", runId);
            return true; // nothing to do
        }
        ReviewReportV2 review = parseJson(freshRun.getReviewJson(), ReviewReportV2.class);

        Set<String> taskIds = getSuggestionTaskIds(review, runId);
        if (taskIds.isEmpty()) {
            log.info("[Suggestions] No tasks with MEDIUM/LOW issues in run {} — nothing to implement", runId);
            return true;
        }

        int suggCount = review.getPostMergeSuggestions() != null ? review.getPostMergeSuggestions().size() : 0;
        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "🔧 Implementing " + suggCount + " suggestion(s) across " + taskIds.size() + " task(s)…"));

        List<AgentTask> toRerun = stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .filter(t -> taskIds.contains(t.getTaskId()))
            .toList();

        for (AgentTask t : toRerun) {
            String feedback = buildSuggestionFeedback(review, t.getTaskId());
            stateStore.markRetryingWithFeedback(runId, t.getTaskId(), feedback);
            broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
        }

        // Reload refreshed task objects (markRetryingWithFeedback resets status/timestamps)
        List<AgentTask> refreshed = toRerun.stream()
            .map(t -> stateStore.getTask(t.getTaskId()))
            .toList();
        workerPool.runAll(refreshed, runId, props.getRoles().getWorker());
        compilerAgent.runCompileAndFix(runId);

        // If any task still failed, mark PARTIAL so user can retry individually
        boolean anyFailed = stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .anyMatch(t -> "FAILED".equals(t.getStatus()) || "PARTIAL".equals(t.getStatus()));

        if (anyFailed) {
            String reason = "Suggestions implementation failed — some tasks could not apply the changes. "
                + "Use 'Retry Implementation' or retry individual failed tasks.";
            stateStore.markPartial(runId, reason);
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + reason));
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
            log.warn("[Suggestions] Implementation failed for run {} — marked PARTIAL", runId);
            return false;
        }

        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "✅ Suggestions implemented successfully — re-running reviewer…"));
        return true;
    }

    /**
     * Implements reviewer rework for a run.
     *
     * <p>Identifies tasks that the reviewer flagged with HIGH severity issues or failed
     * acceptance criteria, injects reviewer feedback into each task's definition,
     * re-runs those workers, runs the compiler agent, and checks whether all tasks
     * completed successfully.
     *
     * <p>Unlike the removed auto-REWORK loop, this method runs exactly once per user
     * click of the "Rework" button at the merge gate.
     *
     * @param run    the pipeline run
     * @param review the review report (may be null — will load from DB)
     * @return {@code true} if all targeted tasks completed (caller should re-review);
     *         {@code false} if any task failed (run is already marked PARTIAL).
     */
    private boolean doReworkImplementation(PipelineRun run, ReviewReportV2 review) {
        String runId = run.getRunId();

        // Load review from DB if not passed (e.g. called from a REST endpoint context)
        if (review == null) {
            PipelineRun freshRun = stateStore.findRun(runId).orElse(run);
            if (freshRun.getReviewJson() == null || freshRun.getReviewJson().isBlank()) {
                log.warn("[Rework] No reviewJson for run {} — nothing to rework", runId);
                return true;
            }
            review = parseJson(freshRun.getReviewJson(), ReviewReportV2.class);
        }

        Set<String> failedTaskIds = getFailedTaskIds(review, runId);
        if (failedTaskIds.isEmpty()) {
            log.info("[Rework] No tasks with HIGH/failed acceptance in run {} — nothing to rework", runId);
            return true;
        }

        broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "🔄 Reworking " + failedTaskIds.size() + " task(s) based on reviewer feedback…"));

        List<AgentTask> toRerun = stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .filter(t -> failedTaskIds.contains(t.getTaskId()))
            .toList();

        for (AgentTask t : toRerun) {
            String feedback = buildRemediationFeedback(review, t.getTaskId());
            stateStore.markRetryingWithFeedback(runId, t.getTaskId(), feedback);
            broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "PENDING"));
        }

        // Reload refreshed task objects (markRetryingWithFeedback resets status/timestamps)
        List<AgentTask> refreshed = toRerun.stream()
            .map(t -> stateStore.getTask(t.getTaskId()))
            .toList();
        workerPool.runAll(refreshed, runId, props.getRoles().getWorker());
        compilerAgent.runCompileAndFix(runId);

        // If any task still failed, mark PARTIAL so user can retry individually
        boolean anyFailed = stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .anyMatch(t -> "FAILED".equals(t.getStatus()) || "PARTIAL".equals(t.getStatus()));

        if (anyFailed) {
            String reason = "Rework failed — some tasks could not apply the fixes. "
                + "Use 'Retry Task' or 'Reopen Merge Gate' when ready.";
            stateStore.markPartial(runId, reason);
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "⚠️ " + reason));
            broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL"));
            eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, reason));
            log.warn("[Rework] Implementation failed for run {} — marked PARTIAL", runId);
            return false;
        }

        broadcaster.emit(runId, RunEvent.log(runId, "harness",
            "✅ Rework completed — re-running reviewer…"));
        return true;
    }

    /**
     * Returns all non-system task IDs if unfixed MINOR issues exist in the V2 review.
     * V2 uses a flat issue list (no per-task breakdown).
     */
    private Set<String> getSuggestionTaskIds(ReviewReportV2 review, String runId) {
        boolean hasUnfixedMinor = review.getIssues() != null && review.getIssues().stream()
            .filter(i -> i.getSeverity() == IssueSeverity.MINOR)
            .anyMatch(i -> !i.isAutoFixed() || !i.isFixSucceeded());

        if (!hasUnfixedMinor) return Set.of();

        return stateStore.getAllTasks(runId).stream()
            .filter(t -> !isSystemTask(t))
            .map(AgentTask::getTaskId)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Builds suggestion feedback from V2 review: global post-merge suggestions
     * plus all unfixed MINOR issues. V2 uses a flat issue list (no per-task breakdown).
     */
    private String buildSuggestionFeedback(ReviewReportV2 review, String taskId) {
        StringBuilder sb = new StringBuilder();

        if (review.getPostMergeSuggestions() != null && !review.getPostMergeSuggestions().isEmpty()) {
            sb.append("Post-merge suggestions to address:\n");
            review.getPostMergeSuggestions().forEach(s -> sb.append("  - ").append(s).append("\n"));
        }

        if (review.getIssues() != null) {
            List<ReviewIssue> unfixedMinor = review.getIssues().stream()
                .filter(i -> i.getSeverity() == IssueSeverity.MINOR)
                .filter(i -> !i.isAutoFixed() || !i.isFixSucceeded())
                .toList();
            if (!unfixedMinor.isEmpty()) {
                sb.append("\nImprovements to make:\n");
                for (ReviewIssue issue : unfixedMinor) {
                    String loc = issue.getFile() != null ? " [" + issue.getFile() + "]" : "";
                    sb.append("  - [").append(issue.getSeverity()).append("]")
                      .append(loc).append(" ").append(issue.getDescription()).append("\n");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * Returns {@code true} for legacy system tasks that must be excluded from the
     * worker task pool and partial-completion checks.
     */
    private boolean isSystemTask(com.navneet.executespec.state.entity.AgentTask t) {
        return OPENAPI_TASK_ID.equals(t.getTaskId())
            || "generate_preview".equals(t.getTaskId()); // legacy plans in DB
    }

    /**
     * Auto-completes the {@code generate_openapi_spec} task (if present) so it does not
     * block partial-completion checks or appear as incomplete to the reviewer.
     *
     * <p>The actual spec is generated post-merge by ApiTestSuiteAgent.  This is called
     * immediately after {@code workerPool.runAll()} so the task moves to COMPLETE before
     * the all-tasks-complete gate check.
     */
    private void markOpenapiTaskComplete(String runId, List<com.navneet.executespec.state.entity.AgentTask> tasks) {
        tasks.stream()
            .filter(t -> OPENAPI_TASK_ID.equals(t.getTaskId()))
            .findFirst()
            .ifPresent(t -> {
                String model  = t.getAssignedModel() != null ? t.getAssignedModel() : modelRegistry.workhouseModelFor("anthropic");
                String branch = "agent/" + runId.substring(0, 8) + "/" + OPENAPI_TASK_ID;
                if (!stateStore.lockTask(t.getTaskId(), model, branch)) {
                    // Already locked (may be a retry) — still ensure it is COMPLETE
                    log.debug("[Harness] {} already locked for run {} — skipping auto-complete lock",
                        OPENAPI_TASK_ID, runId);
                }
                WorkerReport report = WorkerReport.builder()
                    .taskId(OPENAPI_TASK_ID)
                    .status("complete")
                    .filesCreated(List.of())
                    .filesModified(List.of())
                    .filesRead(List.of())
                    .selfReview("OpenAPI spec is auto-generated post-merge by ApiTestSuiteAgent. No worker action needed.")
                    .build();
                stateStore.completeTask(t.getTaskId(), report);
                broadcaster.emit(runId, RunEvent.taskStatus(runId, t.getTaskId(), "COMPLETE"));
                log.info("[Harness] {} auto-completed for run {} (spec generated post-merge)", OPENAPI_TASK_ID, runId);
            });
    }

    /**
     * Clears {@code currentGate} on a run immediately (synchronously), so the UI
     * banner disappears on the next poll without waiting for the async pipeline thread.
     * Both {@link #resolvePlanGate} and {@link #resolveMergeGate} share this fix.
     */
    private void clearGateSync(String runId) {
        // Atomic JPQL update — avoids full entity save that could overwrite concurrent field changes
        stateStore.clearCurrentGate(runId);
    }

    /**
     * Generates a validation report for the run (non-fatal).
     * Called after review completes and before the merge gate opens.
     * Waits up to 3 minutes for test execution to finish so test results
     * are included in the report.
     */
    private void generateValidationReport(String runId, PipelineRun run,
                                           List<WorkerReport> reports,
                                           ReviewReportV2 review,
                                           CompileResult compileResult) {
        generateValidationReport(runId, run, reports, review, compileResult, false);
    }

    private void generateValidationReport(String runId, PipelineRun run,
                                           List<WorkerReport> reports,
                                           ReviewReportV2 review,
                                           CompileResult compileResult,
                                           boolean afterImprovements) {
        Observation reportObs = Observation.createNotStarted("executespec.run.phase.report", observationRegistry)
            .lowCardinalityKeyValue("run.id", runId)
            .start();
        try (Observation.Scope reportScope = reportObs.openScope()) {
            // Test results are decoupled from validation report — tests run in
            // background and their report is generated independently when complete.
            // Clean up the future reference so it doesn't leak.
            testCompletionFutures.invalidate(runId);

            ReportInput input = new ReportInput(run, reports, review, compileResult,
                    stateStore.getAllTasks(runId), null, List.of(), afterImprovements);

            // Attempt report generation with one retry — report is required before merge gate
            Optional<com.navneet.executespec.report.entity.ValidationReport> reportResult = Optional.empty();
            for (int attempt = 1; attempt <= 2; attempt++) {
                reportResult = validationReportService.generateAndPersist(runId, input);
                if (reportResult.isPresent()) {
                    broadcaster.emit(runId, RunEvent.log(runId, "report", "✅ Validation report generated"));
                    break;
                }
                if (attempt < 2) {
                    log.warn("Validation report generation failed for run {} — retrying (attempt {}/2)", runId, attempt);
                    broadcaster.emit(runId, RunEvent.log(runId, "report",
                            "⚠️ Report generation failed — retrying..."));
                    try { Thread.sleep(3000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    log.error("Validation report generation failed after 2 attempts for run {} — proceeding to merge gate without report", runId);
                    broadcaster.emit(runId, RunEvent.log(runId, "report",
                            "⚠️ Report generation failed after retry — merge gate opened without validation report"));
                }
            }
        } catch (Exception e) {
            reportObs.error(e);
            log.error("Validation report generation crashed for run {} — continuing to merge gate", runId, e);
            broadcaster.emit(runId, RunEvent.log(runId, "report",
                    "⚠️ Report generation error — merge gate opened without validation report"));
        } finally {
            reportObs.stop();
        }
    }

    // ── Auto-start preview for test execution ──────────────────────────────

    /** Starts preview in a virtual thread. Public for watchdog recovery. */
    public void autoStartPreview(String runId, PipelineRun run) {
        Thread.ofVirtual().name("auto-preview-" + runId).start(() -> {
            try {
                // Look up the run creator to pass to PreviewService
                var user = run.getCreatedByUserId() != null
                        ? appUserRepository.findById(run.getCreatedByUserId()).orElse(null)
                        : null;
                if (user == null) {
                    log.warn("[AutoPreview] No user found for run {} — skipping auto-preview", runId);
                    testPreviewCoordinator.cancelIfPending(runId);
                    return;
                }

                log.info("[AutoPreview] Starting preview for run {} (for test execution)", runId);
                broadcaster.emit(runId, RunEvent.log(runId, null,
                        "Starting preview automatically for integration test execution..."));
                previewService.start(runId, user);
                // PreviewService will call testPreviewCoordinator.onPreviewReady() when ready
            } catch (Exception e) {
                log.warn("[AutoPreview] Failed to auto-start preview for run {}: {}", runId, e.getMessage());
                broadcaster.emit(runId, RunEvent.log(runId, null,
                        "⚠️ Auto-preview failed: " + e.getMessage() + " — skipping integration tests"));
                // Immediately release the test coordinator so report generation isn't blocked
                // by the 10-minute safety timeout waiting for a preview that will never arrive.
                testPreviewCoordinator.cancelIfPending(runId);
            }
        });
    }

    // ── Test execution completion listener ───────────────────────────────────

    @EventListener
    public void onTestExecutionCompleted(TestExecutionCompletedEvent event) {
        CompletableFuture<TestExecutionSummary> future = testCompletionFutures.getIfPresent(event.runId());
        if (future != null) {
            future.complete(event.summary());
            log.info("Run {} — test execution completed, report generation unblocked", event.runId());
        }
        // Preview stays running for the user — TTL-based cleanup handles shutdown
    }

    // ── Batch completion listener — resumes pipeline from compile step ────

    @EventListener
    @org.springframework.scheduling.annotation.Async
    public void onBatchComplete(com.navneet.executespec.batch.BatchResumeService.BatchCompleteEvent event) {
        String runId = event.runId();
        log.info("[Run {}] Batch complete event received — resuming pipeline", runId);
        PipelineRun run = stateStore.findRun(runId).orElse(null);
        if (run == null) {
            log.error("[Run {}] Run not found for batch resume", runId);
            return;
        }

        // C1 fix: guard against cancel-vs-complete race — if the run reached a terminal
        // state (e.g. user cancelled) between batch poll and this async handler, bail out
        if (List.of("CANCELLED", "FAILED", "COMPLETE", "PAUSED").contains(run.getStatus())) {
            log.info("[Run {}] Run already terminal ({}) — skipping batch resume", runId, run.getStatus());
            return;
        }

        // H2 fix: acquire run semaphore so batch resume respects concurrency limits
        boolean acquired = false;
        try {
            acquired = runSlots.tryAcquire();
            if (!acquired) {
                log.warn("[Run {}] No run slots available for batch resume — waiting", runId);
                runSlots.acquire();
                acquired = true;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[Run {}] Interrupted while waiting for run slot — aborting batch resume", runId);
            return;
        }

        // Route through provider pool after acquiring global slot
        String batchUserTier = "FREE";
        try {
            batchUserTier = tierEnforcementService.resolveUserTier(run.getCreatedByUserId());
        } catch (Exception e) {
            log.debug("[Run {}] Could not resolve tier for batch resume — defaulting to FREE", runId);
        }
        try {
            final boolean slotAcquired = acquired;
            final PipelineRun batchRun = run;
            final String batchTier = batchUserTier;
            dispatchToProviderPool(run.getAiProvider(), runId, batchTier, () -> {
                runBatchResumeInPool(runId, batchRun, slotAcquired);
            });
            return; // pool dispatch handles the rest
        } catch (ProviderRunPool.ProviderQueueFullException e) {
            if (acquired) runSlots.release();
            log.warn("[Run {}] Batch resume rejected — provider pool full: {}", runId, e.getMessage());
            stateStore.markFailed(runId, "Batch resume failed: provider queue full — " + e.getMessage());
            return;
        }

    }

    /** Executes the batch resume pipeline body inside a provider pool slot. */
    private void runBatchResumeInPool(String runId, PipelineRun run, boolean slotAcquired) {
        // H3 fix: register thread so cancelRun() can interrupt batch resume operations
        pipelineThreads.put(runId, Thread.currentThread());

        try {
            // Initialize workspace + agent context if needed (batch runs may not have it set up)
            String workspacePath = run.getWorkspacePath();
            if (workspacePath != null) {
                try {
                    TaskPlan plan = mapper.readValue(run.getPlanJson(), TaskPlan.class);
                    initAgentContext(runId, run.getPlanJson());
                } catch (Exception e) {
                    log.debug("[Run {}] Could not init agent context for batch resume: {}", runId, e.getMessage());
                }
            }

            // Auto-retry failed batch tasks in real-time mode
            List<AgentTask> tasksAfterBatch = stateStore.getAllTasks(runId);
            Set<String> decomposedParentIds = tasksAfterBatch.stream()
                    .filter(t -> t.getParentTaskId() != null)
                    .map(AgentTask::getParentTaskId)
                    .collect(Collectors.toSet());
            List<AgentTask> failedTasks = tasksAfterBatch.stream()
                    .filter(t -> "FAILED".equals(t.getStatus()))
                    .filter(t -> !decomposedParentIds.contains(t.getTaskId()))
                    .toList();

            if (!failedTasks.isEmpty()) {
                log.info("[Run {}] Retrying {} failed batch tasks in real-time mode", runId, failedTasks.size());
                broadcaster.emit(runId, RunEvent.log(runId, "harness",
                        "Retrying " + failedTasks.size() + " failed batch task(s) in real-time..."));
                for (AgentTask ft : failedTasks) {
                    stateStore.resetTaskForRetry(ft.getTaskId());
                }
                // Re-fetch tasks after reset so executor sees PENDING status
                List<AgentTask> retryTasks = stateStore.getAllTasks(runId).stream()
                        .filter(t -> failedTasks.stream().anyMatch(f -> f.getTaskId().equals(t.getTaskId())))
                        .toList();
                try {
                    sequentialTaskExecutor.execute(run, retryTasks);
                    log.info("[Run {}] Real-time retry completed for {} tasks", runId, retryTasks.size());
                } catch (Exception e) {
                    log.warn("[Run {}] Real-time retry failed: {}", runId, e.getMessage());
                }
            }

            // Compile & Fix
            broadcaster.emit(runId, RunEvent.runStatus(runId, "COMPILING"));
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "Compile & fix pass (post-batch)..."));
            CompileResult compileResult = null;
            try {
                compileResult = compilerAgent.runCompileAndFix(runId);
            } catch (Exception e) {
                log.warn("[Run {}] Post-batch compile failed: {}", runId, e.getMessage());
            }

            // Check if any tasks completed
            List<AgentTask> allTasks = stateStore.getAllTasks(runId);
            boolean anyComplete = allTasks.stream().anyMatch(t -> "COMPLETE".equals(t.getStatus()));
            if (!anyComplete) {
                stateStore.markFailed(runId, "No batch tasks completed successfully");
                broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
                return;
            }

            // Auto-merge all branches
            broadcaster.emit(runId, RunEvent.log(runId, "harness", "Merging batch task branches..."));
            try {
                mergeService.mergeAll(runId, allTasks);
            } catch (Exception e) {
                log.warn("[Run {}] Post-batch merge failed: {}", runId, e.getMessage());
            }

            // Mark COMPLETE
            stateStore.markComplete(runId);
            broadcaster.emit(runId, RunEvent.complete(runId, "COMPLETE"));
            eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));

            // Background validation (review, test gen, preview) — same as real-time runs
            final CompileResult finalCompileResult = compileResult;
            backgroundValidationService.create(runId);
            final PipelineRun bgRun = stateStore.findRun(runId).orElse(run);
            var bgSnapshot = ContextSnapshotFactory.builder().build().captureAll();
            Thread.ofVirtual().name("bg-validation-batch-" + runId).start(
                    bgSnapshot.wrap(() -> {
                        try {
                            runBackgroundValidation(runId, bgRun, List.of(),
                                    bgRun.getPlanJson(), bgRun.getUserRequest(),
                                    finalCompileResult, false, true);
                        } finally {
                            // cleanup is handled inside runBackgroundValidation's finally block
                            broadcaster.complete(runId);
                        }
                    })
            );

            // Save actual cost
            try {
                saveActualCost(runId);
            } catch (Exception e) {
                log.debug("[Run {}] Post-batch cost save failed: {}", runId, e.getMessage());
            }

        } catch (Exception e) {
            log.error("[Run {}] Batch resume failed: {}", runId, e.getMessage(), e);
            stateStore.markFailed(runId, "Batch resume failed: " + e.getMessage());
            broadcaster.emit(runId, RunEvent.complete(runId, "FAILED"));
        } finally {
            // H2+H3 cleanup: release semaphore and unregister thread
            pipelineThreads.remove(runId);
            if (slotAcquired) runSlots.release();
        }
    }

    // ── Instance heartbeat ────────────────────────────────────────────────────

    /**
     * Refreshes {@code execution_heartbeat} every 30s for all runs executing on this instance.
     * {@link com.navneet.executespec.recovery.StuckRunMonitor} uses stale heartbeats (>2 min)
     * to detect dead instances and recover their runs.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 30_000)
    void refreshInstanceHeartbeats() {
        if (activeRunThreads.isEmpty()) return;
        Instant now = Instant.now();
        for (String runId : activeRunThreads) {
            try {
                stateStore.updateHeartbeat(runId, INSTANCE_ID, now);
            } catch (Exception e) {
                log.debug("[Heartbeat] Failed to update heartbeat for run {}: {}", runId, e.getMessage());
            }
        }
    }
}
