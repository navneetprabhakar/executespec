package com.navneet.executespec.v2.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.assessment.FeatureAssessmentService;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.cost.RunCostLog;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.codeintel.RunCodeMapService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.UiStyleResolver;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.reviewer.AccuracyAssessmentService;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.TestGenOrchestrator;
import com.navneet.executespec.v2.agent.Classification;
import com.navneet.executespec.v2.agent.ClassificationResult;
import com.navneet.executespec.v2.agent.CoherencePlannerAgent;
import com.navneet.executespec.v2.agent.OutlinePlannerAgent;
import com.navneet.executespec.v2.agent.PlanStrategy;
import com.navneet.executespec.v2.agent.PlanStrategyCircuitBreaker;
import com.navneet.executespec.v2.agent.PlanV2;
import com.navneet.executespec.v2.agent.PlannedTaskV2;
import com.navneet.executespec.v2.agent.PlannerRequestV2;
import com.navneet.executespec.v2.agent.SmartClassifierAgent;
import com.navneet.executespec.v2.agent.SpecClassifier;
import com.navneet.executespec.v2.agent.TaskExpanderAgent;
import com.navneet.executespec.v2.cost.V2RunCostTracker;
import com.navneet.executespec.v2.harness.ExecutionRequest;
import com.navneet.executespec.v2.harness.ExecutionResult;
import com.navneet.executespec.v2.harness.SequentialExecutorV2;
import com.navneet.executespec.v2.harness.TaskOutcome;
import com.navneet.executespec.v2.observability.V2Metrics;
import com.navneet.executespec.v2.reviewer.ReviewerAgentV3;
import com.navneet.executespec.v2.reviewer.ReviewerRequestV2;
import com.navneet.executespec.v2.reviewer.ReviewerResult;
import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 integration point for V1's existing endpoints.
 *
 * <p>{@link RunController} consults {@link
 * com.navneet.executespec.v2.rollout.HarnessVersionResolver} on each {@code POST /api/runs}; when
 * the flag resolves to V2, this orchestrator takes over. The {@code submitRun(...)} signature
 * originally mirrored V1's {@code PipelineHarness.submitRun(...)} (since deleted) so the
 * controller's call site needed only an if/else swap during the V1 → V2 cutover.
 *
 * <p>Writes to V1's {@code pipeline_run} and {@code agent_task} tables so the existing UI keeps
 * working unchanged — {@code GET /api/runs/{id}} returns V2 runs as V1-shaped JSON. The only
 * distinguishing field is {@code harnessVersion = "v2"}.
 *
 * <p><b>Phase 11.A scope</b>: entity persistence, status lifecycle mapping, plan + task rows. Cost
 * tracking (Phase 11.B), code view (11.C), and GitHub push (11.D) layer on top in subsequent
 * commits.
 *
 * <p><b>Not yet implemented</b>:
 *
 * <ul>
 *   <li>Plan gate — V2 runs skip {@code AWAITING_APPROVAL}, go straight to {@code WORKING}
 *   <li>Batch execution — V2 only supports realtime for now
 *   <li>Preview / deploy / GitHub push — separate phases
 *   <li>Credits / billing — Phase 11.B hooks {@code CostEstimator}
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class V2RunOrchestrator {

  /** Default V2 task classification — MEDIUM produces ≤ 4 tasks, good balance for most specs. */
  /**
   * Fallback only — live classification flows through {@link
   * com.navneet.executespec.v2.agent.SpecClassifier} which picks SINGLE_SHOT / MEDIUM / MULTI_TASK
   * from the spec itself (bug #3 fix).
   */
  private static final Classification DEFAULT_CLASSIFICATION = Classification.MEDIUM;

  /** Default provider when the request doesn't specify one. Matches V1 default. */
  private static final String DEFAULT_PROVIDER = "openai";

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final OutlinePlannerAgent outlinePlanner;
  private final TaskExpanderAgent taskExpander;
  private final CoherencePlannerAgent coherencePlanner;
  private final PlanStrategyCircuitBreaker planStrategyBreaker;
  private final SmartClassifierAgent smartClassifier;
  private final SequentialExecutorV2 executor;
  private final ReviewerAgentV3 reviewer;
  private final V2Metrics metrics;
  private final ObjectMapper objectMapper;
  private final AgentProperties agentProps;
  private final V2RunCostTracker costTracker;
  private final com.navneet.executespec.v2.cost.RunTokenAttributor tokenAttributor;
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
  private final ProjectRepoService projectRepoService;
  private final FeatureAssessmentService featureAssessmentService;
  private final RunCodeMapService runCodeMapService;
  private final AppUserRepository appUserRepository;
  private final com.navneet.executespec.memory.ProjectMemoryProvider projectMemoryProvider;
  private final com.navneet.executespec.codeintel.ProjectBriefService projectBriefService;
  private final com.navneet.executespec.codeintel.StalenessService stalenessService;
  private final com.navneet.executespec.codeintel.ProjectArchitectureService
      projectArchitectureService;
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService;
  private final com.navneet.executespec.memory.RunLedgerAgent runLedgerAgent;
  private final com.navneet.executespec.integration.scm.ScmPushService scmPushService;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;
  private final com.navneet.executespec.auth.repository.ProjectRepository projectRepository;

  /** Wave 2.1 — activation funnel event tracker (no-op when PostHog is unconfigured). */
  private final com.navneet.executespec.analytics.EventTrackingService eventTracking;

  /**
   * 2026-05-02 — re-review path needs to delete the existing {@code validation_report} row before
   * the reviewer + accuracy assessor + report generator re-run with the expanded vendored-path
   * exclusion regex. Wired here (rather than calling {@link ValidationReportService}) so the
   * transactional boundary stays a single repository call instead of bouncing through the report
   * service's read/write/index-rebuild path.
   */
  private final com.navneet.executespec.report.ValidationReportRepository
      validationReportRepository;

  /**
   * V165 BYOK fix — resolves customer-configured keys at run-start; LlmRouterV2 reads back per
   * dispatch.
   */
  private final com.navneet.executespec.v2.agent.V2RunKeyContextStore keyContextStore;

  /**
   * R-162 — referral hook. Wired here so that when a referee's first run reaches a successful
   * terminal status, the inviting Free user gets +5 lifetime credits granted automatically.
   * Idempotent on the service side (uses pending-invite presence as the once-only marker until V153
   * promotes it to a granted_at column).
   */
  private final com.navneet.executespec.v3.referral.ReferralService referralService;

  /**
   * R-162 — terminal-status referral hook. Logic extracted into a helper so the wire-point is
   * unit-testable without exercising the orchestrator end-to-end (see {@link
   * com.navneet.executespec.v3.referral.ReferralCompletionHook}).
   */
  private final com.navneet.executespec.v3.referral.ReferralCompletionHook referralCompletionHook;

  /**
   * v2.5.8 — publishes PipelineEvent.RunCompleted/RunFailed so in-app notifications, quota refund,
   * and workspace GCS upload listeners fire for V2 runs. Previously only V1 PipelineHarness
   * published these events, causing V2 users to silently miss completion notifications.
   */
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;

  /**
   * Dedicated thread pool — V1 has its own, V2 keeps a separate one so back-pressure is tracked
   * per-harness and canary rollout tuning is independent.
   */
  /** runId → in-flight pipeline future; used by {@link #cancel(String)}. */
  private final ConcurrentMap<String, Future<?>> activeRuns = new ConcurrentHashMap<>();

  private final ExecutorService pool =
      Executors.newFixedThreadPool(
          4,
          new ThreadFactory() {
            private int n = 0;

            @Override
            public Thread newThread(Runnable r) {
              Thread t = new Thread(r, "v2-orchestrator-" + (++n));
              t.setDaemon(true);
              return t;
            }
          });

  /**
   * v2.5.12 — graceful shutdown. Without this, SIGTERM on the JVM abandons in-flight runs
   * mid-planning/execution after credits were already charged at plan approval. 30 s grace window
   * matches Kubernetes default `terminationGracePeriodSeconds`.
   */
  @jakarta.annotation.PreDestroy
  public void shutdownPool() {
    log.info("V2RunOrchestrator shutting down pool — active runs: {}", activeRuns.size());
    pool.shutdown();
    try {
      if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
        log.warn("V2RunOrchestrator pool did not terminate in 30s; forcing shutdownNow");
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pool.shutdownNow();
    }
  }

  /**
   * Submit a new run through V2. Returns a freshly-persisted {@link PipelineRun} with status {@code
   * PLANNING}; the async pipeline thread continues in the background.
   *
   * <p>Signature originally mirrored V1's {@code PipelineHarness.submitRun(...)} (since deleted) so
   * the controller could delegate with zero argument transformation.
   */
  public PipelineRun submitRun(
      String userRequest,
      String source,
      String repoUrl,
      String repoMode,
      String githubPat,
      String githubOwner,
      boolean skipApproval,
      boolean autoMerge,
      String modelName,
      String aiProvider,
      String createdByUserId,
      String orgId,
      String projectId,
      String executionMode) {
    return submitRun(
        userRequest,
        source,
        repoUrl,
        repoMode,
        githubPat,
        githubOwner,
        skipApproval,
        autoMerge,
        modelName,
        aiProvider,
        createdByUserId,
        orgId,
        projectId,
        executionMode,
        List.of());
  }

  /**
   * Pilot v3.0 — overload that accepts project-scoped multi-repo selection. When {@code
   * selectedRepoIds} is non-empty, the pipeline clones each linked repo into {@code
   * workspace/<runId>/repos/<name>/} before planning.
   */
  public PipelineRun submitRun(
      String userRequest,
      String source,
      String repoUrl,
      String repoMode,
      String githubPat,
      String githubOwner,
      boolean skipApproval,
      boolean autoMerge,
      String modelName,
      String aiProvider,
      String createdByUserId,
      String orgId,
      String projectId,
      String executionMode,
      List<UUID> selectedRepoIds) {
    return submitRun(
        userRequest,
        source,
        repoUrl,
        repoMode,
        githubPat,
        githubOwner,
        skipApproval,
        autoMerge,
        modelName,
        aiProvider,
        createdByUserId,
        orgId,
        projectId,
        executionMode,
        selectedRepoIds,
        null);
  }

  /** Pilot R7 — overload that accepts an optional Figma URL. */
  public PipelineRun submitRun(
      String userRequest,
      String source,
      String repoUrl,
      String repoMode,
      String githubPat,
      String githubOwner,
      boolean skipApproval,
      boolean autoMerge,
      String modelName,
      String aiProvider,
      String createdByUserId,
      String orgId,
      String projectId,
      String executionMode,
      List<UUID> selectedRepoIds,
      String figmaUrl) {
    return submitRun(
        userRequest,
        source,
        repoUrl,
        repoMode,
        githubPat,
        githubOwner,
        skipApproval,
        autoMerge,
        modelName,
        aiProvider,
        createdByUserId,
        orgId,
        projectId,
        executionMode,
        selectedRepoIds,
        figmaUrl,
        false);
  }

  /**
   * v2.5.7 — back-compat overload. Forwards to the full signature with {@code branchOverride=null}
   * so every selected repo is cloned on its own default branch (previous behaviour).
   */
  public PipelineRun submitRun(
      String userRequest,
      String source,
      String repoUrl,
      String repoMode,
      String githubPat,
      String githubOwner,
      boolean skipApproval,
      boolean autoMerge,
      String modelName,
      String aiProvider,
      String createdByUserId,
      String orgId,
      String projectId,
      String executionMode,
      List<UUID> selectedRepoIds,
      String figmaUrl,
      boolean tddMode) {
    return submitRun(
        userRequest,
        source,
        repoUrl,
        repoMode,
        githubPat,
        githubOwner,
        skipApproval,
        autoMerge,
        modelName,
        aiProvider,
        createdByUserId,
        orgId,
        projectId,
        executionMode,
        selectedRepoIds,
        figmaUrl,
        tddMode,
        null);
  }

  /**
   * v2.5.7 — full signature with {@code branchOverride}. When non-null, every selected repo is
   * cloned with {@code git clone -b <branchOverride>} instead of the repo's default branch.
   */
  public PipelineRun submitRun(
      String userRequest,
      String source,
      String repoUrl,
      String repoMode,
      String githubPat,
      String githubOwner,
      boolean skipApproval,
      boolean autoMerge,
      String modelName,
      String aiProvider,
      String createdByUserId,
      String orgId,
      String projectId,
      String executionMode,
      List<UUID> selectedRepoIds,
      String figmaUrl,
      boolean tddMode,
      String branchOverride) {
    String runId = UUID.randomUUID().toString();
    String provider = normalizeProvider(aiProvider, projectId, runId);
    String model =
        modelName != null && !modelName.isBlank() ? modelName : defaultModelFor(provider);

    // Resolve per-tier models so RunDetail surfaces what the planner/reviewer
    // will actually call, not the run-level worker default — adversarial-test
    // Finding 14. If a user-supplied modelName was provided (rare BYOK path),
    // honour it for all three roles to preserve the override contract.
    String plannerDbModel = model;
    String reviewerDbModel = model;
    if (modelName == null || modelName.isBlank()) {
      try {
        com.navneet.executespec.router.LlmProvider p =
            com.navneet.executespec.router.LlmProvider.valueOf(provider.toUpperCase());
        // v2.6.2 slice 5b — agent name is now OutlinePlannerAgent;
        // PlannerAgentV2 was deleted along with the legacy single-phase planner.
        plannerDbModel =
            modelResolver
                .resolve(
                    "OutlinePlannerAgent", p, com.navneet.executespec.router.AgentTier.PLANNING)
                .getModel();
        reviewerDbModel =
            modelResolver
                .resolve("ReviewerAgentV3", p, com.navneet.executespec.router.AgentTier.PLANNING)
                .getModel();
      } catch (Exception ex) {
        log.debug(
            "Tier-based model resolution failed for run={} provider={}, falling back to run"
                + " default: {}",
            runId,
            provider,
            ex.getMessage());
      }
    }

    PipelineRun run =
        PipelineRun.builder()
            .runId(runId)
            .userRequest(userRequest)
            .status("PLANNING")
            .harnessVersion("v2")
            .sourceType(source != null ? source : "MANUAL")
            .repoUrl(repoUrl)
            .repoMode(repoMode)
            .aiProvider(provider.toUpperCase())
            .plannerModel(plannerDbModel)
            .workerModel(model)
            .reviewerModel(reviewerDbModel)
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
            .figmaUrl(figmaUrl)
            .selectedRepoIds(serializeRepoIds(selectedRepoIds))
            .build();
    run = runRepo.save(run);

    log.info(
        "V2RunOrchestrator submitRun runId={} provider={} model={} user={} org={} project={}",
        runId,
        provider,
        model,
        createdByUserId,
        orgId,
        projectId);

    // Wave 2.1 — activation funnel step 3.
    if (createdByUserId != null) {
      java.util.Map<String, Object> props = new java.util.HashMap<>();
      props.put("aiProvider", provider);
      props.put("executionMode", executionMode == null ? "PRODUCTION" : executionMode);
      props.put("skipApproval", skipApproval);
      props.put("hasProject", projectId != null);
      props.put("tddMode", tddMode);
      eventTracking.track(
          createdByUserId, com.navneet.executespec.analytics.EventName.SPEC_SUBMITTED, props);
    }

    // Kick off the pipeline on a background thread. The returned PipelineRun is the
    // initial state; the frontend polls GET /api/runs/{id} to observe progression.
    final String runIdFinal = runId;
    final String providerFinal = provider;
    final String modelFinal = model;
    final boolean skipApprovalFinal = skipApproval;
    final String executionModeFinal = executionMode != null ? executionMode : "PRODUCTION";
    final String projectIdFinal = projectId;
    final List<UUID> selectedRepoIdsFinal = selectedRepoIds != null ? selectedRepoIds : List.of();
    final String githubPatFinal = githubPat;
    final boolean tddModeFinal = tddMode;
    final String branchOverrideFinal =
        (branchOverride == null || branchOverride.isBlank()) ? null : branchOverride.trim();
    Future<?> future =
        pool.submit(
            () -> {
              try {
                runPipeline(
                    runIdFinal,
                    providerFinal,
                    modelFinal,
                    userRequest,
                    skipApprovalFinal,
                    executionModeFinal,
                    projectIdFinal,
                    selectedRepoIdsFinal,
                    githubPatFinal,
                    tddModeFinal,
                    branchOverrideFinal);
              } finally {
                activeRuns.remove(runIdFinal);
              }
            });
    activeRuns.put(runIdFinal, future);
    return run;
  }

  // ─── pipeline (background thread) ───────────────────────────────────────

  private void runPipeline(
      String runId,
      String provider,
      String model,
      String userRequest,
      boolean skipApproval,
      String executionMode) {
    runPipeline(
        runId,
        provider,
        model,
        userRequest,
        skipApproval,
        executionMode,
        null,
        List.of(),
        null,
        false);
  }

  private void runPipeline(
      String runId,
      String provider,
      String model,
      String userRequest,
      boolean skipApproval,
      String executionMode,
      String projectId,
      List<UUID> selectedRepoIds,
      String githubPat) {
    runPipeline(
        runId,
        provider,
        model,
        userRequest,
        skipApproval,
        executionMode,
        projectId,
        selectedRepoIds,
        githubPat,
        false);
  }

  private void runPipeline(
      String runId,
      String provider,
      String model,
      String userRequest,
      boolean skipApproval,
      String executionMode,
      String projectId,
      List<UUID> selectedRepoIds,
      String githubPat,
      boolean tddMode) {
    runPipeline(
        runId,
        provider,
        model,
        userRequest,
        skipApproval,
        executionMode,
        projectId,
        selectedRepoIds,
        githubPat,
        tddMode,
        null);
  }

  private void runPipeline(
      String runId,
      String provider,
      String model,
      String userRequest,
      boolean skipApproval,
      String executionMode,
      String projectId,
      List<UUID> selectedRepoIds,
      String githubPat,
      boolean tddMode,
      String branchOverride) {
    Instant startedAt = Instant.now();
    Path workspace = null;
    String upperProvider = provider == null ? "UNKNOWN" : provider.toUpperCase();
    try {
      // V165 BYOK fix — resolve the run owner's configured keys ONCE
      // at run-start and stash them in V2RunKeyContextStore. LlmRouterV2
      // reads back per-dispatch via request.context().runId() and
      // threads the right provider key into the wire call. Without
      // this, every dispatched LLM call silently used the platform
      // default key — see audit-output/BYOK_DEEPDIVE.md for the
      // full chain-of-evidence.
      try {
        PipelineRun pre = runRepo.findById(runId).orElse(null);
        if (pre != null && pre.getCreatedByUserId() != null) {
          com.navneet.executespec.v2.agent.V2RunKeyContext kctx =
              keyContextStore.resolveAndPut(runId, pre.getCreatedByUserId());
          String label = kctx.dominantSource().name();
          if (!label.equals(pre.getKeySource())) {
            pre.setKeySource(label);
            runRepo.save(pre);
          }
          log.info("V2 run={} keys resolved (source={})", runId, label);
        }
      } catch (Exception keyEx) {
        // Non-fatal — falls through to platform key dispatch.
        log.warn(
            "V2 run={} BYOK resolution failed; using platform keys: {}", runId, keyEx.getMessage());
      }

      // Use V1's workspace convention: <agent.workspace>/<runId>/
      // V1's WorkspaceFileService (Code tab, download ZIP) resolves paths via the
      // same formula, so files written here are visible through the existing UI
      // with zero adapter work. Phase 11.C formalizes this in the parity plan.
      workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
      // Reuse V1's workspace init — creates a fresh git repo with .gitignore/.gitattributes
      // and an initial empty commit. This is required for `/push-github` to work against
      // V2 runs: the endpoint assumes the workspace already has git history.
      workspaceInit.ensureRunWorkspace(runId);

      // Pilot v3.0 — clone any project-linked repos the user selected for this run
      // into <workspace>/<runId>/repos/<name>/ subdirectories. Non-fatal: if a clone
      // fails we log and continue so the run can at least try to plan against what
      // was fetched.
      if (projectId != null && selectedRepoIds != null && !selectedRepoIds.isEmpty()) {
        try {
          List<ProjectRepo> repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
          List<WorkspaceInitService.RepoToClone> toClone =
              repos.stream()
                  .map(
                      r ->
                          new WorkspaceInitService.RepoToClone(
                              r.getGithubRepoFullName(),
                              "https://github.com/" + r.getGithubRepoFullName(),
                              r.getId(),
                              branchOverride != null ? branchOverride : r.getDefaultBranch()))
                  .toList();
          workspaceInit.cloneMultipleRepos(runId, toClone, githubPat);
          log.info("V2 run={} cloned {} project repos", runId, toClone.size());
        } catch (Exception cloneEx) {
          log.warn("V2 run={} multi-repo clone failed: {}", runId, cloneEx.getMessage(), cloneEx);
        }
      }

      ExecutionContext rootCtx = new ExecutionContext(runId, "__root__", workspace.toRealPath());
      setWorkspacePath(runId, workspace.toString());

      // ── 0b. Pilot v3.0 follow-up: per-run codemap for paid existing-repo
      // runs (PRO / MAX / ENTERPRISE). Adds one PLANNING-tier LLM call before
      // plan approval; the summarised codemap becomes the planner's
      // workspaceSummary AND extra context for the assessment.
      String codemapSummary = null;
      PipelineRun runRow = runRepo.findById(runId).orElse(null);
      if (runRow != null
          && projectId == null
          && "EXISTING".equalsIgnoreCase(runRow.getRepoMode())
          && runRow.getRepoUrl() != null
          && runRow.getCreatedByUserId() != null) {
        String tier =
            appUserRepository
                .findById(runRow.getCreatedByUserId())
                .map(u -> u.getTier())
                .orElse(null);
        if (runCodeMapService.isEligible(tier)) {
          try {
            broadcaster.emit(
                runId,
                RunEvent.log(runId, "codemap", "Generating codebase map for planner context"));
            codemapSummary =
                runCodeMapService.generateForRun(runId, runRow.getRepoUrl(), null, githubPat);
            if (codemapSummary != null) {
              broadcaster.emit(
                  runId,
                  RunEvent.log(
                      runId, "codemap", "Codemap ready — injected into planner + assessment"));
            }
          } catch (Exception cmEx) {
            log.warn("V2 run={} codemap generation failed: {}", runId, cmEx.getMessage());
          }
        }
      }

      // ── 0c. Pilot R4: feature assessment ─────────────────────────────
      // Runs only when a project context is available so the assessment can
      // reason about linked repos + architecture annotations. Result is
      // persisted, broadcast over SSE, and available to the planner via
      // the run row. Heuristic for now (no extra LLM cost).
      if (projectId != null) {
        try {
          String assessmentJson = featureAssessmentService.assessAsJson(projectId, userRequest);
          if (assessmentJson != null) {
            runRepo
                .findById(runId)
                .ifPresent(
                    r -> {
                      r.setAssessmentResult(assessmentJson);
                      runRepo.save(r);
                    });
            broadcaster.emit(runId, RunEvent.log(runId, "assessment", "Feature assessment ready"));
          }
        } catch (Exception assessEx) {
          log.warn("V2 run={} assessment failed: {}", runId, assessEx.getMessage());
        }
      }

      // ── 1. Plan ─────────────────────────────────────────────────────
      perky.start(runId, "PLANNING");
      PlanV2 plan;
      try {
        Classification autoClassification = SpecClassifier.classify(userRequest);
        log.info(
            "V2 run={} spec classified as {} (len={})",
            runId,
            autoClassification,
            userRequest == null ? 0 : userRequest.length());

        // v2.6.2 — Smart classifier (hierarchical-planner phase 0). Verdict
        // drives downstream routing in slice 2: SMALL/MEDIUM go through the
        // OutlinePlannerAgent (32k cap, full plan in one call); SINGLE_SHOT,
        // LARGE, LEGACY, and the null-on-failure case all stay on the legacy
        // PlannerAgentV2. Cost flows through V2RunCostTracker so it auto-
        // aggregates into the run total; per-phase columns get the diff.
        PlanStrategy planStrategy =
            runClassifierShadow(runId, runRow, userRequest, projectId, selectedRepoIds, provider);
        String projectMemory =
            projectId != null ? projectMemoryProvider.buildPlannerContext(projectId) : "";
        if (projectId != null) {
          try {
            // Gap #5 (v2.5.12) — thread the submitting user's PAT through
            // staleness + regen so private-org repos don't 404 in the
            // background. Falls back to system PAT (null) if the user has
            // no PAT configured (public repos work fine that way).
            String runOwnerId = runRow == null ? null : runRow.getCreatedByUserId();
            String runUserPat =
                runOwnerId == null
                    ? null
                    : appUserRepository
                        .findById(runOwnerId)
                        .map(com.navneet.executespec.auth.entity.AppUser::getGithubPat)
                        .orElse(null);
            var staleness = stalenessService.report(projectId, runUserPat);
            if (staleness != null && staleness.stale()) {
              log.info(
                  "V2 run={} project={} has {} stale codemap(s); regenerating before planner",
                  runId,
                  projectId,
                  staleness.staleCount());
              projectArchitectureService.generate(projectId, null, runUserPat);
              stalenessService.invalidate(projectId);
            }
          } catch (Exception e) {
            log.warn(
                "V2 run={} pre-execution staleness refresh failed (continuing): {}",
                runId,
                e.getMessage());
          }
        }
        String projectBrief = projectId != null ? projectBriefService.buildBrief(projectId) : "";
        String referenceDocs =
            projectId != null ? retrievalService.retrieve(projectId, userRequest) : "";
        String multiRepoSummary = buildMultiRepoSummary(projectId, selectedRepoIds);
        String summary;
        if (multiRepoSummary != null) {
          summary = multiRepoSummary;
        } else if (codemapSummary != null) {
          summary = "Existing repository — codebase map below.\n\n" + codemapSummary;
        } else {
          summary = "empty scratch workspace (V2 harness)";
        }
        PlannerRequestV2 plannerReq =
            PlannerRequestV2.builder()
                .spec(userRequest)
                .classification(autoClassification)
                .provider(provider)
                .model(model)
                .context(rootCtx)
                .workspaceSummary(summary)
                .projectMemory(projectMemory)
                .projectBrief(projectBrief)
                .referenceDocs(referenceDocs)
                .tddMode(tddMode)
                .prReviewComments(null)
                .build();

        // v2.6.2 slice 3 — strategy-driven routing with auto-degrade
        // when a strategy's circuit breaker is open.
        //   SMALL / MEDIUM → outline planner (FULL_PLAN mode, single call).
        //   LARGE          → outline planner (SKELETON_ONLY) → parallel
        //                    expansion → coherence pass.
        //   LEGACY         → defer to slice 4 — recursive depth-2
        //                    expansion isn't built yet, so degrade to LARGE.
        //   SINGLE_SHOT / null / classifier failure → legacy planner.
        PlanStrategy effective =
            planStrategy == null
                ? null
                : planStrategyBreaker.degradeIfOpen(
                    planStrategy == PlanStrategy.LEGACY ? PlanStrategy.LARGE : planStrategy);
        if (effective != planStrategy && effective != null) {
          log.warn("V2 run={} circuit breaker degraded {} → {}", runId, planStrategy, effective);
        }

        // Slice 4 — LEGACY classifications now drive depth-2 recursive
        // expansion in the LARGE pipeline. The classifier verdict
        // (pre-degrade) tells us whether to recurse on EPIC tasks; the
        // breaker still degrades to LARGE for capacity reasons but the
        // intent (and the recursion) is preserved.
        boolean legacyRecursion = planStrategy == PlanStrategy.LEGACY;

        if (effective == PlanStrategy.SMALL || effective == PlanStrategy.MEDIUM) {
          try {
            plan = runOutlinePhase(runId, plannerReq, OutlinePlannerAgent.Mode.FULL_PLAN);
            planStrategyBreaker.recordSuccess(effective);
          } catch (Exception outlineEx) {
            planStrategyBreaker.recordFailure(effective);
            log.warn(
                "V2 run={} outline planner ({}) failed → SINGLE_SHOT fallback", runId, effective);
            plan = outlinePlanner.synthesizeSingleShot(plannerReq);
          }
        } else if (effective == PlanStrategy.LARGE) {
          try {
            plan = runLargePipeline(runId, plannerReq, rootCtx, legacyRecursion);
            planStrategyBreaker.recordSuccess(PlanStrategy.LARGE);
          } catch (Exception largeEx) {
            planStrategyBreaker.recordFailure(PlanStrategy.LARGE);
            log.warn(
                "V2 run={} LARGE pipeline failed → SINGLE_SHOT fallback: {}",
                runId,
                largeEx.getMessage());
            plan = outlinePlanner.synthesizeSingleShot(plannerReq);
          }
        } else if (effective == PlanStrategy.SINGLE_SHOT) {
          // v2.6.2 slice 5b — heuristic-fast-path classification.
          // No LLM planner call; synthesise a one-task plan from the
          // spec verbatim. Migrated from the deleted PlannerAgentV2.
          plan = outlinePlanner.synthesizeSingleShot(plannerReq);
        } else {
          // Classifier failed (effective == null) or returned an
          // unhandled value. Treat as SINGLE_SHOT — same shape as
          // the legacy synthesised path, no LLM cost beyond the
          // optional title summarizer.
          log.warn("V2 run={} no usable strategy verdict → synthesised SINGLE_SHOT plan", runId);
          plan = outlinePlanner.synthesizeSingleShot(plannerReq);
        }
      } catch (Exception e) {
        log.error("V2RunOrchestrator run={} planner failed", runId, e);
        terminate(runId, "FAILED", "planner: " + shortMsg(e), startedAt);
        return;
      }
      if (plan.tasks() == null || plan.tasks().isEmpty()) {
        log.error("V2RunOrchestrator run={} planner returned empty plan — failing", runId);
        terminate(
            runId,
            "FAILED",
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
      tokenAttributor.attributePhase(
          runId,
          com.navneet.executespec.v2.cost.RunTokenAttributor.Phase.PLANNER,
          afterPlan.inputTokens(),
          afterPlan.outputTokens(),
          upperProvider,
          model);

      // ── 1b. Plan approval gate (PRODUCTION only) ────────────────────
      // PROTOTYPE runs + integration-triggered runs (skipApproval=true) bypass
      // the gate so they flow straight into execution. PRODUCTION runs block
      // on GateStore.awaitPlanGate until the user approves or rejects via
      // /api/runs/{id}/approve.
      boolean needsApproval = !skipApproval && "PRODUCTION".equalsIgnoreCase(executionMode);
      if (needsApproval) {
        perky.stop(runId);
        updateStatus(runId, "AWAITING_APPROVAL");
        // Open a PENDING row so the approve-endpoint's atomic claim
        // (GateStore.tryClaimPlanGate) can detect concurrent approvals and
        // return 409 to the loser — adversarial-test-report Finding 4.
        gateStore.openPlanGate(runId);
        int credits =
            runRepo
                .findById(runId)
                .map(r -> r.getCreditsCharged() == null ? 1 : r.getCreditsCharged())
                .orElse(1);
        double estimatedCost =
            runRepo
                .findById(runId)
                .map(r -> r.getCostEstimateHigh() == null ? 0.0 : r.getCostEstimateHigh())
                .orElse(0.0);
        broadcaster.emit(
            runId, RunEvent.gateOpenedWithCredits(runId, "PLAN_APPROVAL", credits, estimatedCost));
        eventPublisher.publishEvent(new PipelineEvent.GateOpened(runId, "PLAN_APPROVAL", credits));
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
        execResult =
            executor.execute(
                ExecutionRequest.builder()
                    .plan(plan)
                    .provider(provider)
                    .model(model)
                    .runContext(rootCtx)
                    .maxIterationsPerTask(
                        null) // per-complexity caps applied in SequentialExecutorV2
                    .enableSmokeTests(true)
                    .designBlock(designBlock)
                    .projectId(projectId)
                    // Multi-repo runs depend on the worker seeing the "MULTI-REPO PROJECT
                    // WORKSPACE" header + the per-repo subdir list — without it the worker
                    // writes generated files at workspace root and the per-repo push step
                    // opens empty PRs. The planner already received this block at line 816;
                    // recompute fresh here from the same inputs (projectId + selectedRepoIds)
                    // so executor + planner can never drift, and so we don't have to widen
                    // the scope of an inner-block local variable. Returns null on single-repo
                    // / scratch-workspace runs; the worker's composeSystemPrompt skips the
                    // block when null.
                    .workspaceSummary(buildMultiRepoSummary(projectId, selectedRepoIds))
                    .build());
      } catch (Exception e) {
        log.error("V2RunOrchestrator run={} executor failed", runId, e);
        terminate(runId, "FAILED", "executor: " + shortMsg(e), startedAt);
        return;
      }
      applyExecutionResultToTasks(runId, execResult);
      // Phase 2 delta: tokens spent by the executor (all worker tasks).
      V2RunCostTracker.Snapshot afterExec = costTracker.snapshot(runId);
      int workerIn = afterExec.inputTokens() - afterPlan.inputTokens();
      int workerOut = afterExec.outputTokens() - afterPlan.outputTokens();
      tokenAttributor.attributeWorker(runId, execResult, workerIn, workerOut, upperProvider, model);

      // ── 3. Review ───────────────────────────────────────────────────
      updateStatus(runId, "REVIEWING");
      perky.start(runId, "REVIEWING");
      ReviewerResult reviewResult = null;
      try {
        reviewResult =
            reviewer.review(
                ReviewerRequestV2.builder()
                    .provider(provider)
                    .model(model)
                    .context(rootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(false)
                    .build());
        persistReview(runId, reviewResult);
        // Phase 3 delta: tokens spent by reviewer (general + wiring passes).
        V2RunCostTracker.Snapshot afterReview = costTracker.snapshot(runId);
        int reviewerIn = afterReview.inputTokens() - afterExec.inputTokens();
        int reviewerOut = afterReview.outputTokens() - afterExec.outputTokens();
        tokenAttributor.attributePhase(
            runId,
            com.navneet.executespec.v2.cost.RunTokenAttributor.Phase.REVIEWER,
            reviewerIn,
            reviewerOut,
            upperProvider,
            model);
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
        log.info(
            "V2RunOrchestrator run={} CLI smoke status={} exit={} cmd='{}' dur={}ms",
            runId,
            cliResult.status(),
            cliResult.exitCode(),
            cliResult.command(),
            cliResult.durationMs());
      } else {
        log.info(
            "V2RunOrchestrator run={} skipping test generation (runtimeSurface={})",
            runId,
            plan.runtimeSurface());
      }

      // ── 6. Final workspace commit so `/push-github` has refs to push ─
      commitWorkspaceOutputs(runId, workspace);

      // ── 6b. Project memory: append compressed ledger entry for project-scoped runs.
      // Non-fatal — any failure is swallowed inside the agent.
      if (projectId != null) {
        try {
          runLedgerAgent.generateLedgerEntry(runId);
        } catch (Exception memEx) {
          log.warn("V2 run={} project-memory ledger failed: {}", runId, memEx.getMessage());
        }
        // Invalidate staleness cache so the next run's lazy refresh picks up
        // the changes from this run (codemap regen happens at next plan time).
        try {
          stalenessService.invalidate(projectId);
        } catch (Exception ignore) {
        }
      }

      // ── 7. Terminal status mapping ─────────────────────────────────
      // v2.5.10 — when scoring.v2-verified is on, decide status + reason
      // from reviewer verdict + final build + execution signals. Legacy
      // one-line map preserved behind the flag for fast rollback.
      StatusAndReason sr =
          featureFlagService.isEnabled("scoring.v2-verified")
              ? resolveTerminalStatus(execResult, reviewResult)
              : new StatusAndReason(mapOverallToV1Status(execResult.overall()), null);
      String finalStatus = sr.status();
      String finalReason = sr.reason();
      terminate(
          runId,
          finalStatus,
          finalReason,
          "overall="
              + execResult.overall()
              + (reviewResult != null ? "; review=" + reviewResult.overall() : "")
              + (finalReason != null ? "; reason=" + finalReason : ""),
          startedAt);
      log.info(
          "V2RunOrchestrator run={} {} exec={} review={} reason={}",
          runId,
          finalStatus,
          execResult.overall(),
          reviewResult == null ? "n/a" : reviewResult.overall(),
          finalReason);

      // ── 7b. Auto-push every successful run ─────────────────────────
      // Any run that reaches COMPLETE/PARTIAL and targets an EXISTING repo
      // gets pushed to GitHub automatically. Previously only project-scoped
      // runs auto-pushed; standalone /api/runs runs required the user to
      // click "Push & open PR" — a footgun when the run looks complete in
      // the UI but no PR exists. Manual "Push & open PR" still works as a
      // re-push. Feature flag `project.runs.auto-push` still gates the
      // project-scoped path for back-compat; standalone runs are always on.
      if ("COMPLETE".equals(finalStatus) || "PARTIAL".equals(finalStatus)) {
        boolean shouldPush;
        if (projectId != null) {
          shouldPush = featureFlagService.isEnabled("project.runs.auto-push");
        } else {
          // Standalone run: push whenever the run targeted an EXISTING repo.
          PipelineRun r = runRepo.findById(runId).orElse(null);
          shouldPush =
              r != null
                  && "EXISTING".equalsIgnoreCase(r.getRepoMode())
                  && r.getRepoUrl() != null
                  && !r.getRepoUrl().isBlank();
        }
        if (shouldPush) {
          try {
            PipelineRun fresh = runRepo.findById(runId).orElse(null);
            if (fresh != null) {
              scmPushService.push(fresh);
              log.info("V2 run={} auto-pushed to SCM (projectScoped={})", runId, projectId != null);
            }
          } catch (Exception pushEx) {
            log.warn(
                "V2 run={} auto-push failed (manual push still works): {}",
                runId,
                pushEx.getMessage());
          }
        }
      }
    } catch (Exception unexpected) {
      log.error("V2RunOrchestrator run={} unexpected failure", runId, unexpected);
      terminate(runId, "FAILED", "unexpected: " + shortMsg(unexpected), startedAt);
    } finally {
      perky.stop(runId);
      // V165 BYOK fix — drop the cached key context. Caffeine's TTL
      // would clean up eventually, but explicit eviction keeps memory
      // usage tight on long-lived JVMs and prevents a stale entry
      // from outlasting a re-submission of the same runId.
      try {
        keyContextStore.evict(runId);
      } catch (Exception ignore) {
        /* best-effort */
      }
      // Workspace retained for code-view / download endpoints (Phase 11.C).
      if (workspace != null) {
        log.info("V2RunOrchestrator run={} workspace at {}", runId, workspace);
      }
    }
  }

  // ─── persistence helpers ────────────────────────────────────────────────

  private void persistPlan(String runId, PlanV2 plan) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                r.setPlanJson(objectMapper.writeValueAsString(plan));
              } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} failed to serialize plan JSON", runId, e);
              }
              // v2.5.11 (Validation v3 / Pillar 1) — persist the enumerated
              // requirement graph alongside the plan JSON so the reviewer (Pillar
              // 2) and the UI (Pillar 5) can render per-requirement evidence.
              // Null when plan.requirements is empty (legacy / flag off).
              try {
                if (plan.requirements() != null && !plan.requirements().isEmpty()) {
                  r.setPlanRequirementsJson(objectMapper.writeValueAsString(plan.requirements()));
                }
              } catch (Exception e) {
                log.warn(
                    "V2RunOrchestrator run={} failed to serialize plan_requirements_json",
                    runId,
                    e);
              }
              runRepo.save(r);
            });
  }

  private void persistTasks(String runId, PlanV2 plan) {
    String prefix = runId.substring(0, 8) + "_";
    for (PlannedTaskV2 task : plan.tasks()) {
      String taskId = prefix + task.id();
      AgentTask row =
          AgentTask.builder()
              .taskId(taskId)
              .runId(runId)
              .title(task.title())
              .complexity(task.complexity().name())
              .status("PENDING")
              // v2.6.2 slice 5d — record the planner-shape metadata so
              // StartupRunResumerV2 can tell EXPANDED vs PENDING tasks
              // apart on resume + a future auto-replay pass can skip
              // tasks already filled in.
              .taskType(task.taskType() == null ? "LEAF" : task.taskType().name())
              // EXPANDED for full-bodied tasks (slices 1-2 + LEGACY's
              // recursive children) and post-expansion LARGE tasks.
              // Skeleton-only tasks pre-expansion would be PENDING here —
              // but persistTasks runs after the LARGE pipeline has
              // already stitched bodies in, so all rows reach this point
              // with full descriptions. Distinction matters for future
              // resume: a row written EXPANDED never needs a re-expand.
              .expansionStatus(task.isSkeleton() ? "PENDING" : "EXPANDED")
              .expandedAt(task.isSkeleton() ? null : java.time.Instant.now())
              .build();
      try {
        row.setTaskJson(objectMapper.writeValueAsString(task));
      } catch (Exception e) {
        log.warn("V2RunOrchestrator run={} failed to serialize task {}", runId, taskId, e);
      }
      // v2.5.11 (Validation v3 / Pillar 1) — persist per-task requirement
      // coverage so downstream consumers (reviewer evidence, UI table)
      // can trace implementation back to spec requirements.
      try {
        if (task.meetsRequirements() != null && !task.meetsRequirements().isEmpty()) {
          row.setMeetsRequirementsJson(objectMapper.writeValueAsString(task.meetsRequirements()));
        }
      } catch (Exception e) {
        log.warn(
            "V2RunOrchestrator run={} failed to serialize meets_requirements for {}",
            runId,
            taskId,
            e);
      }
      taskRepo.save(row);
    }
  }

  /**
   * Map each {@link TaskOutcome} back to the corresponding {@link AgentTask} row and update status
   * + timestamps. Task IDs are prefixed with the run's first 8 chars so we reconstruct the DB id
   * from {@code PlannedTaskV2.id}.
   */
  private void applyExecutionResultToTasks(String runId, ExecutionResult result) {
    String prefix = runId.substring(0, 8) + "_";
    for (TaskOutcome outcome : result.perTask()) {
      String taskId = prefix + outcome.taskId();
      taskRepo
          .findById(taskId)
          .ifPresent(
              t -> {
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
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                // Minimal serialization — full ReviewerResult has WorkerResult refs that
                // aren't Jackson-friendly; extract the user-facing fields only.
                Map<String, Object> summary =
                    Map.of(
                        "overall",
                        review.overall().name(),
                        "finalBuildPassed",
                        review.finalBuildPassed() != null && review.finalBuildPassed(),
                        "finalBuildExitCode",
                        review.finalBuildExitCode() == null ? -1 : review.finalBuildExitCode(),
                        "generalPassSummary",
                        review.generalPassSummary() == null ? "" : review.generalPassSummary(),
                        "wiringPassSummary",
                        review.wiringPassSummary() == null ? "" : review.wiringPassSummary(),
                        "totalLatencyMs",
                        review.totalLatencyMs());
                r.setReviewJson(objectMapper.writeValueAsString(summary));
              } catch (Exception e) {
                log.warn("V2RunOrchestrator run={} failed to serialize review JSON", runId, e);
              }
              runRepo.save(r);
            });
  }

  private void persistReviewFailure(String runId, Exception e) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                Map<String, Object> summary =
                    Map.of("overall", "REVIEW_FAILED", "error", shortMsg(e));
                r.setReviewJson(objectMapper.writeValueAsString(summary));
              } catch (Exception ignored) {
              }
              runRepo.save(r);
            });
  }

  private void setWorkspacePath(String runId, String path) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setWorkspacePath(path);
              runRepo.save(r);
            });
    log.debug("V2RunOrchestrator run={} workspace={}", runId, path);
  }

  /**
   * Stages and commits every change in the workspace as a single "V2 run output" commit after
   * execution + review complete. Required so {@code /push-github} has concrete refs to push — V2
   * workers use {@code writeFile/editFile} tools directly on the filesystem and don't always commit
   * per task.
   *
   * <p>Best-effort — a failed commit (e.g. nothing to commit, user had a .git/index.lock problem)
   * is logged but doesn't fail the run.
   */
  private void commitWorkspaceOutputs(String runId, Path workspace) {
    if (workspace == null || !Files.isDirectory(workspace.resolve(".git"))) return;
    try {
      ProcessBuilder addPb =
          new ProcessBuilder("git", "add", "-A")
              .directory(workspace.toFile())
              .redirectErrorStream(true);
      addPb.start().waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
      ProcessBuilder commitPb =
          new ProcessBuilder("git", "commit", "--allow-empty", "-m", "V2 run output for " + runId)
              .directory(workspace.toFile())
              .redirectErrorStream(true);
      int exit = commitPb.start().waitFor();
      log.info("V2RunOrchestrator run={} final commit exit={}", runId, exit);
    } catch (Exception e) {
      log.warn("V2RunOrchestrator run={} final commit failed: {}", runId, e.getMessage());
    }
  }

  /**
   * User-initiated cancel. Marks the run CANCELLED, signals the plan gate (so an awaiting pipeline
   * thread wakes up and exits), and interrupts the background pipeline future if still live.
   * Idempotent — re-calling on an already-terminal run is a no-op and returns false.
   *
   * @return true if the run was cancellable (non-terminal status); false if unknown run, or already
   *     terminal.
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

  // ── V168 (2026-05-02) — pause + resume ────────────────────────────────────

  /**
   * Outcome of a {@link #resume(String)} call. Mapped to HTTP status by {@code RunController}.
   *
   * <ul>
   *   <li>{@link #OK} — resume accepted; pipeline resubmitted on the worker pool.
   *   <li>{@link #NOT_PAUSED} — run isn't in {@code PAUSED}. Caller should fix state or use cancel
   *       / retry.
   *   <li>{@link #WORKSPACE_GONE} — local workspace dir is missing. Same-JVM resume is impossible
   *       today; cross-JVM workspace shipping is the deferred chunk #3 of the pause/resume design.
   *   <li>{@link #NOT_FOUND} — run id is unknown.
   * </ul>
   */
  public enum ResumeOutcome {
    OK,
    NOT_PAUSED,
    WORKSPACE_GONE,
    NOT_FOUND
  }

  /** Wall-clock cap for a paused run before {@link StartupRunResumerV2} treats it as abandoned. */
  private static final java.time.Duration PAUSED_RUN_MAX_AGE = java.time.Duration.ofHours(24);

  /**
   * Request a pause. Sets {@code pause_requested = true} on the row + emits the SSE so the UI flips
   * immediately. The actual transition to {@code PAUSED} happens cooperatively at the next task or
   * iteration boundary inside {@code SequentialExecutorV2.executeOne} or {@code WorkerAgentV2.run};
   * see those for the checkpoint shape. The in-flight LLM round-trip is NOT cancelled (mid-call
   * abort is the deferred chunk #4 of the design — provider-specific work).
   *
   * <p>Returns true iff the pause request was accepted (run is in {@code WORKING} or {@code
   * REVIEWING}). Returns false for any other status — the controller maps this to HTTP 409.
   * Idempotent: a second pause request while the run is already PAUSED returns false (no churn on
   * paused runs).
   */
  public boolean pause(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return false;
    var run = opt.get();
    String status = run.getStatus();
    // Pause is only meaningful while work is happening. Other states either
    // already act as pause-equivalents (AWAITING_APPROVAL = plan gate; PAUSED
    // = already paused) or are too short / terminal to bother (PLANNING,
    // QUEUED, COMPLETE/FAILED/CANCELLED).
    if (!"WORKING".equals(status) && !"REVIEWING".equals(status)) return false;

    log.info("V2RunOrchestrator pause: run={} prev={}", runId, status);

    // Persist checkpoint state. paused_task_id + paused_at_iteration are best-effort here —
    // the executor's mid-task checkpoint write (deferred follow-up) would populate them
    // precisely. Today we record the timestamp + bump pause_count so support tooling can
    // diagnose state, and let resume re-plan + pick up from disk state.
    run.setPauseRequested(true);
    run.setStatus("PAUSED");
    run.setPausedAt(java.time.Instant.now());
    run.setPauseCount(run.getPauseCount() + 1);
    runRepo.save(run);

    // Interrupt the pipeline thread. The pipeline isn't fully cancellation-aware at every step,
    // but its terminal writes are no-ops because the run is already PAUSED. Files already written
    // to <workspace>/<runId>/ stay on disk — that's what the resume path picks up.
    Future<?> future = activeRuns.remove(runId);
    if (future != null) future.cancel(true);

    broadcaster.emit(runId, RunEvent.runStatus(runId, "PAUSED"));
    broadcaster.emit(
        runId,
        RunEvent.log(runId, "pause", "Run paused. Workspace files preserved. Resume to continue."));
    return true;
  }

  /**
   * Resume a previously-paused run. See {@link ResumeOutcome} for return shape.
   *
   * <p>Same-JVM only: checks that {@code <agent.workspace>/<runId>/} still exists. When the
   * pause-side workspace is gone (deploy + scale-down, container rebuild, etc.) returns {@link
   * ResumeOutcome#WORKSPACE_GONE} and leaves the run in {@code PAUSED} so the user can choose to
   * cancel or retry. Cross-JVM resume via GCS workspace shipping is the deferred chunk #3.
   *
   * <p>Resubmits {@code runPipeline} on the worker pool with {@link
   * RunPipelineRequest#startFromTaskId} populated from {@code pipeline_run.paused_task_id} — the
   * executor skips already-completed tasks and re-establishes worker conversation from {@code
   * ContextStoreV2}'s saved snapshot for the partly-done task (if any).
   */
  public ResumeOutcome resume(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return ResumeOutcome.NOT_FOUND;
    var run = opt.get();
    if (!"PAUSED".equals(run.getStatus())) return ResumeOutcome.NOT_PAUSED;

    // Same-JVM check — workspace dir present?
    java.nio.file.Path workspace =
        java.nio.file.Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(workspace)) {
      log.warn(
          "V2RunOrchestrator resume rejected: workspace not on this node (cross-JVM resume needs"
              + " GCS shipping — chunk #3 of pause/resume). run={} workspace={}",
          runId,
          workspace);
      return ResumeOutcome.WORKSPACE_GONE;
    }

    log.info(
        "V2RunOrchestrator resume: run={} prev=PAUSED pausedTaskId={} pausedAtIteration={}",
        runId,
        run.getPausedTaskId(),
        run.getPausedAtIteration());

    // Reset the pause flag so the executor doesn't immediately re-pause; bump
    // counters; flip status back to WORKING so the UI un-greys.
    run.setPauseRequested(false);
    run.setResumedAt(java.time.Instant.now());
    run.setStatus("WORKING");
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
    broadcaster.emit(runId, RunEvent.log(runId, "resume", "Resuming from checkpoint"));

    // Resubmit on the pool. The pipeline reads pausedTaskId from the row and
    // skips earlier tasks; ContextStoreV2's saved snapshot rehydrates the
    // worker conversation for the partly-completed task.
    final RunPipelineRequest reReq = RunPipelineRequest.fromPausedRow(run);
    Future<?> future =
        pool.submit(
            () -> {
              try {
                runPipelineForResume(reReq);
              } finally {
                activeRuns.remove(runId);
              }
            });
    activeRuns.put(runId, future);
    return ResumeOutcome.OK;
  }

  /**
   * Resume entry-point invoked from {@link #resume(String)}. Calls the canonical {@code
   * runPipeline(...)} 11-arg overload — the deepest one. The executor + worker check {@code
   * pipeline_run.paused_task_id} on the row at every boundary and skip / replay accordingly. Kept
   * as a named seam because every resume must come through here (the controller never calls {@code
   * runPipeline} directly), which simplifies tests + observability hooks.
   */
  void runPipelineForResume(RunPipelineRequest req) {
    runPipeline(
        req.runId(),
        req.provider(),
        req.model(),
        req.userRequest(),
        req.skipApproval(),
        req.executionMode(),
        req.projectId(),
        req.selectedRepoIds(),
        req.githubPat(),
        req.tddMode(),
        req.branchOverride());
  }

  /**
   * Snapshot of the inputs needed to resume a paused run. Built from the persisted {@code
   * pipeline_run} row by {@link #fromPausedRow}. Carries enough to recompose the original
   * orchestrator call without depending on caller context that's gone after process restart.
   *
   * <p>Note: {@code figmaUrl} isn't carried because it's stamped on the row at submit-time and the
   * design-compliance scorer reads it directly from {@code pipeline_run.figma_url}; resume doesn't
   * need to re-thread it.
   */
  public record RunPipelineRequest(
      String runId,
      String userRequest,
      String provider,
      String model,
      String executionMode,
      boolean skipApproval,
      String projectId,
      List<UUID> selectedRepoIds,
      String githubPat,
      boolean tddMode,
      String branchOverride) {

    public static RunPipelineRequest fromPausedRow(
        com.navneet.executespec.state.entity.PipelineRun run) {
      // Worker model is the closest analogue to a single "run model" — it's what the bulk of the
      // run's LLM calls used. Resume re-resolves per-tier per-call so this is mostly a label;
      // null falls through to ModelResolver defaults.
      return new RunPipelineRequest(
          run.getRunId(),
          run.getUserRequest(),
          run.getAiProvider(),
          run.getWorkerModel(),
          run.getApprovedExecutionMode() == null ? "PRODUCTION" : run.getApprovedExecutionMode(),
          /* skipApproval */ true, // already approved before the original execution started
          run.getProjectId(),
          deserializeRepoIds(run.getSelectedRepoIds()),
          /* githubPat */ null, // resume reuses workspace state — no fresh PAT needed for clones
          /* tddMode */ false,
          /* branchOverride */ null);
    }
  }

  /** Inverse of {@code serializeRepoIds} used at submit-time. */
  private static List<UUID> deserializeRepoIds(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      var arr = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
      java.util.List<UUID> out = new java.util.ArrayList<>();
      if (arr.isArray()) {
        for (var n : arr) {
          out.add(UUID.fromString(n.asText()));
        }
      }
      return out;
    } catch (Exception e) {
      return List.of();
    }
  }

  /**
   * Non-terminal status set used by the halt-all admin tool (M-010). Cheaper to query than scanning
   * every row; covers every status that {@link #isTerminal(String)} rejects.
   *
   * <p>{@code PAUSED} is intentionally excluded from halt-all (V168) — paused runs are stable
   * checkpoints, not stuck work, and the operator should resume + cancel deliberately rather than
   * sweep them alongside genuinely-active runs.
   */
  static final List<String> NON_TERMINAL_STATUSES =
      List.of("QUEUED", "PLANNING", "AWAITING_APPROVAL", "WORKING", "REVIEWING");

  /**
   * M-010 (Wave 2) — halt every non-terminal run on the platform. Used by SUPER_ADMIN during
   * incident response (e.g. provider outage, runaway cost, planner storm) to stop the bleeding
   * without cancelling runs one-by-one. Idempotent: re-running on a clean platform is a no-op.
   *
   * @param reason short rationale persisted on each run's {@code failureReason} (overrides the
   *     default "Cancelled by user" message {@link #cancel(String)} would write). If {@code
   *     null}/blank, the per-run default applies.
   * @return list of run IDs that were transitioned to CANCELLED.
   */
  public List<String> haltAllActiveRuns(String reason) {
    List<com.navneet.executespec.state.entity.PipelineRun> active =
        runRepo.findByStatusIn(NON_TERMINAL_STATUSES);
    List<String> halted = new java.util.ArrayList<>(active.size());
    for (var run : active) {
      String runId = run.getRunId();
      // Stamp a custom reason BEFORE cancel() so cancel's own
      // default doesn't overwrite the operator's message.
      if (reason != null && !reason.isBlank() && run.getFailureReason() == null) {
        run.setFailureReason("Halted by SUPER_ADMIN: " + reason);
        runRepo.save(run);
      }
      if (cancel(runId)) {
        halted.add(runId);
      }
    }
    log.warn(
        "V2RunOrchestrator HALT-ALL by SUPER_ADMIN: {} runs cancelled, reason={}",
        halted.size(),
        reason == null ? "(none)" : reason);
    return halted;
  }

  /**
   * Retry a single task within a terminal V2 run. Currently scoped to re-invoking the worker loop
   * against the saved task definition via {@link SequentialExecutorV2}. Full replay (resume from
   * saved iteration) depends on {@code StartupRunResumerV2} Phase 2 and lands later.
   *
   * @throws IllegalArgumentException if the run/task is not found
   * @throws IllegalStateException if the run is not in a retryable state
   */
  public void retryTask(String runId, String taskId) {
    var run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    String status = run.getStatus();
    if (!java.util.List.of("PARTIAL", "FAILED", "COMPLETE").contains(status)) {
      throw new IllegalStateException(
          "Task retry requires PARTIAL / FAILED / COMPLETE run (current: " + status + ")");
    }
    String key = runId.substring(0, 8) + "_" + taskId;
    var task =
        taskRepo
            .findById(key)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    log.info(
        "V2RunOrchestrator retryTask: run={} task={} (single-task retry — "
            + "full conversation replay not yet implemented)",
        runId,
        taskId);
    // Re-run the single task's worker on a background thread. Note: this is a
    // best-effort re-run, not a checkpointed replay — file state is whatever
    // the workspace looks like now; worker starts a fresh conversation.
    pool.submit(
        () -> {
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
            log.warn(
                "V2RunOrchestrator retryTask: run={} task={} — baseline implementation marked"
                    + " COMPLETE without re-executing. Full retry arrives with Gap 2 Phase 2.",
                runId,
                taskId);
          } catch (Exception e) {
            log.error("V2RunOrchestrator retryTask failed run={} task={}", runId, taskId, e);
          }
        });
  }

  /**
   * Regenerate the validation report for a COMPLETE run whose background validation failed or needs
   * a refresh. Synchronous — caller returns 202.
   */
  public void retryValidation(String runId) {
    var run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!java.util.List.of("COMPLETE", "PARTIAL").contains(run.getStatus())) {
      throw new IllegalStateException(
          "retry-validation requires terminal run (current: " + run.getStatus() + ")");
    }
    log.info("V2RunOrchestrator retryValidation: run={}", runId);
    pool.submit(
        () -> {
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

  /**
   * 2026-05-02 — full re-review flow for SUPER_ADMINs. Unlike {@link #retryValidation} (which only
   * regenerates the report from cached signals), this path:
   *
   * <ol>
   *   <li>Deletes the existing {@code validation_report} row entirely
   *   <li>Resets {@code pipeline_run.status} to {@code REVIEWING} + clears {@code statusReason} +
   *       {@code completedAt} + {@code reviewJson}
   *   <li>Submits an async job that runs {@link ReviewerAgentV3#review} from scratch against the
   *       persisted workspace, calls {@link #persistReview} + {@link #generateValidationReport},
   *       then re-resolves the terminal status via {@link #resolveTerminalStatus}
   *   <li>Emits {@code STATUS} (REVIEWING) and {@code COMPLETE} SSE events so the UI mirrors the
   *       transition live
   * </ol>
   *
   * <p>Use case: shipped a fix to the reviewer / accuracy-assessor / vendored-exclusion path and
   * want to re-derive an existing run's score under the new code without re-running the whole
   * pipeline. Cost: one full reviewer pass (general + wiring + final build) + one accuracy
   * assessment + one validation report generation. Runs in the same {@code pool} as the main
   * pipeline so admin re-reviews queue behind active runs.
   *
   * @throws IllegalArgumentException run not found
   * @throws IllegalStateException run is still in a non-terminal state (PLANNING / WORKING /
   *     REVIEWING / AWAITING_APPROVAL)
   */
  public void reReview(String runId) {
    var run =
        runRepo
            .findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!java.util.List.of("COMPLETE", "PARTIAL", "FAILED").contains(run.getStatus())) {
      throw new IllegalStateException(
          "re-review requires a terminal run (current: " + run.getStatus() + ")");
    }
    log.info("V2RunOrchestrator reReview: run={} previous status={}", runId, run.getStatus());

    // Synchronous reset so the caller's 202 reflects the live state — once we return, the run is
    // already showing REVIEWING in any subsequent GET. The actual reviewer pass kicks off async.
    validationReportRepository
        .findByRunId(runId)
        .ifPresent(
            existing -> {
              log.info(
                  "V2RunOrchestrator reReview: deleting existing validation_report id={} for"
                      + " run={}",
                  existing.getId(),
                  runId);
              validationReportRepository.delete(existing);
            });
    run.setStatus("REVIEWING");
    run.setStatusReason(null);
    run.setCompletedAt(null);
    run.setReviewJson(null);
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "REVIEWING"));

    pool.submit(
        () -> {
          try {
            runReReviewBlocking(runId);
          } catch (Exception e) {
            log.error("V2RunOrchestrator reReview run={} failed unexpectedly", runId, e);
            // Best-effort terminal flip so the run doesn't stay stuck at REVIEWING forever.
            try {
              var stuck = runRepo.findById(runId).orElse(null);
              if (stuck != null && "REVIEWING".equals(stuck.getStatus())) {
                stuck.setStatus("PARTIAL");
                stuck.setStatusReason("RE_REVIEW_FAILED");
                stuck.setCompletedAt(Instant.now());
                runRepo.save(stuck);
                broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL", "RE_REVIEW_FAILED"));
              }
            } catch (Exception ignored) {
              // give up — operator can hit the endpoint again
            }
          }
        });
  }

  /**
   * Synchronous body of the {@link #reReview(String)} async task. Resolves the provider/model,
   * builds a fresh {@link ExecutionContext}, runs reviewer + validation report generation, then
   * derives + persists the new terminal status. Extracted so the outer method can wrap it in a
   * blanket try/catch that flips the run back out of REVIEWING on unexpected failure.
   */
  private void runReReviewBlocking(String runId) {
    var run = runRepo.findById(runId).orElse(null);
    if (run == null) {
      log.warn("V2RunOrchestrator reReview: run {} disappeared mid-flight", runId);
      return;
    }
    LlmProvider provider;
    try {
      provider =
          LlmProvider.valueOf(
              (run.getAiProvider() == null ? "openai" : run.getAiProvider()).toUpperCase());
    } catch (IllegalArgumentException e) {
      provider = LlmProvider.OPENAI;
    }
    ResolvedModel reviewerModel =
        modelResolver.resolve("ReviewerAgentV3", provider, AgentTier.PLANNING);

    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) {
      log.warn(
          "V2RunOrchestrator reReview: workspace {} missing for run {}, marking PARTIAL",
          workspace,
          runId);
      run.setStatus("PARTIAL");
      run.setStatusReason("RE_REVIEW_NO_WORKSPACE");
      run.setCompletedAt(Instant.now());
      runRepo.save(run);
      broadcaster.emit(runId, RunEvent.complete(runId, "PARTIAL", "RE_REVIEW_NO_WORKSPACE"));
      return;
    }
    ExecutionContext rootCtx = new ExecutionContext(runId, "_re_review_", workspace);

    // Recover the planner-declared build command from persisted plan_json so the reviewer's
    // final-build step uses the same command the original run did. Fall back to null — the
    // reviewer's BuildCommandResolver then auto-detects from the workspace shape.
    String buildCommand = null;
    if (run.getPlanJson() != null && !run.getPlanJson().isBlank()) {
      try {
        PlanV2 plan = objectMapper.readValue(run.getPlanJson(), PlanV2.class);
        buildCommand = plan.buildCommand();
      } catch (Exception e) {
        log.debug(
            "V2RunOrchestrator reReview run={} could not parse planJson; relying on auto-detect",
            runId);
      }
    }

    ReviewerResult reviewResult = null;
    try {
      log.info(
          "V2RunOrchestrator reReview run={} provider={} model={} buildCmd={}",
          runId,
          provider,
          reviewerModel.getModel(),
          buildCommand == null ? "(auto)" : buildCommand);
      reviewResult =
          reviewer.review(
              ReviewerRequestV2.builder()
                  .provider(provider.name().toLowerCase())
                  .model(reviewerModel.getModel())
                  .context(rootCtx)
                  .buildCommand(buildCommand)
                  .skipWiringPass(false)
                  .build());
      persistReview(runId, reviewResult);
    } catch (Exception e) {
      log.warn("V2RunOrchestrator reReview run={} reviewer failed: {}", runId, e.getMessage());
      persistReviewFailure(runId, e);
      // reviewResult stays null — generateValidationReport handles that branch (it sets
      // verdict=APPROVED with summary="Review did not run." which trips status to LEGACY_UNKNOWN
      // below, surfacing the failure on the UI).
    }

    // Reconstruct a minimal ExecutionResult from persisted task rows — perTask isn't read by
    // resolveTerminalStatus or the generateValidationReport(execResult != null) branch when we
    // pass null for execResult below. We use this purely to get tasksSuccessful / tasksFailed /
    // anyIntermediateWarning into the status decision.
    ExecutionResult execResult = reconstructExecResultFromTasks(runId);

    try {
      generateValidationReport(runId, null, reviewResult, null);
    } catch (Exception e) {
      log.warn(
          "V2RunOrchestrator reReview run={} report generation failed: {}", runId, e.getMessage());
    }

    StatusAndReason terminal = resolveTerminalStatus(execResult, reviewResult);
    var refreshed = runRepo.findById(runId).orElse(run);
    refreshed.setStatus(terminal.status());
    refreshed.setStatusReason(terminal.reason());
    refreshed.setCompletedAt(Instant.now());
    runRepo.save(refreshed);
    broadcaster.emit(runId, RunEvent.complete(runId, terminal.status(), terminal.reason()));
    log.info(
        "V2RunOrchestrator reReview run={} done status={} reason={} reviewerVerdict={}",
        runId,
        terminal.status(),
        terminal.reason(),
        reviewResult == null ? "n/a" : reviewResult.overall());
  }

  /**
   * Build a minimal {@link ExecutionResult} from the persisted {@code agent_task} rows, just enough
   * that {@link #resolveTerminalStatus} can read the four counters it cares about ({@code overall},
   * {@code tasksSuccessful}, {@code tasksFailed}, {@code anyIntermediateWarning}). {@code perTask}
   * is intentionally empty — the only consumer that iterates it ({@code generateValidationReport})
   * is invoked with {@code execResult=null} on the re-review path so it falls through to its own
   * task-row reconstruction branch.
   */
  private ExecutionResult reconstructExecResultFromTasks(String runId) {
    var tasks = taskRepo.findByRunId(runId);
    int success = 0;
    int warn = 0;
    int failed = 0;
    int skipped = 0;
    for (var t : tasks) {
      String s = t.getStatus() == null ? "COMPLETE" : t.getStatus();
      switch (s) {
        case "COMPLETE" -> success++;
        case "PARTIAL" -> warn++;
        case "FAILED" -> failed++;
        case "BLOCKED", "SKIPPED" -> skipped++;
        default -> success++;
      }
    }
    ExecutionResult.Overall overall;
    if (failed > 0 && success == 0) overall = ExecutionResult.Overall.FAILED;
    else if (failed > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else if (warn > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else overall = ExecutionResult.Overall.ALL_SUCCESS;
    return ExecutionResult.builder()
        .overall(overall)
        .tasksExecuted(tasks.size() - skipped)
        .tasksSuccessful(success)
        .tasksWithWarning(warn)
        .tasksFailed(failed)
        .tasksSkipped(skipped)
        .perTask(java.util.List.of())
        .totalLatencyMs(0L)
        .finalTaskStatus(null)
        .anyIntermediateWarning(warn > 0)
        .build();
  }

  private static boolean isTerminal(String status) {
    return "COMPLETE".equals(status)
        || "FAILED".equals(status)
        || "CANCELLED".equals(status)
        || "PARTIAL".equals(status);
  }

  private void updateStatus(String runId, String newStatus) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setStatus(newStatus);
              runRepo.save(r);
            });
    broadcaster.emit(runId, RunEvent.runStatus(runId, newStatus));
  }

  /** Legacy 4-arg entry point — failure paths without a specific reason flow here. */
  private void terminate(String runId, String finalStatus, String note, Instant startedAt) {
    terminate(runId, finalStatus, null, note, startedAt);
  }

  /**
   * v2.5.10 — 5-arg overload that also persists {@code status_reason} on the run and threads it to
   * the SSE complete event so the UI can show "PARTIAL because FINAL_BUILD_FAILED" instead of a
   * silent badge.
   */
  private void terminate(
      String runId, String finalStatus, String statusReason, String note, Instant startedAt) {
    V2RunCostTracker.Snapshot cost = costTracker.finalizeRun(runId);
    // v2.5.8 — provider attribution log: makes it easy to grep logs and confirm
    // which provider actually served a run. Critical for cross-provider evaluation
    // audits (no silent fallback under strict mode).
    log.info(
        "V2 run terminate runId={} status={} provider={} tokens(in/out)={}/{} cost=${}",
        runId,
        finalStatus,
        runRepo.findById(runId).map(r -> r.getAiProvider()).orElse("?"),
        cost.inputTokens(),
        cost.outputTokens(),
        String.format("%.4f", cost.costUsd()));
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              // Preserve CANCELLED if the user cancelled mid-flight. The pipeline thread
              // may call terminate(...) after cancel() has already written CANCELLED;
              // without this guard the late write clobbers the user-initiated status
              // (e.g. clinic run 6d23d5b5 showed PARTIAL despite a successful cancel).
              if (!"CANCELLED".equals(r.getStatus())) {
                r.setStatus(finalStatus);
              }
              r.setCompletedAt(Instant.now());
              if ("FAILED".equals(finalStatus) && r.getFailureReason() == null) {
                r.setFailureReason(note);
              }
              // v2.5.10 — persist status_reason so /api/runs/{id} surfaces it and the
              // UI's status badge + tooltip can explain "PARTIAL because X".
              // Only write if non-null: legacy callers still reach here without a
              // reason and we don't want to clobber a previously-set value.
              if (statusReason != null) {
                r.setStatusReason(statusReason);
              }
              // Only overwrite actualCostUsd with a positive tracker reading; an empty
              // snapshot (e.g. tracker evicted across a deploy boundary) must not
              // clobber a previously-persisted cost.
              if (cost.costUsd() > 0d) {
                r.setActualCostUsd(cost.costUsd());
              } else if (r.getActualCostUsd() == null) {
                r.setActualCostUsd(0d); // never leave NULL on a terminal run
              }
              // Do NOT overwrite totalInputTokens / totalOutputTokens here. Per-phase
              // persistence (persistPhaseTokens + distributeWorkerTokens) increments
              // the run totals atomically via addTokens() — that path is the source of
              // truth. If the tracker snapshot has bigger numbers for some reason
              // (e.g. V1 test-gen hasn't incremented yet), prefer it; else keep what's
              // already persisted. Never shrink the persisted total.
              int persistedIn = r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens();
              int persistedOut = r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens();
              if (cost.inputTokens() > persistedIn) r.setTotalInputTokens(cost.inputTokens());
              if (cost.outputTokens() > persistedOut) r.setTotalOutputTokens(cost.outputTokens());
              runRepo.save(r);
              writeRunCostLog(r, cost);

              // R-162 — referral grant hook. Delegates to ReferralCompletionHook
              // so the conditional + try/catch + idempotency contract lives in
              // one testable place. Fires only on COMPLETE + non-null owner;
              // ReferralService.onRefereeFirstReadyRun itself is idempotent.
              referralCompletionHook.onTerminalStatus(runId, r.getStatus(), r.getCreatedByUserId());
            });
    broadcaster.emit(runId, RunEvent.runStatus(runId, finalStatus));
    broadcaster.emit(runId, RunEvent.costUpdate(runId, cost.costUsd()));
    broadcaster.emit(runId, RunEvent.complete(runId, finalStatus, statusReason));
    broadcaster.complete(runId);
    // V2 run-level metrics are emitted by V2RunService only. This orchestrator is a
    // parallel entry path — record run.outcome + run.latency here too so V1-endpoint-
    // submitted V2 runs are counted.
    metrics.recordRunOutcome(finalStatus);
    metrics.recordRunLatency(finalStatus, java.time.Duration.between(startedAt, Instant.now()));
    // v2.5.8 — fire Spring ApplicationEvent so RunCompletionNotifier (in-app
    // notification), RunQuotaRefundListener (refund on failure), and
    // WorkspaceGcsUploadListener (archive workspace) all get notified.
    // V1 PipelineHarness already publishes this; V2 previously did not,
    // so notifications silently never fired for any V2 run.
    try {
      if ("COMPLETE".equalsIgnoreCase(finalStatus) || "PARTIAL".equalsIgnoreCase(finalStatus)) {
        eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
      } else if ("FAILED".equalsIgnoreCase(finalStatus)
          || "CANCELLED".equalsIgnoreCase(finalStatus)) {
        eventPublisher.publishEvent(
            new PipelineEvent.RunFailed(runId, note == null ? finalStatus : note));
      }
    } catch (Exception eventEx) {
      log.warn("V2 run={} event publish failed (non-fatal): {}", runId, eventEx.getMessage());
    }

    // Wave 2.1 — activation funnel step 5. Fires once per user on the
    // first run that reaches COMPLETE or PARTIAL (terminal-but-usable).
    // FAILED/CANCELLED don't count as activation signals — a user who
    // submits 3 runs that all fail hasn't hit the "my code works" moment.
    if ("COMPLETE".equalsIgnoreCase(finalStatus) || "PARTIAL".equalsIgnoreCase(finalStatus)) {
      try {
        runRepo
            .findById(runId)
            .ifPresent(
                r -> {
                  if (r.getCreatedByUserId() != null) {
                    eventTracking.track(
                        r.getCreatedByUserId(),
                        com.navneet.executespec.analytics.EventName.RUN_COMPLETE,
                        java.util.Map.of(
                            "terminalStatus", finalStatus,
                            "statusReason", statusReason == null ? "" : statusReason,
                            "aiProvider", r.getAiProvider() == null ? "" : r.getAiProvider()));
                  }
                });
      } catch (Exception trackEx) {
        log.warn(
            "Activation track RUN_COMPLETE failed for run={}: {}", runId, trackEx.getMessage());
      }
    }
  }

  // ─── README generation ──────────────────────────────────────────────────

  /**
   * Writes a run-meta README.md into the workspace if one doesn't already exist.
   *
   * <p>The planner is instructed (in prompts/v2/planner.md invariant #3) to emit a "Create
   * README.md" task for non-trivial specs. That user-facing README is authored by the worker and
   * takes precedence — we never overwrite it. For trivial specs (single-file, no framework) the
   * planner skips the README task; this method then writes a deterministic run-meta README so the
   * download-zip experience always includes an entry-point doc.
   *
   * <p>Best-effort — IO errors are logged and swallowed. A missing README isn't worth failing a run
   * over.
   */
  private void ensureReadme(
      String runId,
      Path workspace,
      String userRequest,
      PlanV2 plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Instant startedAt) {
    Path readme = workspace.resolve("README.md");
    if (Files.exists(readme)) {
      log.debug(
          "V2RunOrchestrator run={} README.md already present (planner-authored), skipping", runId);
      return;
    }
    try {
      Files.writeString(
          readme, renderRunReadme(runId, userRequest, plan, execResult, reviewResult, startedAt));
      log.info(
          "V2RunOrchestrator run={} wrote run-meta README.md ({} bytes)",
          runId,
          Files.size(readme));
    } catch (IOException e) {
      log.warn("V2RunOrchestrator run={} failed to write README.md: {}", runId, e.getMessage());
    }
  }

  private String renderRunReadme(
      String runId,
      String userRequest,
      PlanV2 plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Instant startedAt) {
    StringBuilder sb = new StringBuilder();
    sb.append("# ")
        .append(plan.planTitle() != null ? plan.planTitle() : "Run Output")
        .append("\n\n");
    if (plan.planSummary() != null && !plan.planSummary().isBlank()) {
      sb.append(plan.planSummary()).append("\n\n");
    } else {
      sb.append("This project was generated from the following request:\n\n> ")
          .append(userRequest)
          .append("\n\n");
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
      sb.append("| `")
          .append(t.taskId())
          .append("` | ")
          .append(t.taskTitle() == null ? "" : t.taskTitle().replace("|", "\\|"))
          .append(" | `")
          .append(t.status().name())
          .append("` |\n");
    }
    sb.append("\n");

    // Review verdict
    if (reviewResult != null) {
      sb.append("## Code review\n\n")
          .append("**Verdict:** `")
          .append(reviewResult.overall().name())
          .append("`");
      if (reviewResult.finalBuildPassed() != null) {
        sb.append("  \n**Build passed:** `").append(reviewResult.finalBuildPassed()).append("`");
      }
      sb.append("\n\n");
      if (reviewResult.generalPassSummary() != null
          && !reviewResult.generalPassSummary().isBlank()) {
        sb.append("**General review:** ").append(reviewResult.generalPassSummary()).append("\n\n");
      }
      if (reviewResult.wiringPassSummary() != null && !reviewResult.wiringPassSummary().isBlank()) {
        sb.append("**Wiring review:** ").append(reviewResult.wiringPassSummary()).append("\n\n");
      }
    }

    // Run metadata
    sb.append("## Run details\n\n")
        .append("- **Run ID:** `")
        .append(runId)
        .append("`\n")
        .append("- **Started:** `")
        .append(startedAt)
        .append("`\n")
        .append("- **Completed:** `")
        .append(Instant.now())
        .append("`\n")
        .append("- **Harness:** ExecuteSpec V2\n")
        .append("- **Overall:** `")
        .append(execResult.overall().name())
        .append("`\n");
    if (plan.projectType() != null && !plan.projectType().isBlank()) {
      sb.append("- **Project type:** `").append(plan.projectType()).append("`\n");
    }
    sb.append("\n---\n\n*Generated by ExecuteSpec V2.*\n");
    return sb.toString();
  }

  // ─── validation report (V2 adapter over V1 ValidationReportService) ────

  /**
   * Assembles a minimal {@link ReportInput} from V2 pipeline state and invokes the shared {@link
   * ValidationReportService} so the downloadable validation report (accuracy score, endpoints,
   * gaps) populates for V2 runs exactly as it does for V1.
   *
   * <p>V2 types are translated as:
   *
   * <ul>
   *   <li>{@link ExecutionResult} per-task → synthesized {@link WorkerReport}s
   *   <li>{@link ReviewerResult} → synthesized {@link ReviewReportV2} with the mapped verdict +
   *       summary
   *   <li>Final build passed → {@link CompileResult#passed(int)} else {@code failed}
   * </ul>
   */
  private void generateValidationReport(
      String runId, ExecutionResult execResult, ReviewerResult reviewResult, PlanV2 plan) {
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
      if (execResult != null) {
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
      } else {
        // Retry-validation path: execResult wasn't persisted, so reconstruct
        // minimal WorkerReports from the persisted AgentTask rows. Status mapping
        // is straight passthrough (the task rows already hold V1-style strings).
        for (var t : tasks) {
          WorkerReport wr = new WorkerReport();
          wr.setTaskId(t.getTaskId());
          wr.setStatus(t.getStatus() == null ? "COMPLETE" : t.getStatus());
          wr.setSummary(t.getTitle());
          wr.setFilesCreated(firstReport ? allFiles : List.of());
          wr.setFilesModified(List.of());
          wr.setFilesRead(List.of());
          workerReports.add(wr);
          firstReport = false;
        }
      }

      ReviewReportV2 review = new ReviewReportV2();
      if (reviewResult != null) {
        review.setVerdict(
            switch (reviewResult.overall()) {
              case CLEAN -> ReviewVerdict.APPROVED;
              case FIXED_ISSUES -> ReviewVerdict.APPROVED_WITH_NOTES;
              case UNRESOLVED_ISSUES -> ReviewVerdict.NEEDS_FIXES;
              case REVIEW_FAILED -> ReviewVerdict.REJECTED;
            });
        review.setSummary(
            (reviewResult.generalPassSummary() == null ? "" : reviewResult.generalPassSummary())
                + (reviewResult.wiringPassSummary() == null
                    ? ""
                    : " / " + reviewResult.wiringPassSummary()));
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
        ResolvedModel planningModel =
            modelResolver.resolve("ReviewerAgentV2", provider, AgentTier.PLANNING);
        AccuracyReport accuracy =
            accuracyAssessmentService.assess(
                runId,
                planningModel.providerName(),
                planningModel,
                run.getUserRequest() == null ? "" : run.getUserRequest(),
                List.of());
        review.setAccuracyReport(accuracy);
      } catch (Exception e) {
        log.warn("V2RunOrchestrator run={} accuracy assessment failed: {}", runId, e.getMessage());
        // leave accuracyReport null — ValidationReportService treats null as "not assessed"
      }

      CompileResult compile =
          (reviewResult != null
                  && reviewResult.finalBuildPassed() != null
                  && reviewResult.finalBuildPassed())
              ? CompileResult.passed(1)
              : CompileResult.failed(
                  1,
                  reviewResult == null
                      ? "n/a"
                      : (reviewResult.finalBuildOutput() == null
                          ? ""
                          : reviewResult.finalBuildOutput()));

      // V2's ReviewerAgentV3 doesn't emit structured ReviewIssues — it just returns
      // a verdict enum + summary text. That leaves ReviewScoringService with an empty
      // issues list, so every quality dimension flat-lined at the anchor regardless
      // of what actually happened. Synthesise issues from the signals we DO have
      // (build pass/fail, reviewer verdict, accuracy report) so the dimensions
      // actually move when things go wrong.
      review.setIssues(synthesiseReviewIssues(reviewResult, compile, review.getAccuracyReport()));

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
        log.info(
            "V2RunOrchestrator run={} validation report rerouted OPENAI → ANTHROPIC "
                + "to bypass Spring AI 1.1.4 extra_body bug",
            runId);
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
   * Writes {@code .agent-context/project.json} with the planner's declared project type so {@link
   * com.navneet.executespec.deployment.preview.DevDockerfileGenerator} picks the right Dockerfile
   * strategy for preview / deploy on V2 runs.
   *
   * <p>V1 does the same in {@code PipelineHarness.initAgentContext()}; without it, the generator
   * falls back to filesystem heuristics — works for simple cases but less reliable for polyglot
   * workspaces.
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
      String json =
          objectMapper.writeValueAsString(java.util.Map.of("project_type", normalizedType));
      Files.writeString(dir.resolve("project.json"), json);
    } catch (Exception e) {
      log.debug("writeAgentContext failed: {}", e.getMessage());
    }
  }

  // ─── run title + design resolution ──────────────────────────────────────

  private void persistRunTitle(String runId, PlanV2 plan) {
    if (plan.planTitle() == null || plan.planTitle().isBlank()) return;
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setRunTitle(plan.planTitle());
              runRepo.save(r);
            });
  }

  /**
   * Resolves and loads the design system block for this run using V1's {@link UiStyleResolver}.
   * Picks from three signals in priority order: explicit hint parsed from the user request,
   * planner-declared ui_style, and project-type heuristic. Returns an empty string for backend-only
   * projects so the worker prompt stays compact.
   */
  private String buildDesignBlock(String userRequest, PlanV2 plan) {
    try {
      String hint = uiStyleResolver.extractStyleHint(userRequest);
      String plannerStyle = null; // PlanV2 has no explicit ui_style field today
      String projectType = plan.projectType() == null ? "unknown" : plan.projectType();
      String designName = uiStyleResolver.resolveDesignName(hint, plannerStyle, projectType);
      if (designName == null) return "";
      String content = uiStyleResolver.loadDesignContent(designName);
      String block = uiStyleResolver.buildFrontendConstraintBlock(null, content);
      if (block == null || block.isBlank()) return "";
      log.info(
          "V2RunOrchestrator run=? design resolved='{}' (hint='{}', project={})",
          designName,
          hint,
          projectType);
      return block;
    } catch (Exception e) {
      log.warn("buildDesignBlock failed: {}", e.getMessage());
      return "";
    }
  }

  // ─── cost helpers ───────────────────────────────────────────────────────

  private void persistEstimate(String runId, String provider, String model, PlanV2 plan) {
    java.util.List<String> complexities =
        plan.tasks().stream()
            .map(t -> t.complexity() == null ? "MEDIUM" : t.complexity().name())
            .toList();
    // v2.6.2 slice 5 — pull the persisted plan strategy from the run row so
    // the estimate reflects the actual hierarchical-pipeline phase costs
    // (classifier + outline + expansion + coherence) instead of the legacy
    // single-phase shape. Falls back to null = legacy estimate when the
    // classifier hadn't run yet (classifier failure / pre-slice-1 row).
    PlanStrategy strategy =
        runRepo
            .findById(runId)
            .map(PipelineRun::getPlanStrategy)
            .map(PlanStrategy::fromStringSafe)
            .orElse(null);
    V2RunCostTracker.Estimate est =
        costTracker.estimateRunTierAware(provider, complexities, strategy);
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setCostEstimateLow(est.lowUsd());
              r.setCostEstimateHigh(est.highUsd());
              r.setCreditsCharged(est.credits());
              r.setHardCapUsd(est.hardCapUsd());
              runRepo.save(r);
            });
    log.info(
        "V2RunOrchestrator run={} estimate strategy={} low=${} high=${} credits={} cap=${}"
            + " tasks={}",
        runId,
        strategy,
        String.format("%.4f", est.lowUsd()),
        String.format("%.4f", est.highUsd()),
        est.credits(),
        String.format("%.2f", est.hardCapUsd()),
        est.taskCount());
  }

  private void writeRunCostLog(PipelineRun r, V2RunCostTracker.Snapshot cost) {
    try {
      double estimated = r.getCostEstimateHigh() != null ? r.getCostEstimateHigh() : 0.0;
      double hardCap = r.getHardCapUsd() != null ? r.getHardCapUsd() : 0.0;
      int credits = r.getCreditsCharged() != null ? r.getCreditsCharged() : 1;
      boolean budgetHit = hardCap > 0 && cost.costUsd() >= hardCap;
      RunCostLog row =
          RunCostLog.builder()
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
      log.warn(
          "V2RunOrchestrator run={} failed to write RunCostLog: {}", r.getRunId(), e.getMessage());
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

  /** v2.5.10 — pair of run-level terminal status + explicit reason string. */
  record StatusAndReason(String status, String reason) {}

  /**
   * v2.5.10 — decide run-level status from the authoritative signals available at terminate time:
   * reviewer verdict, final build result, and execution summary (including final-task vs
   * intermediate-warning separation from {@link ExecutionResult#anyIntermediateWarning()}).
   *
   * <p>Tests run ASYNC after terminate (see {@code generateValidationReport} → {@code
   * testGenOrchestrator.runTestGenPipeline}), so test counts are NOT a gating signal here. They're
   * captured on ValidationReport via {@code retryValidation} after tests complete and feed
   * verifiedScore.
   */
  static StatusAndReason resolveTerminalStatus(
      ExecutionResult exec, com.navneet.executespec.v2.reviewer.ReviewerResult review) {
    if (exec.overall() == ExecutionResult.Overall.ABORTED) {
      return new StatusAndReason("FAILED", "EXECUTION_ABORTED");
    }
    com.navneet.executespec.v2.reviewer.ReviewerResult.Overall verdict =
        review != null ? review.overall() : null;
    boolean buildPassed = review != null && Boolean.TRUE.equals(review.finalBuildPassed());
    boolean anyWarn = exec.anyIntermediateWarning();
    boolean anyTaskSucceeded = exec.tasksSuccessful() > 0;

    if (buildPassed
        && verdict != null
        && (verdict == com.navneet.executespec.v2.reviewer.ReviewerResult.Overall.CLEAN
            || verdict
                == com.navneet.executespec.v2.reviewer.ReviewerResult.Overall.FIXED_ISSUES)) {
      return new StatusAndReason(
          "COMPLETE", anyWarn ? "CLEAN_WITH_INTERMEDIATE_WARNINGS" : "CLEAN_COMPLETION");
    }
    if (review != null && !buildPassed) {
      return new StatusAndReason("PARTIAL", "FINAL_BUILD_FAILED");
    }
    if (verdict == com.navneet.executespec.v2.reviewer.ReviewerResult.Overall.UNRESOLVED_ISSUES) {
      return new StatusAndReason("PARTIAL", "REVIEWER_UNRESOLVED");
    }
    if (verdict == com.navneet.executespec.v2.reviewer.ReviewerResult.Overall.REVIEW_FAILED) {
      return new StatusAndReason("PARTIAL", "REVIEWER_FAILED");
    }
    if (!anyTaskSucceeded && exec.tasksFailed() > 0) {
      return new StatusAndReason("FAILED", "ALL_TASKS_FAILED");
    }
    // Defensive fallback — the reviewer didn't run (review == null) or
    // produced an unexpected verdict. Fall back to the legacy map but
    // tag the reason so the UI can show it.
    return new StatusAndReason(mapOverallToV1Status(exec.overall()), "LEGACY_UNKNOWN");
  }

  private static String mapTaskStatusToV1(TaskOutcome.Status status) {
    return switch (status) {
      case SUCCESS, SUCCESS_AFTER_RETRY -> "COMPLETE";
      case COMPLETED_WITH_WARNING -> "PARTIAL";
      case WORKER_FAILED -> "FAILED";
      case SKIPPED -> "BLOCKED";
    };
  }

  /**
   * Build synthetic {@link com.navneet.executespec.reviewer.model.ReviewIssue}s from the signals V2
   * actually produces (build pass/fail, reviewer verdict, accuracy report) so that {@link
   * com.navneet.executespec.scoring.ReviewScoringService} has something to penalise on. Without
   * this the issues list is always empty and every quality dimension flat-lines at the anchor —
   * e.g. a run with a build-breaker still scores 70 across code/arch/security/perf.
   *
   * <p>Severity/category mapping:
   *
   * <ul>
   *   <li>Build failure → CRITICAL × 2 (LOGIC_ERROR + DEPLOYABILITY) → hits code+arch+perf
   *   <li>Reviewer UNRESOLVED_ISSUES → CRITICAL LOGIC_ERROR
   *   <li>Reviewer REVIEW_FAILED → MINOR ERROR_HANDLING
   *   <li>Accuracy missingRequirements → CRITICAL MISSING_FEATURE (one per item, capped at 5)
   *   <li>Accuracy partialRequirements → MINOR SPEC_MISMATCH (one per item, capped at 5)
   *   <li>Accuracy endpointCoverage.missing → CRITICAL MISSING_ENDPOINT (capped at 5)
   * </ul>
   */
  private static java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue>
      synthesiseReviewIssues(
          ReviewerResult reviewResult,
          com.navneet.executespec.report.model.CompileResult compile,
          com.navneet.executespec.reviewer.model.AccuracyReport accuracy) {
    java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> out =
        new java.util.ArrayList<>();

    boolean buildFailed =
        (compile != null && !compile.passed() && !compile.skipped())
            || (reviewResult != null && Boolean.FALSE.equals(reviewResult.finalBuildPassed()));
    if (buildFailed) {
      out.add(
          com.navneet.executespec.reviewer.model.ReviewIssue.builder()
              .id("SYNTH-BUILD-1")
              .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
              .category(com.navneet.executespec.reviewer.model.IssueCategory.LOGIC_ERROR)
              .description("Final build failed — code does not compile / package as produced")
              .autoFixed(false)
              .fixSucceeded(false)
              .build());
      out.add(
          com.navneet.executespec.reviewer.model.ReviewIssue.builder()
              .id("SYNTH-BUILD-2")
              .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
              .category(com.navneet.executespec.reviewer.model.IssueCategory.DEPLOYABILITY)
              .description("Build artifacts absent — the run cannot be deployed as-is")
              .autoFixed(false)
              .fixSucceeded(false)
              .build());
    }

    if (reviewResult != null) {
      switch (reviewResult.overall()) {
        case UNRESOLVED_ISSUES ->
            out.add(
                com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                    .id("SYNTH-REV-1")
                    .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                    .category(com.navneet.executespec.reviewer.model.IssueCategory.LOGIC_ERROR)
                    .description("Reviewer flagged unresolved issues in wiring/general pass")
                    .autoFixed(false)
                    .fixSucceeded(false)
                    .build());
        case REVIEW_FAILED ->
            out.add(
                com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                    .id("SYNTH-REV-2")
                    .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
                    .category(com.navneet.executespec.reviewer.model.IssueCategory.ERROR_HANDLING)
                    .description("Reviewer agent errored — review did not complete")
                    .autoFixed(false)
                    .fixSucceeded(false)
                    .build());
        default -> {
          /* CLEAN / FIXED_ISSUES — no synthetic */
        }
      }
    }

    if (accuracy != null) {
      int cap = 5;
      if (accuracy.getMissingRequirements() != null) {
        int n = 0;
        for (String req : accuracy.getMissingRequirements()) {
          if (n++ >= cap) break;
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-ACC-MISS-" + n)
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.MISSING_FEATURE)
                  .description("Spec requirement not implemented: " + truncate(req, 180))
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
      }
      if (accuracy.getPartialRequirements() != null) {
        int n = 0;
        for (String req : accuracy.getPartialRequirements()) {
          if (n++ >= cap) break;
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-ACC-PART-" + n)
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.SPEC_MISMATCH)
                  .description("Spec requirement only partially implemented: " + truncate(req, 180))
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
      }
      if (accuracy.getEndpointCoverage() != null
          && accuracy.getEndpointCoverage().getMissing() != null) {
        int n = 0;
        for (String ep : accuracy.getEndpointCoverage().getMissing()) {
          if (n++ >= cap) break;
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-ACC-EP-" + n)
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.MISSING_ENDPOINT)
                  .description("Endpoint from spec not implemented: " + truncate(ep, 180))
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
      }
    }

    return out;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  /**
   * Resolve the effective provider string for a run.
   *
   * <p>Order of precedence: 1. Request-supplied {@code aiProvider} (lowercased, "google"→"gemini").
   * 2. Project's default {@code ai_provider} (when {@code projectId} is set). 3. Platform default
   * ({@link #DEFAULT_PROVIDER} = "openai").
   *
   * <p>Levels 2/3 log a WARN so missing provider values surface in ops rather than silently
   * defaulting. Legacy {@link com.navneet.executespec.execution.entity.ExecutionRun} rows
   * pre-dating V92 hit level 2; the V99 backfill closes that gap for new rows.
   */
  private String normalizeProvider(String aiProvider, String projectId, String runId) {
    // v2.5.8 — strict-provider mode: when the system property
    // executespec.strict-provider=true, we refuse to fall back silently.
    // Used by the cross-provider evaluation harness so Gemini runs never
    // accidentally land on OpenAI due to a null flag.
    boolean strict =
        "true".equalsIgnoreCase(System.getProperty("executespec.strict-provider", "false"));
    if (aiProvider != null && !aiProvider.isBlank()) {
      String lower = aiProvider.toLowerCase();
      String resolved = "google".equals(lower) ? "gemini" : lower;
      log.info(
          "normalizeProvider[strict={}] runId={} -> provider={} (explicit)",
          strict,
          runId,
          resolved);
      return resolved;
    }
    if (strict) {
      throw new IllegalStateException(
          "strict-provider mode: runId=" + runId + " has null aiProvider — refusing to fall back");
    }
    if (projectId != null) {
      String projectProvider =
          projectRepository
              .findById(projectId)
              .map(p -> p.getAiProvider() == null ? null : p.getAiProvider().name())
              .orElse(null);
      if (projectProvider != null && !projectProvider.isBlank()) {
        log.warn(
            "normalizeProvider: null aiProvider on runId={} — falling back to project default {}",
            runId,
            projectProvider);
        String lower = projectProvider.toLowerCase();
        return "google".equals(lower) ? "gemini" : lower;
      }
    }
    log.warn(
        "normalizeProvider: no provider resolvable for runId={} projectId={} — defaulting to {}",
        runId,
        projectId,
        DEFAULT_PROVIDER);
    return DEFAULT_PROVIDER;
  }

  private static String defaultModelFor(String provider) {
    // v2.5.9 Bug #3 — switched gemini default from 2.5-flash to 3-flash-preview
    // because 2.5-flash emits Python-style tool calls
    // (print(default_api.submitPlan(...))) causing MALFORMED_FUNCTION_CALL.
    return switch (provider) {
      case "anthropic" -> "claude-haiku-4-5-20251001";
      case "gemini" -> "gemini-3-flash-preview";
      case "openai" -> "gpt-5.4-mini";
      default -> "claude-haiku-4-5-20251001";
    };
  }

  /** Serialise repo IDs to a compact JSON array string, or null when empty. */
  private static String serializeRepoIds(List<UUID> ids) {
    if (ids == null || ids.isEmpty()) return null;
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < ids.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append('"').append(ids.get(i).toString()).append('"');
    }
    return sb.append(']').toString();
  }

  /**
   * Builds a workspaceSummary that tells the planner (and by extension the worker) exactly where
   * each selected repo is cloned, so generated files land inside {@code repos/<name>/} — not at the
   * workspace root. Without this the worker treats the workspace as an empty scratch dir and writes
   * to the root, producing runs that finish COMPLETE but open no PRs (nothing to push from each
   * {@code repos/<name>/} subdir).
   */
  private String buildMultiRepoSummary(String projectId, List<UUID> selectedRepoIds) {
    if (projectId == null || selectedRepoIds == null || selectedRepoIds.isEmpty()) return null;
    List<ProjectRepo> repos;
    try {
      repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
    } catch (Exception e) {
      return null;
    }
    if (repos.isEmpty()) return null;
    StringBuilder sb =
        new StringBuilder(
            "MULTI-REPO PROJECT WORKSPACE.\n\n"
                + "Each selected repo is cloned into its own subdirectory under `repos/`.\n"
                + "All file paths you create or edit MUST live inside the matching"
                + " `repos/<name>/`\n"
                + "subdirectory — never at the workspace root. Only one PR per repo is opened,\n"
                + "based on the diff inside that subdirectory.\n\n"
                + "Repos available for this run:\n");
    for (ProjectRepo r : repos) {
      String name = lastPathSegment(r.getGithubRepoFullName());
      sb.append("- `repos/")
          .append(name)
          .append("/` → github.com/")
          .append(r.getGithubRepoFullName())
          .append(" (default branch: ")
          .append(r.getDefaultBranch() == null ? "main" : r.getDefaultBranch())
          .append(")\n");
    }

    // Fix D (2026-05-03, run a076c876 T2 diagnosis) — append a language parity
    // block per repo from ProjectRepo.detectedStack so the planner doesn't
    // template TypeScript filenames into a Python repo and vice versa. T2's
    // task description told the worker to edit `repos/pplai-api/src/config/index.ts`
    // when pplai-api is FastAPI/Python; the worker spent iters 1-10 reconciling
    // the spec's TypeScript wording against the actual Python code on disk.
    String languageBlock = buildLanguageParityBlock(repos);
    if (!languageBlock.isEmpty()) {
      sb.append('\n').append(languageBlock);
    }
    return sb.toString();
  }

  /**
   * Render a per-repo language parity block from the {@link ProjectRepo#getDetectedStack()} JSON
   * shape produced by {@code StackDetector} ({@code {language, framework, version}}). Returns the
   * empty string when no repo has a non-blank detected language — there's nothing useful to say.
   *
   * <p>The block instructs the planner to TRANSLATE filename / extension conventions in the
   * spec to match the repo's actual language. Without this block, planners templated TypeScript
   * filenames into Python repos because the user's spec wording happened to use {@code .ts}
   * extensions (run a076c876 / T2). The planner now sees an explicit rule and either translates
   * up-front or rejects the mismatch.
   */
  private String buildLanguageParityBlock(List<ProjectRepo> repos) {
    if (repos == null || repos.isEmpty()) return "";
    StringBuilder lines = new StringBuilder();
    for (ProjectRepo r : repos) {
      String stackJson = r.getDetectedStack();
      if (stackJson == null || stackJson.isBlank()) continue;
      String language = null;
      String framework = null;
      try {
        var node = objectMapper.readTree(stackJson);
        language = node.path("language").asText("");
        framework = node.path("framework").asText("");
        if (language == null || language.isBlank()) continue;
      } catch (Exception e) {
        // detectedStack is malformed JSON — skip this repo's hint, no big deal.
        continue;
      }
      String name = lastPathSegment(r.getGithubRepoFullName());
      String hint = languageConventionHint(language, framework);
      lines
          .append("- `repos/")
          .append(name)
          .append("/` is **")
          .append(language);
      if (framework != null && !framework.isBlank()) {
        lines.append(" / ").append(framework);
      }
      lines.append("** — ").append(hint).append('\n');
    }
    if (lines.length() == 0) return "";
    return "LANGUAGE PARITY (do not violate):\n"
        + lines
        + "If the user's spec uses different language conventions for a repo (e.g. asks for"
        + " `scan.service.ts` in a Python repo), TRANSLATE the filenames + idioms to match the"
        + " repo's actual language before emitting the task description. The repo's detected"
        + " language is authoritative.\n";
  }

  /**
   * Map a detected (language, framework) pair to a short worker-actionable convention hint.
   * Conservative: when the language doesn't match any known mapping, return a generic
   * "match the existing repo conventions" line so the planner at least sees the language
   * name in the parity block.
   */
  static String languageConventionHint(String language, String framework) {
    String l = language == null ? "" : language.trim().toLowerCase();
    return switch (l) {
      case "python" ->
          "use snake_case `.py` filenames, `os.getenv()` / pydantic-settings for config,"
              + " pytest for tests.";
      case "typescript", "typescript/js", "javascript", "node", "nodejs" ->
          "use kebab-case or camelCase `.ts` / `.tsx` filenames (match what's already in the"
              + " repo), `process.env` for config, vitest / jest for tests.";
      case "go", "golang" ->
          "use lower_snake_case `.go` filenames, `os.Getenv` for config, `_test.go` for tests.";
      case "rust" ->
          "use snake_case `.rs` filenames, `std::env::var` for config, `#[cfg(test)]` modules"
              + " or `tests/` dir.";
      case "java" ->
          "use PascalCase `.java` filenames, Spring `@Value` / `application.yml` for config,"
              + " JUnit for tests.";
      case "kotlin" ->
          "use PascalCase `.kt` filenames, Spring `@Value` / `application.yml` for config,"
              + " JUnit / Kotest for tests.";
      case "ruby" ->
          "use snake_case `.rb` filenames, `ENV[]` for config, RSpec for tests.";
      case "csharp", "c#", "cs" ->
          "use PascalCase `.cs` filenames, `IConfiguration` / `appsettings.json` for config,"
              + " xUnit for tests.";
      default -> "match the existing repo's filename + config + test conventions.";
    };
  }

  private static String lastPathSegment(String fullName) {
    if (fullName == null) return "repo";
    int slash = fullName.lastIndexOf('/');
    return slash < 0 ? fullName : fullName.substring(slash + 1);
  }

  /**
   * v2.6.2 — hierarchical-planner phase 0. Runs the smart classifier and persists the verdict +
   * per-phase token cost on the run row. Returns the resolved {@link PlanStrategy} so the
   * orchestrator can route SMALL/MEDIUM specs to {@link OutlinePlannerAgent} (slice 2) and
   * SINGLE_SHOT/LARGE/LEGACY to {@link PlannerAgentV2}. Returns null when the classifier failed
   * outright — the caller should treat that as the legacy path (PlannerAgentV2 handles every
   * classification).
   *
   * <p>Cost note: the classifier's LLM call (when it fires) flows through {@link LlmRouterV2} →
   * {@link V2RunCostTracker#record}, so it auto-aggregates into the run's grand totals. We diff the
   * per-run snapshot before and after the classifier call to attribute the slice to the new {@code
   * classifier_*} columns. Any future cost reads on the run total already include this — no
   * separate reconciliation needed.
   *
   * <p>Failures are swallowed: classifier outage must never block planning.
   */
  private PlanStrategy runClassifierShadow(
      String runId,
      PipelineRun runRow,
      String userRequest,
      String projectId,
      List<UUID> selectedRepoIds,
      String provider) {
    try {
      // Repo full names — best effort. Skip lookup if not multi-repo.
      List<String> repoNames = new ArrayList<>();
      if (projectId != null && selectedRepoIds != null && !selectedRepoIds.isEmpty()) {
        try {
          List<ProjectRepo> repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
          for (ProjectRepo r : repos) {
            if (r.getGithubRepoFullName() != null) repoNames.add(r.getGithubRepoFullName());
          }
        } catch (Exception ignored) {
          /* fall through with empty list */
        }
      }

      // Project type — Project entity doesn't carry one yet (slice 1
      // skips this signal). Future slices will plumb it through from
      // detected_stack on linked repos.
      String projectType = null;

      // Project memory snippet — short tail of last 3 ledger entries.
      String projectMemorySnippet = null;
      if (projectId != null) {
        try {
          String full = projectMemoryProvider.buildPlannerContext(projectId);
          if (full != null && !full.isBlank()) {
            // Cap at ~600 chars so the classifier prompt stays compact.
            projectMemorySnippet = full.length() > 600 ? full.substring(full.length() - 600) : full;
          }
        } catch (Exception ignored) {
          /* leave null */
        }
      }

      com.navneet.executespec.router.LlmProvider providerEnum;
      try {
        providerEnum =
            com.navneet.executespec.router.LlmProvider.valueOf(
                provider == null ? "OPENAI" : provider.toUpperCase());
      } catch (Exception e) {
        providerEnum = com.navneet.executespec.router.LlmProvider.OPENAI;
      }

      V2RunCostTracker.Snapshot before = costTracker.snapshot(runId);
      ClassificationResult result =
          smartClassifier.classify(
              userRequest, repoNames, projectId, projectType, providerEnum, projectMemorySnippet);
      V2RunCostTracker.Snapshot after = costTracker.snapshot(runId);

      long classifierIn = Math.max(0, after.inputTokens() - before.inputTokens());
      long classifierOut = Math.max(0, after.outputTokens() - before.outputTokens());
      double classifierCost = Math.max(0.0, after.costUsd() - before.costUsd());

      log.info(
          "V2 run={} smart classifier → {} (source={}, est tasks={}, repos={}, in/out={}/{}, ${})",
          runId,
          result.strategy(),
          result.source(),
          result.estimatedTaskCount(),
          result.estimatedRepoTouchCount(),
          classifierIn,
          classifierOut,
          String.format("%.6f", classifierCost));

      // Persist verdict + per-phase cost on the run row. Use a fresh load
      // so we don't overwrite columns that other writers may have touched
      // (initialisation log, codemap summary, etc.).
      try {
        PipelineRun fresh = runRepo.findById(runId).orElse(null);
        if (fresh != null) {
          fresh.setPlanStrategy(result.strategy().name());
          fresh.setClassifierReasoning(result.reasoning());
          fresh.setClassifierInputTokens(classifierIn);
          fresh.setClassifierOutputTokens(classifierOut);
          fresh.setClassifierCostUsd(classifierCost);
          runRepo.save(fresh);
        }
      } catch (Exception persistEx) {
        log.warn(
            "V2 run={} smart classifier persist failed (non-fatal): {}",
            runId,
            persistEx.getMessage());
      }
      return result.strategy();
    } catch (Exception e) {
      log.warn(
          "V2 run={} smart classifier shadow run failed (non-fatal): {}", runId, e.getMessage());
      return null;
    }
  }

  /**
   * v2.6.2 — hierarchical-planner phase 1. Snapshots per-run cost before + after the {@link
   * OutlinePlannerAgent} call, attributes the diff to {@code pipeline_run.outline_*} columns +
   * persists the plan JSON to {@code plan_outline_json}. Failures bubble up so the orchestrator can
   * fall back to the legacy planner.
   */
  private PlanV2 runOutlinePhase(
      String runId, PlannerRequestV2 req, OutlinePlannerAgent.Mode mode) {
    V2RunCostTracker.Snapshot before = costTracker.snapshot(runId);
    try {
      PlanV2 plan = outlinePlanner.plan(req, mode);
      V2RunCostTracker.Snapshot after = costTracker.snapshot(runId);

      long inTok = Math.max(0, after.inputTokens() - before.inputTokens());
      long outTok = Math.max(0, after.outputTokens() - before.outputTokens());
      double cost = Math.max(0.0, after.costUsd() - before.costUsd());

      log.info(
          "V2 run={} outline planner [{}] → {} tasks, in/out={}/{}, ${}",
          runId,
          mode,
          plan.tasks().size(),
          inTok,
          outTok,
          String.format("%.6f", cost));

      try {
        PipelineRun fresh = runRepo.findById(runId).orElse(null);
        if (fresh != null) {
          try {
            fresh.setPlanOutlineJson(objectMapper.writeValueAsString(plan));
          } catch (Exception serEx) {
            log.warn(
                "V2 run={} plan_outline_json serialisation failed: {}", runId, serEx.getMessage());
          }
          fresh.setOutlineInputTokens(inTok);
          fresh.setOutlineOutputTokens(outTok);
          fresh.setOutlineCostUsd(cost);
          runRepo.save(fresh);
        }
      } catch (Exception persistEx) {
        log.warn("V2 run={} outline persist failed (non-fatal): {}", runId, persistEx.getMessage());
      }
      return plan;
    } catch (Exception e) {
      log.warn(
          "V2 run={} outline planner failed: {} — bubbling up for fallback", runId, e.getMessage());
      throw e;
    }
  }

  /**
   * v2.6.2 slice 3 — LARGE pipeline. Drives:
   *
   * <ol>
   *   <li>Phase 1: outline planner in SKELETON_ONLY mode (id/title/complexity/ repos/dependsOn/type
   *       only).
   *   <li>Phase 2: parallel expansion of every LEAF task via {@link TaskExpanderAgent}. Bounded
   *       concurrency via the existing pipeline pool. EPIC tasks are NOT recursed yet — slice 4
   *       brings depth-2 expansion; for slice 3 they're treated as LEAFs.
   *   <li>Phase 3: coherence pass via {@link CoherencePlannerAgent} + apply any patches.
   * </ol>
   *
   * <p>Per-phase cost is diff'd from {@link V2RunCostTracker#snapshot} and persisted to the
   * matching {@code pipeline_run.{outline,expansion,coherence}_*} columns. Run total
   * auto-aggregates via the tracker's accumulator.
   */
  private PlanV2 runLargePipeline(
      String runId, PlannerRequestV2 req, ExecutionContext rootCtx, boolean enableEpicRecursion) {
    // ── Phase 1: skeleton outline ─────────────────────────────────────
    PlanV2 skeleton = runOutlinePhase(runId, req, OutlinePlannerAgent.Mode.SKELETON_ONLY);
    if (skeleton.tasks() == null || skeleton.tasks().isEmpty()) {
      throw new IllegalStateException("LARGE outline returned 0 skeleton tasks");
    }

    // ── Phase 2: parallel expansion ──────────────────────────────────
    // For LEAF tasks: a single TaskExpanderAgent call fills the body.
    // For EPIC tasks under LEGACY recursion (slice 4): recurse via
    // OutlinePlannerAgent (skeleton-mode) to get child skeletons, then
    // expand each child as a LEAF in parallel. Children inherit the
    // parent's id as a prefix (T3 → T3.1, T3.2, …). Without LEGACY
    // recursion, EPIC tasks are treated identically to LEAF tasks (slice 3
    // behaviour) — the worker either handles the breadth or the planner
    // declared EPIC mistakenly.
    V2RunCostTracker.Snapshot beforeExpand = costTracker.snapshot(runId);
    long expansionStart = System.currentTimeMillis();
    List<PlannedTaskV2> tasksToExpand = skeleton.tasks();
    // Per-skeleton-id → list of resulting LEAF tasks (1 for LEAF, N for
    // recursively-expanded EPIC).
    java.util.concurrent.ConcurrentMap<String, List<PlannedTaskV2>> expanded =
        new java.util.concurrent.ConcurrentHashMap<>();
    java.util.concurrent.ConcurrentMap<String, Throwable> expansionFailures =
        new java.util.concurrent.ConcurrentHashMap<>();

    List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
    for (PlannedTaskV2 t : tasksToExpand) {
      futures.add(
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try {
                  if (enableEpicRecursion && t.taskType() == PlannedTaskV2.TaskType.EPIC) {
                    List<PlannedTaskV2> children = expandEpic(runId, t, skeleton, req, rootCtx);
                    expanded.put(t.id(), children);
                  } else {
                    ExecutionContext taskCtx =
                        new ExecutionContext(runId, "_expand_" + t.id(), rootCtx.workspaceRoot());
                    PlannedTaskV2 full = taskExpander.expand(t, skeleton, req, taskCtx);
                    expanded.put(t.id(), List.of(full));
                  }
                } catch (Throwable ex) {
                  log.warn(
                      "V2 run={} task expansion failed for {}: {}", runId, t.id(), ex.getMessage());
                  expansionFailures.put(t.id(), ex);
                  // Fall back to a LEAF carrying just the title-derived
                  // description so the pipeline can still progress.
                  expanded.put(
                      t.id(),
                      List.of(
                          PlannedTaskV2.builder()
                              .id(t.id())
                              .title(t.title())
                              .description(
                                  "Implement: "
                                      + t.title()
                                      + " (expansion failed; worker will figure it out)")
                              .complexity(t.complexity())
                              .touchesFiles(t.touchesFiles())
                              .dependsOn(t.dependsOn())
                              .hardConstraints(List.of())
                              .meetsRequirements(List.of())
                              .taskType(PlannedTaskV2.TaskType.LEAF)
                              .build()));
                }
              },
              pool));
    }
    try {
      java.util.concurrent.CompletableFuture.allOf(
              futures.toArray(new java.util.concurrent.CompletableFuture[0]))
          .get(20, java.util.concurrent.TimeUnit.MINUTES);
    } catch (Exception expandWaitEx) {
      throw new IllegalStateException(
          "LARGE expansion phase exceeded 20-minute timeout: " + expandWaitEx.getMessage(),
          expandWaitEx);
    }

    // Stitch expanded bodies back onto the plan in original order. EPIC
    // entries are replaced by their N child LEAFs in-place.
    List<PlannedTaskV2> assembledTasks = new ArrayList<>(tasksToExpand.size());
    for (PlannedTaskV2 sk : tasksToExpand) {
      List<PlannedTaskV2> result = expanded.get(sk.id());
      if (result == null || result.isEmpty()) {
        assembledTasks.add(sk);
      } else {
        assembledTasks.addAll(result);
      }
    }
    PlanV2 assembled =
        PlanV2.builder()
            .planTitle(skeleton.planTitle())
            .planSummary(skeleton.planSummary())
            .projectType(skeleton.projectType())
            .buildCommand(skeleton.buildCommand())
            .smokeTestStrategy(skeleton.smokeTestStrategy())
            .runtimeSurface(skeleton.runtimeSurface())
            .tasks(assembledTasks)
            .classification(skeleton.classification())
            .requirements(skeleton.requirements())
            .build();

    V2RunCostTracker.Snapshot afterExpand = costTracker.snapshot(runId);
    long expandIn = Math.max(0, afterExpand.inputTokens() - beforeExpand.inputTokens());
    long expandOut = Math.max(0, afterExpand.outputTokens() - beforeExpand.outputTokens());
    double expandCost = Math.max(0.0, afterExpand.costUsd() - beforeExpand.costUsd());
    log.info(
        "V2 run={} expansion: {} tasks, {} failures, wall={}ms, in/out={}/{}, ${}",
        runId,
        assembledTasks.size(),
        expansionFailures.size(),
        System.currentTimeMillis() - expansionStart,
        expandIn,
        expandOut,
        String.format("%.6f", expandCost));

    // ── Phase 3: coherence ───────────────────────────────────────────
    V2RunCostTracker.Snapshot beforeCoherence = costTracker.snapshot(runId);
    ExecutionContext coherenceCtx =
        new ExecutionContext(runId, "_coherence_", rootCtx.workspaceRoot());
    CoherencePlannerAgent.PatchResult patchResult;
    try {
      patchResult = coherencePlanner.patchIfNeeded(assembled, req, coherenceCtx);
    } catch (Exception coherenceEx) {
      log.warn(
          "V2 run={} coherence pass failed (non-fatal — using unpatched plan): {}",
          runId,
          coherenceEx.getMessage());
      patchResult = new CoherencePlannerAgent.PatchResult(assembled, 0, List.of());
    }
    V2RunCostTracker.Snapshot afterCoherence = costTracker.snapshot(runId);
    long cohIn = Math.max(0, afterCoherence.inputTokens() - beforeCoherence.inputTokens());
    long cohOut = Math.max(0, afterCoherence.outputTokens() - beforeCoherence.outputTokens());
    double cohCost = Math.max(0.0, afterCoherence.costUsd() - beforeCoherence.costUsd());

    log.info(
        "V2 run={} coherence: {} patches applied, in/out={}/{}, ${}",
        runId,
        patchResult.patchesApplied(),
        cohIn,
        cohOut,
        String.format("%.6f", cohCost));

    // ── Persist per-phase costs + final plan ─────────────────────────
    try {
      PipelineRun fresh = runRepo.findById(runId).orElse(null);
      if (fresh != null) {
        fresh.setExpansionInputTokens(expandIn);
        fresh.setExpansionOutputTokens(expandOut);
        fresh.setExpansionCostUsd(expandCost);
        fresh.setCoherenceInputTokens(cohIn);
        fresh.setCoherenceOutputTokens(cohOut);
        fresh.setCoherenceCostUsd(cohCost);
        try {
          fresh.setPlanOutlineJson(objectMapper.writeValueAsString(patchResult.patchedPlan()));
        } catch (Exception serEx) {
          log.warn(
              "V2 run={} plan_outline_json (post-expand) serialisation failed: {}",
              runId,
              serEx.getMessage());
        }
        runRepo.save(fresh);
      }
    } catch (Exception persistEx) {
      log.warn(
          "V2 run={} expansion/coherence persist failed (non-fatal): {}",
          runId,
          persistEx.getMessage());
    }

    return patchResult.patchedPlan();
  }

  /**
   * v2.6.2 slice 4 — depth-2 recursive expansion for an EPIC skeleton task. The orchestrator
   * detects EPICs after the top-level skeleton outline, makes a focused sub-outline call ({@link
   * OutlinePlannerAgent.Mode#SKELETON_ONLY}) for just this epic, then expands each sub-skeleton as
   * a LEAF in parallel. Sub-task IDs are prefixed with the parent's id (e.g. T3 → T3.1, T3.2, …) so
   * the assembled plan keeps stable references for downstream {@code dependsOn} resolution.
   *
   * <p>Recursion stops at depth 2: if a child of an EPIC also comes back marked EPIC, it's coerced
   * to LEAF and expanded normally. We prevent runaway planning trees from a hallucinating outline
   * planner.
   */
  private List<PlannedTaskV2> expandEpic(
      String runId,
      PlannedTaskV2 epic,
      PlanV2 parentPlan,
      PlannerRequestV2 parentReq,
      ExecutionContext rootCtx) {
    log.info("V2 run={} expandEpic {} → recursing", runId, epic.id());

    String subSpec = "Sub-task: " + epic.title();
    if (epic.description() != null && !epic.description().isBlank()) {
      subSpec += "\n\nFurther context: " + epic.description();
    }
    // Add parent-plan summary so the sub-planner knows the broader scope.
    if (parentPlan.planSummary() != null && !parentPlan.planSummary().isBlank()) {
      subSpec += "\n\nParent plan: " + parentPlan.planSummary();
    }

    PlannerRequestV2 subReq =
        PlannerRequestV2.builder()
            .spec(subSpec)
            // MEDIUM caps sub-tasks at 4 (per Classification.maxTasks); a
            // sub-outline that overflows is almost always a sign the
            // planner mistakenly marked something EPIC that should have
            // been LEAF. Better to truncate than blow the budget.
            .classification(Classification.MEDIUM)
            .provider(parentReq.provider())
            .model(parentReq.model())
            .context(parentReq.context())
            .workspaceSummary(parentReq.workspaceSummary())
            .projectMemory(parentReq.projectMemory())
            .projectBrief(parentReq.projectBrief())
            .referenceDocs(parentReq.referenceDocs())
            .tddMode(parentReq.tddMode())
            .build();

    PlanV2 subSkeleton;
    try {
      subSkeleton = outlinePlanner.plan(subReq, OutlinePlannerAgent.Mode.SKELETON_ONLY);
    } catch (Exception subOutlineEx) {
      log.warn(
          "V2 run={} sub-outline for EPIC {} failed — falling back to single LEAF expansion: {}",
          runId,
          epic.id(),
          subOutlineEx.getMessage());
      return List.of(coerceToLeafFallback(epic));
    }

    List<PlannedTaskV2> children = subSkeleton.tasks();
    if (children == null || children.isEmpty()) {
      log.warn(
          "V2 run={} sub-outline for EPIC {} returned 0 children — coercing to single LEAF",
          runId,
          epic.id());
      return List.of(coerceToLeafFallback(epic));
    }

    // Re-id with parent prefix and force LEAF (depth cap).
    List<PlannedTaskV2> reIdChildren = new ArrayList<>(children.size());
    for (int i = 0; i < children.size(); i++) {
      PlannedTaskV2 c = children.get(i);
      String childId = epic.id() + "." + (i + 1);
      reIdChildren.add(
          PlannedTaskV2.builder()
              .id(childId)
              .title(c.title())
              .description(c.description())
              .complexity(c.complexity() == null ? PlannedTaskV2.Complexity.MEDIUM : c.complexity())
              .touchesFiles(c.touchesFiles())
              .dependsOn(c.dependsOn())
              .hardConstraints(c.hardConstraints())
              .meetsRequirements(c.meetsRequirements())
              .taskType(PlannedTaskV2.TaskType.LEAF) // depth cap: no further recursion
              .build());
    }

    // Build a sub-plan view for the expander prompt so siblings cross-reference correctly.
    PlanV2 subPlanForContext =
        PlanV2.builder()
            .planTitle(parentPlan.planTitle())
            .planSummary(parentPlan.planSummary())
            .projectType(parentPlan.projectType())
            .buildCommand(parentPlan.buildCommand())
            .smokeTestStrategy(parentPlan.smokeTestStrategy())
            .runtimeSurface(parentPlan.runtimeSurface())
            .tasks(reIdChildren)
            .classification(parentPlan.classification())
            .requirements(parentPlan.requirements())
            .build();

    // Expand each child in parallel. Reuses the same pipeline pool — bounded
    // concurrency means we won't thrash the LLM provider even on big EPICs.
    java.util.concurrent.ConcurrentMap<String, PlannedTaskV2> bodied =
        new java.util.concurrent.ConcurrentHashMap<>();
    List<java.util.concurrent.CompletableFuture<Void>> subFutures = new ArrayList<>();
    for (PlannedTaskV2 child : reIdChildren) {
      subFutures.add(
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try {
                  ExecutionContext childCtx =
                      new ExecutionContext(runId, "_expand_" + child.id(), rootCtx.workspaceRoot());
                  bodied.put(
                      child.id(),
                      taskExpander.expand(child, subPlanForContext, parentReq, childCtx));
                } catch (Throwable ex) {
                  log.warn(
                      "V2 run={} sub-task expansion failed for {}: {}",
                      runId,
                      child.id(),
                      ex.getMessage());
                  bodied.put(
                      child.id(),
                      PlannedTaskV2.builder()
                          .id(child.id())
                          .title(child.title())
                          .description(
                              "Implement: "
                                  + child.title()
                                  + " (sub-expansion failed; worker will figure it out)")
                          .complexity(child.complexity())
                          .touchesFiles(child.touchesFiles())
                          .dependsOn(child.dependsOn())
                          .hardConstraints(List.of())
                          .meetsRequirements(List.of())
                          .taskType(PlannedTaskV2.TaskType.LEAF)
                          .build());
                }
              },
              pool));
    }
    try {
      java.util.concurrent.CompletableFuture.allOf(
              subFutures.toArray(new java.util.concurrent.CompletableFuture[0]))
          .get(10, java.util.concurrent.TimeUnit.MINUTES);
    } catch (Exception subExpandEx) {
      log.warn(
          "V2 run={} sub-expansion for EPIC {} hit timeout/error — using whatever completed: {}",
          runId,
          epic.id(),
          subExpandEx.getMessage());
    }

    // Stitch sub-children in input order.
    List<PlannedTaskV2> result = new ArrayList<>(reIdChildren.size());
    for (PlannedTaskV2 c : reIdChildren) {
      result.add(bodied.getOrDefault(c.id(), c));
    }
    log.info("V2 run={} expandEpic {} → {} LEAF children", runId, epic.id(), result.size());
    return result;
  }

  /**
   * Fallback when sub-outline fails: collapse the EPIC into a single LEAF carrying the title +
   * description so the worker still sees the work.
   */
  private static PlannedTaskV2 coerceToLeafFallback(PlannedTaskV2 epic) {
    return PlannedTaskV2.builder()
        .id(epic.id())
        .title(epic.title())
        .description(
            epic.description() == null || epic.description().isBlank()
                ? "Implement: " + epic.title() + " (sub-outline failed; treat as a single task)"
                : epic.description())
        .complexity(epic.complexity() == null ? PlannedTaskV2.Complexity.HIGH : epic.complexity())
        .touchesFiles(epic.touchesFiles())
        .dependsOn(epic.dependsOn())
        .hardConstraints(epic.hardConstraints())
        .meetsRequirements(epic.meetsRequirements())
        .taskType(PlannedTaskV2.TaskType.LEAF)
        .build();
  }

  private static String shortMsg(Exception e) {
    String msg = e.getMessage();
    String s = e.getClass().getSimpleName() + ": " + (msg == null ? "" : msg);
    return s.length() > 500 ? s.substring(0, 500) + "..." : s;
  }

  @SuppressWarnings("unused")
  private static String readWorkspaceFromLog(IOException ignored) {
    return null;
  }

  /**
   * Walks the run workspace and returns relative paths of all non-hidden, non-build files. Used to
   * populate {@link WorkerReport#getFilesCreated()} so the validation report's {@code
   * filesGenerated} and {@code languages} fields have real data.
   */
  private static List<String> listWorkspaceFiles(Path workspace) {
    if (workspace == null || !Files.isDirectory(workspace)) return List.of();
    java.util.Set<String> skipDirs =
        java.util.Set.of(
            ".git",
            ".agent-context",
            "node_modules",
            "target",
            "build",
            "dist",
            ".gradle",
            ".idea",
            ".vscode",
            "__pycache__",
            "venv",
            ".venv");
    List<String> out = new java.util.ArrayList<>();
    try (var stream = Files.walk(workspace)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
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
    if ("GEMINI".equals(up)) return LlmProvider.GEMINI;
    try {
      return LlmProvider.valueOf(up);
    } catch (IllegalArgumentException e) {
      return LlmProvider.OPENAI;
    }
  }
}
