package com.navneet.executespec.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.ClassificationResult;
import com.navneet.executespec.agent.CoherencePlannerAgent;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlanStrategyCircuitBreaker;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.SmartClassifierAgent;
import com.navneet.executespec.agent.SpecClassifier;
import com.navneet.executespec.agent.TaskExpanderAgent;
import com.navneet.executespec.agent.model.WorkerReport;
import com.navneet.executespec.assessment.FeatureAssessmentService;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.cost.RunCostLog;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.codeintel.RunCodeMapService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.CostHistoryService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.harness.UiStyleResolver;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.reviewer.AccuracyAssessmentService;
import com.navneet.executespec.reviewer.PostEditFormatter;
import com.navneet.executespec.reviewer.ReviewerAgent;
import com.navneet.executespec.reviewer.ReviewerRequest;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.reviewer.model.ReviewVerdict;
import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.TestGenOrchestrator;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 integration point for V1's existing endpoints.
 *
 * <p>{@link RunController} consults {@link com.navneet.executespec.rollout.HarnessVersionResolver}
 * on each {@code POST /api/runs}; when the flag resolves to V2, this orchestrator takes over. The
 * {@code submitRun(...)} signature originally mirrored V1's {@code PipelineHarness.submitRun(...)}
 * (since deleted) so the controller's call site needed only an if/else swap during the V1 → V2
 * cutover.
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
public class RunOrchestrator {

  /** Default V2 task classification — MEDIUM produces ≤ 4 tasks, good balance for most specs. */
  /**
   * Fallback only — live classification flows through {@link
   * com.navneet.executespec.agent.SpecClassifier} which picks SINGLE_SHOT / MEDIUM / MULTI_TASK
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
  private final SequentialExecutor executor;
  private final ReviewerAgent reviewer;
  private final PostEditFormatter postEditFormatter;

  /**
   * V182 (2026-05-08) — patch-mode entry. Used by the {@code runPatchPipeline} branch when {@link
   * PipelineRun#getRunMode()} is {@code RunMode.PATCH}. Build-mode runs never call this — the
   * existing planner / executor / reviewer flow is untouched.
   */
  private final com.navneet.executespec.agent.PatchAgent patchAgent;

  /**
   * 2026-05-07 — deterministic auto-formatter pass between executor and reviewer. Runs prettier /
   * spotless / black / gofmt / cargo fmt against the workspace before the LLM reviewer sees it.
   * Cuts ~3K tokens per task off the reviewer's input by absorbing trailing-comma / indent /
   * import-order drift up-front. Gated by feature flag {@code lint.autofix.enabled}.
   */
  private final com.navneet.executespec.lint.LintPass lintPass;

  private final Metrics metrics;
  private final ObjectMapper objectMapper;
  private final AgentProperties agentProps;
  private final RunCostTracker costTracker;
  // 2026-05-13 audit Stage 2 — k-NN over historical run costs. Records on terminal; the
  // estimator falls back to it before the Stage-1 formula once ≥10 similar runs exist.
  private final CostHistoryService costHistoryService;
  private final com.navneet.executespec.cost.RunTokenAttributor tokenAttributor;
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
  private final OrgModelOverrideService orgModelOverrideService;
  private final ModelRegistry modelRegistry;
  private final com.navneet.executespec.smoketest.CliSmokeRunner cliSmokeRunner;
  private final ProjectRepoService projectRepoService;
  private final FeatureAssessmentService featureAssessmentService;
  private final RunCodeMapService runCodeMapService;
  private final AppUserRepository appUserRepository;
  private final com.navneet.executespec.agent.ApiKeyResolver apiKeyResolver;
  private final com.navneet.executespec.memory.ProjectMemoryProvider projectMemoryProvider;
  private final com.navneet.executespec.codeintel.ProjectBriefService projectBriefService;
  private final com.navneet.executespec.codeintel.StalenessService stalenessService;
  private final com.navneet.executespec.codeintel.ProjectArchitectureService
      projectArchitectureService;
  private final com.navneet.executespec.project.rag.RetrievalService retrievalService;
  private final com.navneet.executespec.memory.RunLedgerAgent runLedgerAgent;
  private final com.navneet.executespec.integration.scm.ScmPushService scmPushService;
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;

  /**
   * Enterprise-knowledge planner injection (flag {@code enterprise.inject.enabled}, default OFF).
   * Mirrors the codemap-injection pattern (RL-2026-05-035): best-effort, bounded, no-op on
   * greenfield (no project) so the customer pipeline is unaffected when the flag is off.
   */
  private final com.navneet.executespec.contracts.injection.ContractInjectionService
      contractInjectionService;

  private final com.navneet.executespec.adr.AdrInjectionService adrInjectionService;

  /**
   * RL-2026-05-037 — enterprise enforcement made real (was "decorative"). {@code StandardsRunner}
   * runs deterministic coding standards against the final workspace and persists violations so the
   * {@code /standards/report} endpoint is no longer always-empty; {@code PlanBreakingChangeReport}
   * classifies touched contracts (previous content from {@link
   * com.navneet.executespec.contracts.ContractVersion}, new content from workspace disk) and
   * surfaces breaking changes as report-only run issues. Both project-gated + flag-gated ({@code
   * standards.enforcement.enabled} / {@code contracts.breaking-change.enabled}, default OFF) +
   * non-fatal; greenfield / no-project runs are a no-op.
   */
  private final com.navneet.executespec.standards.EffectiveStandardsResolver
      effectiveStandardsResolver;

  private final com.navneet.executespec.standards.StandardsRunner standardsRunner;

  private final com.navneet.executespec.standards.StandardsViolationRepository
      standardsViolationRepository;

  private final com.navneet.executespec.contracts.gate.PlanBreakingChangeReport
      planBreakingChangeReport;

  private final com.navneet.executespec.contracts.ContractRegistryRepository
      contractRegistryRepository;

  private final com.navneet.executespec.contracts.ContractVersionRepository
      contractVersionRepository;

  /**
   * RL-2026-05-001 — runs the worker's own tests in the workspace for the verifiedScore TESTS slot.
   */
  private final com.navneet.executespec.smoketest.WorkspaceTestRunner workspaceTestRunner;

  /** Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode. */
  private final com.navneet.executespec.run.RunSkipPushFlag runSkipPushFlag;

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
   * V165 BYOK fix — resolves customer-configured keys at run-start; LlmRouter reads back per
   * dispatch.
   */
  private final com.navneet.executespec.agent.RunKeyContextStore keyContextStore;

  /**
   * 2026-05-08 — programbench-mode discriminator. Used at the reviewer step to bump the iteration
   * cap for ProgramBench runs (where the reviewer has to bisect library link errors across multiple
   * files and the default 50 iters is tight). Null for non-programbench runs; cmatrix run 84252898
   * had reviewer outcome ITERATION_LIMIT_EXCEEDED with the build still unfixed.
   */
  private final com.navneet.executespec.programbench.ProgramBenchContainerStore
      programBenchContainerStore;

  /**
   * R-162 — terminal-status referral hook. Logic extracted into a helper so the wire-point is
   * unit-testable without exercising the orchestrator end-to-end (see {@link
   * com.navneet.executespec.referral.ReferralCompletionHook}). The earlier direct {@code
   * ReferralService} injection was superseded by this helper and removed.
   */
  private final com.navneet.executespec.referral.ReferralCompletionHook referralCompletionHook;

  /**
   * v2.5.8 — publishes PipelineEvent.RunCompleted/RunFailed so in-app notifications, quota refund,
   * and workspace GCS upload listeners fire for V2 runs. Previously only V1 PipelineHarness
   * published these events, causing V2 users to silently miss completion notifications.
   */
  private final org.springframework.context.ApplicationEventPublisher eventPublisher;

  // RL-2026-05-041 (audit #2) — mid-run durability: restore a workspace from its latest GCS
  // checkpoint before failing WORKSPACE_GONE on resume.
  private final com.navneet.executespec.deployment.WorkspaceRestoreService workspaceRestoreService;

  /**
   * Dedicated thread pool — V1 has its own, V2 keeps a separate one so back-pressure is tracked
   * per-harness and canary rollout tuning is independent.
   */
  /** runId → in-flight pipeline future; used by {@link #cancel(String)}. */
  private final ConcurrentMap<String, Future<?>> activeRuns = new ConcurrentHashMap<>();

  /**
   * Run-orchestration pool — sized {@code clamp(4, 16, cores × 1)}. Each run can spawn parallel
   * tasks during the LARGE planner pipeline (skeleton expand × N + sub-expansions for EPICs), so
   * the multiplier matches CPU and the cap protects against a 32-core dev box launching 32
   * concurrent run pipelines that would saturate Hikari + the LLM provider rate limits.
   */
  private final ExecutorService pool =
      Executors.newFixedThreadPool(
          com.navneet.executespec.util.PoolSizing.coreMultiple(1, 4, 16),
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
    log.info("RunOrchestrator shutting down pool — active runs: {}", activeRuns.size());
    pool.shutdown();
    try {
      if (!pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
        log.warn("RunOrchestrator pool did not terminate in 30s; forcing shutdownNow");
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
   * v2.5.7 — 18-arg overload. Forwards to the 19-arg signature with {@code prReviewComments=null}.
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
        branchOverride,
        null);
  }

  /**
   * Fix M (2026-05-03) — full signature with {@code branchOverride} + {@code prReviewComments}.
   * Forwarder for backwards compatibility — defaults {@code runMode = BUILD} so every existing
   * caller keeps the planner / executor / reviewer flow.
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
      String branchOverride,
      String prReviewComments) {
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
        branchOverride,
        prReviewComments,
        com.navneet.executespec.state.RunMode.BUILD);
  }

  /**
   * V182 (2026-05-08) — full signature with {@code runMode}. Bench services (swebench /
   * programbench) call this overload directly with {@code RunMode.PATCH} to opt into the thin
   * single-agent loop. Every product entry point uses the 19-arg forwarder above and stays on
   * {@code RunMode.BUILD} (the existing planner / executor / reviewer flow).
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
      String branchOverride,
      String prReviewComments,
      String runMode) {
    String runId = UUID.randomUUID().toString();
    String provider = normalizeProvider(aiProvider, projectId, runId);

    // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22): only ENTERPRISE runs may
    // pin a per-run model. The actual ROUTING is governed by the RunModelOverrides overlay bound
    // in runPipeline (also ENTERPRISE-gated); we resolve the owner tier once here so the persisted
    // plannerModel/workerModel/reviewerModel columns stay HONEST with that routing and never
    // advertise a pin that routing will ignore for a non-ENTERPRISE caller.
    String ownerTierAtSubmit =
        createdByUserId != null
            ? appUserRepository.findById(createdByUserId).map(u -> u.getTier()).orElse(null)
            : null;
    boolean enterpriseSubmit = "ENTERPRISE".equalsIgnoreCase(ownerTierAtSubmit);
    boolean hasPin = modelName != null && !modelName.isBlank();

    // The run-level worker model honours a supplied modelName only for ENTERPRISE; otherwise it is
    // the YAML workhorse default (= the model the worker will actually route to). This keeps the
    // pipeline's `model` value, the persisted workerModel column, and the tierConfigFor fallback
    // all consistent with the overlay-driven routing.
    String model = (hasPin && enterpriseSubmit) ? modelName.trim() : defaultModelFor(provider);

    // 2026-05-06 — defaultModelFor was a hardcoded provider→model fallback that drifted from
    // the YAML matrix (returned Haiku/flash/mini when YAML PLANNING wanted Sonnet/Pro/gpt-5.4).
    // After the consolidation it routes through ModelRegistry.workhouseModelFor(provider) so
    // the run-level worker default tracks YAML.

    // Resolve per-tier models so RunDetail surfaces what the planner/reviewer
    // will actually call, not the run-level worker default — adversarial-test
    // Finding 14. An ENTERPRISE per-run pin is honoured for all three roles to
    // preserve the override contract.
    //
    // V182 PATCH-mode override: a patch run never invokes the planner or
    // reviewer, but it *must* run on PLANNING-tier (Sonnet 4.6 / GPT-5.4 /
    // Gemini 3 Pro Preview) regardless of any caller-supplied modelName.
    // PatchAgent picks `plannerDbModel` as the model passed to WorkerAgent.
    // See PatchAgent javadoc — the reasoning gap between Haiku-class and
    // Sonnet-class is bigger than any harness improvement on bug-fix shapes.
    String plannerDbModel = model;
    String reviewerDbModel = model;
    boolean isPatch = com.navneet.executespec.state.RunMode.isPatch(runMode);
    if (!hasPin || isPatch || !enterpriseSubmit) {
      try {
        com.navneet.executespec.router.LlmProvider p =
            com.navneet.executespec.router.LlmProvider.valueOf(provider.toUpperCase());
        // v2.6.2 slice 5b — agent name is now OutlinePlannerAgent;
        // PlannerAgent was deleted along with the legacy single-phase planner.
        plannerDbModel =
            modelResolver
                .resolve(
                    "OutlinePlannerAgent", p, com.navneet.executespec.router.AgentTier.PLANNING)
                .getModel();
        reviewerDbModel =
            modelResolver
                .resolve("ReviewerAgent", p, com.navneet.executespec.router.AgentTier.PLANNING)
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
            .runMode(
                runMode == null || runMode.isBlank()
                    ? com.navneet.executespec.state.RunMode.BUILD
                    : runMode)
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
        "RunOrchestrator submitRun runId={} provider={} model={} user={} org={} project={}",
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

    // Synchronous lifecycle event — fires on the calling thread before the async
    // pipeline kicks off. Listeners (e.g. ProgramBenchTaskService's cid-stash) get
    // a deterministic hook between persistence and pipeline start, so they can
    // attach per-run state that the worker tools will observe on first invocation.
    eventPublisher.publishEvent(new PipelineEvent.RunStarted(runId));

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
    final String prReviewCommentsFinal =
        (prReviewComments == null || prReviewComments.isBlank()) ? null : prReviewComments.trim();
    // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). The raw, caller-supplied
    // modelName is the ONLY unambiguous signal of an explicit pin: `model` above is already
    // defaulted to defaultModelFor(provider) when modelName is blank, so we capture modelName
    // directly here and thread it to the pipeline thread. ENTERPRISE-gating + validation happen at
    // the RunModelOverrides bind site in runPipeline so the overlay is the authoritative routing
    // mechanism for every role. Null/blank = no pin (org/YAML resolution untouched).
    final String pinnedModelFinal =
        (modelName == null || modelName.isBlank()) ? null : modelName.trim();
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
                    branchOverrideFinal,
                    prReviewCommentsFinal,
                    pinnedModelFinal);
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
        branchOverride,
        null,
        // No per-run model pin on the resume / re-plan path — pinning is a submit-time concept.
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
      String branchOverride,
      String prReviewComments,
      // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22). Non-null only when the
      // caller supplied an explicit RunRequest.modelName. Overlaid onto the org override map at the
      // bind site below, ENTERPRISE-gated; null on every non-submit path (resume / re-plan).
      String pinnedModel) {
    Instant startedAt = Instant.now();
    Path workspace = null;
    String upperProvider = provider == null ? "UNKNOWN" : provider.toUpperCase();
    try {
      // V165 BYOK fix — resolve the run owner's configured keys ONCE
      // at run-start and stash them in RunKeyContextStore. LlmRouter
      // reads back per-dispatch via request.context().runId() and
      // threads the right provider key into the wire call. Without
      // this, every dispatched LLM call silently used the platform
      // default key — see audit-output/BYOK_DEEPDIVE.md for the
      // full chain-of-evidence.
      try {
        PipelineRun pre = runRepo.findById(runId).orElse(null);
        if (pre != null && pre.getCreatedByUserId() != null) {
          com.navneet.executespec.agent.RunKeyContext kctx =
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

      // F3 — bind this org's per-tier model overrides for the duration of the run so every
      // tier-routed agent (worker via SequentialExecutor.tierConfigFor, planner, reviewer)
      // resolves the org's chosen model instead of the YAML default. All resolve on this
      // pipeline thread, so a ThreadLocal binding covers them; cleared in the finally below.
      // Non-fatal: any failure falls through to the matrix defaults.
      //
      // Per-run provider+model pin (Beta, Enterprise-only — 2026-05-22): when an ENTERPRISE run
      // supplied an explicit RunRequest.modelName, overlay it onto the run-provider's three tier
      // cells of a MUTABLE copy of the org map BEFORE binding, so the pin out-ranks both the org
      // overrides and the YAML defaults for every role (planner / worker / reviewer) on that
      // provider. This RunModelOverrides overlay is the single authoritative routing mechanism —
      // the worker no longer silently re-resolves to the tier default and ignores the pin.
      try {
        PipelineRun ovProbe = runRepo.findById(runId).orElse(null);
        String orgId = ovProbe != null ? ovProbe.getOrgId() : null;
        Map<String, String> orgOverrides = orgModelOverrideService.overridesForRun(orgId);

        // Resolve the run owner's tier (same higher-of pattern the codemap-eligibility check uses
        // below). Only ENTERPRISE runs may pin a per-run model; everything else leaves org/YAML
        // resolution untouched.
        String ownerTier =
            ovProbe != null && ovProbe.getCreatedByUserId() != null
                ? appUserRepository
                    .findById(ovProbe.getCreatedByUserId())
                    .map(u -> u.getTier())
                    .orElse(null)
                : null;
        boolean enterprise = "ENTERPRISE".equalsIgnoreCase(ownerTier);

        LlmProvider pinProvider = null;
        String validatedPin = null;
        if (enterprise && pinnedModel != null && !pinnedModel.isBlank()) {
          try {
            pinProvider = LlmProvider.valueOf(provider.toUpperCase());
            // Validate the pin is a real model that belongs to the run's provider (lenient: if the
            // registry can't confirm ownership we still accept it but keep it provider-scoped, so a
            // forward-compat model id the registry doesn't yet know about isn't rejected).
            String canonical = modelRegistry.canonicalize(pinnedModel.trim());
            String owner = modelRegistry.providerFor(canonical);
            if (owner == null || owner.equalsIgnoreCase(pinProvider.name())) {
              validatedPin = canonical;
            } else {
              log.warn(
                  "V2 run={} per-run model pin '{}' is not a {} model — ignoring pin",
                  runId,
                  pinnedModel,
                  pinProvider.name());
              pinProvider = null;
            }
          } catch (IllegalArgumentException badProvider) {
            log.warn(
                "V2 run={} per-run model pin skipped — unrecognised provider '{}'",
                runId,
                provider);
            pinProvider = null;
          }
        } else if (pinnedModel != null && !pinnedModel.isBlank()) {
          log.debug(
              "V2 run={} per-run model pin '{}' ignored — owner tier {} is not ENTERPRISE",
              runId,
              pinnedModel,
              ownerTier);
        }

        if (validatedPin != null) {
          log.info(
              "V2 run={} ENTERPRISE per-run pin: {} ({}) overrides org+YAML for all tiers",
              runId,
              validatedPin,
              pinProvider.name());
        }
        RunModelOverrides.bind(
            RunModelOverrides.withRunPin(orgOverrides, pinProvider, validatedPin));
      } catch (Exception ovEx) {
        RunModelOverrides.clear();
        log.warn(
            "V2 run={} model-override binding failed; using matrix defaults: {}",
            runId,
            ovEx.getMessage());
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

      // ── V182 PATCH-mode dispatch ───────────────────────────────────────
      // Bench services (swebench / programbench) submit runs with
      // RunMode.PATCH and own the workspace setup themselves (docker cp +
      // bind-mount). Skip everything build-mode does — codemap, assessment,
      // classifier, planner, executor, reviewer, validation report — and
      // dispatch the whole user request to a single PatchAgent loop.
      // Product entry points stay on RunMode.BUILD by default and fall
      // through to the unchanged build-mode flow below.
      {
        PipelineRun modeProbe = runRepo.findById(runId).orElse(null);
        if (modeProbe != null
            && com.navneet.executespec.state.RunMode.isPatch(modeProbe.getRunMode())) {
          runPatchPipeline(runId, provider, modeProbe, userRequest, rootCtx, startedAt);
          return;
        }
      }

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
                runCodeMapService.generateForRun(
                    runId, runRow.getRepoUrl(), null, githubPat, runRow.getProjectId());
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
      Plan plan;
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
        // PlannerAgent. Cost flows through RunCostTracker so it auto-
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
        // RL-2026-05-035 — feed the generated code-intel (codemap symbol maps) into the planner.
        // Flag-gated + bounded + best-effort; no-op on greenfield (no project/codemap).
        if (projectId != null && featureFlagService.isEnabled("codeintel.inject.enabled")) {
          try {
            String codeIntel =
                buildCodeIntelContext(
                    projectId, projectRepoService.resolveForRun(projectId, selectedRepoIds));
            if (!codeIntel.isBlank()) {
              summary = summary + "\n\n" + codeIntel;
              log.info(
                  "V2 run={} injected code-intel into planner ({} chars)",
                  runId,
                  codeIntel.length());
            }
          } catch (Exception e) {
            log.warn("V2 run={} code-intel planner injection skipped: {}", runId, e.getMessage());
          }
        }
        // Enterprise-knowledge injection — feed cross-repo contracts + accepted ADRs into the
        // planner so it plans changes that respect them. Flag-gated (`enterprise.inject.enabled`,
        // default OFF), bounded (~8000 chars each), best-effort per block. No project → both empty
        // → no-op (greenfield fail-safe). Mirrors the codemap-injection pattern above.
        String contractsBlock = "";
        String adrsBlock = "";
        if (projectId != null && featureFlagService.isEnabled("enterprise.inject.enabled")) {
          try {
            String c = contractInjectionService.compose(projectId).block();
            contractsBlock = truncate(c == null ? "" : c, ENTERPRISE_INJECT_CAP);
            if (!contractsBlock.isBlank()) {
              log.info(
                  "V2 run={} injected contracts into planner ({} chars)",
                  runId,
                  contractsBlock.length());
            }
          } catch (Exception e) {
            log.warn("V2 run={} contracts planner injection skipped: {}", runId, e.getMessage());
            contractsBlock = "";
          }
          try {
            String a = adrInjectionService.compose(projectId).block();
            adrsBlock = truncate(a == null ? "" : a, ENTERPRISE_INJECT_CAP);
            if (!adrsBlock.isBlank()) {
              log.info(
                  "V2 run={} injected ADRs into planner ({} chars)", runId, adrsBlock.length());
            }
          } catch (Exception e) {
            log.warn("V2 run={} ADR planner injection skipped: {}", runId, e.getMessage());
            adrsBlock = "";
          }
        }
        PlannerRequest plannerReq =
            PlannerRequest.builder()
                .spec(userRequest)
                .classification(autoClassification)
                .provider(provider)
                .model(model)
                .context(rootCtx)
                .workspaceSummary(summary)
                .projectMemory(projectMemory)
                .projectBrief(projectBrief)
                .referenceDocs(referenceDocs)
                .contracts(contractsBlock)
                .projectAdrs(adrsBlock)
                .tddMode(tddMode)
                .prReviewComments(prReviewComments)
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
          // spec verbatim. Migrated from the deleted PlannerAgent.
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
        log.error("RunOrchestrator run={} planner failed", runId, e);
        terminate(runId, "FAILED", "planner: " + shortMsg(e), startedAt);
        return;
      }
      if (plan.tasks() == null || plan.tasks().isEmpty()) {
        log.error("RunOrchestrator run={} planner returned empty plan — failing", runId);
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
      RunCostTracker.Snapshot afterPlan = costTracker.snapshot(runId);
      tokenAttributor.attributePhase(
          runId,
          com.navneet.executespec.cost.RunTokenAttributor.Phase.PLANNER,
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
          log.warn("RunOrchestrator run={} plan gate interrupted — cancelling", runId);
          terminate(runId, "CANCELLED", "plan gate interrupted", startedAt);
          return;
        }
        if (!approved) {
          log.info("RunOrchestrator run={} plan rejected — cancelling", runId);
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
                    .maxIterationsPerTask(null) // per-complexity caps applied in SequentialExecutor
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
        log.error("RunOrchestrator run={} executor failed", runId, e);
        terminate(runId, "FAILED", "executor: " + shortMsg(e), startedAt);
        return;
      }
      applyExecutionResultToTasks(runId, execResult);
      // Phase 2 delta: tokens spent by the executor (all worker tasks).
      RunCostTracker.Snapshot afterExec = costTracker.snapshot(runId);
      int workerIn = afterExec.inputTokens() - afterPlan.inputTokens();
      int workerOut = afterExec.outputTokens() - afterPlan.outputTokens();
      tokenAttributor.attributeWorker(runId, execResult, workerIn, workerOut, upperProvider, model);

      // ── 2.5 Lint auto-fix ────────────────────────────────────────────
      // Run deterministic formatters against the workspace before the LLM reviewer sees it.
      // Cheap (no LLM tokens), deterministic, and absorbs trailing-comma / indent / import-order
      // drift the worker introduces — so the reviewer's input no longer carries 3K tokens of
      // "fix this whitespace" noise per task. Gated by feature flag so ops can disable in
      // environments without prettier / black / gofmt on PATH (the per-tool timeout already
      // makes failure non-fatal, but the flag short-circuits the whole pass when known broken).
      if (featureFlagService.isEnabled("lint.autofix.enabled")) {
        try {
          com.navneet.executespec.lint.LintPass.LintReport lintReport =
              lintPass.run(rootCtx.workspaceRoot());
          if (lintReport.changedAnything()) {
            log.info("RunOrchestrator run={} lint auto-fix: {}", runId, lintReport.summary());
            broadcaster.emit(runId, RunEvent.lintAutofixApplied(runId, lintReport.summary()));
          } else if (!lintReport.skipped()) {
            log.debug("RunOrchestrator run={} lint auto-fix: no changes", runId);
          }
        } catch (Exception e) {
          // Never abort the run on a formatter error — reviewer still runs against unmodified code.
          log.warn(
              "RunOrchestrator run={} lint auto-fix failed (continuing): {}",
              runId,
              e.getMessage());
        }
      }

      // ── 2.9 Code-gen-only stop (benchmark) ──────────────────────────
      // RL-2026-05-050 — the SWE-bench harness wants the REAL classifier→planner→worker
      // decisioning (the system decides its own strategy), but a CLEAN minimal diff for scoring.
      // The reviewer's PostEditFormatter (whole-file reformats) + the downstream auto-commit /
      // validation pollute the diff (a 469-line reformat sank the earlier BUILD smoke). So for a
      // code-gen-only run we STOP here, right after the worker, and finalize COMPLETE — skipping
      // reviewer + validation + push. Tightly gated on the benchmark source, so every production
      // run is byte-identical (this branch is never taken for real customer runs).
      if (isCodeGenOnlyRun(runId)) {
        log.info(
            "RunOrchestrator run={} CODEGEN_ONLY — stopping after worker (skip"
                + " reviewer/validation/push)",
            runId);
        broadcaster.emit(
            runId,
            RunEvent.log(
                runId, null, "Code generation complete — code-gen-only mode (reviewer skipped)"));
        terminate(
            runId, "COMPLETE", "CODEGEN_ONLY", "code-gen complete; reviewer skipped", startedAt);
        return;
      }

      // ── 3. Review ───────────────────────────────────────────────────
      updateStatus(runId, "REVIEWING");
      perky.start(runId, "REVIEWING");
      ReviewerResult reviewResult = null;
      // 2026-05-08 — programbench runs need more reviewer iters to bisect library link
      // errors. Default cap is 50 (per ReviewerAgent.DEFAULT_MAX_ITER_PER_PASS); cmatrix
      // gemini run 84252898 hit ITERATION_LIMIT_EXCEEDED with the build still unfixed.
      // Bumped to 80 for programbench-mode runs only — non-programbench runs unchanged.
      Integer reviewerMaxIter =
          programBenchContainerStore != null && programBenchContainerStore.get(runId) != null
              ? 80
              : null;
      // 2026-05-13 — skip wiring pass on simple runs where it's pure cost overhead.
      // Original gate (SINGLE_SHOT × single-repo) missed run e5f40b8a where classifier picked
      // SMALL on the same kind of "format 6 files" spec — wiring still ran. The wiring pass is
      // valuable when the worker can leave routes / barrels / imports orphaned across multiple
      // tasks or repos; on a single-task plan touching a single repo there's nothing to wire
      // regardless of which strategy the classifier picked. Broadened to:
      //   • single linked repo (count ≤ 1) AND
      //   • single task in the plan
      // This covers SINGLE_SHOT-and-SMALL when both shrink to 1 task. Multi-task SMALL/MEDIUM/
      // LARGE/LEGACY still get the full reviewer because cross-task wiring concerns are real.
      int linkedRepoCount = selectedRepoIds == null ? 0 : selectedRepoIds.size();
      int planTaskCount = plan.tasks() == null ? 0 : plan.tasks().size();
      boolean skipWiring = linkedRepoCount <= 1 && planTaskCount <= 1;
      if (skipWiring) {
        log.info(
            "RunOrchestrator run={} skipping reviewer wiring pass — single-repo × single-task"
                + " (classification={}, audit 2026-05-13 cost-control)",
            runId,
            plan.classification());
      }
      try {
        reviewResult =
            reviewer.review(
                ReviewerRequest.builder()
                    .provider(provider)
                    .model(model)
                    .context(rootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(skipWiring)
                    .maxIterationsPerPass(reviewerMaxIter)
                    .build());
        persistReview(runId, reviewResult);
        // Phase 3 delta: tokens spent by reviewer (general + wiring passes).
        RunCostTracker.Snapshot afterReview = costTracker.snapshot(runId);
        int reviewerIn = afterReview.inputTokens() - afterExec.inputTokens();
        int reviewerOut = afterReview.outputTokens() - afterExec.outputTokens();
        tokenAttributor.attributePhase(
            runId,
            com.navneet.executespec.cost.RunTokenAttributor.Phase.REVIEWER,
            reviewerIn,
            reviewerOut,
            upperProvider,
            model);
      } catch (Exception e) {
        log.warn("RunOrchestrator run={} reviewer failed — recording partial review", runId, e);
        // Review failure is non-fatal; the execution already succeeded or partially
        // succeeded. Record the failure in reviewJson so the UI can surface it.
        persistReviewFailure(runId, e);
      }

      // ── 3b. Enterprise enforcement (project runs only) — make the formerly
      // "decorative" engines real (RL-2026-05-037). StandardsRunner persists
      // standards violations so /standards/report is populated; the breaking-
      // change classifier surfaces touched-contract breaks as report-only run
      // issues. Both project-gated + flag-gated (default OFF) + non-fatal.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> contractBreakingIssues =
          java.util.List.of();
      if (projectId != null) {
        if (featureFlagService.isEnabled("standards.enforcement.enabled")) {
          try {
            runStandardsEnforcement(runId, projectId, rootCtx.workspaceRoot());
          } catch (Exception e) {
            log.warn(
                "RunOrchestrator run={} standards enforcement skipped: {}", runId, e.getMessage());
          }
        }
        if (featureFlagService.isEnabled("contracts.breaking-change.enabled")) {
          try {
            contractBreakingIssues =
                detectBreakingChangeIssues(
                    runId, projectId, rootCtx.workspaceRoot(), selectedRepoIds);
          } catch (Exception e) {
            log.warn(
                "RunOrchestrator run={} breaking-change detection skipped: {}",
                runId,
                e.getMessage());
          }
        }
      }

      // ── 4. Ensure README.md exists in the workspace ─────────────────
      // If the planner emitted a "create README" task the file is already there
      // and we leave it alone. If not (typical for trivial specs), write a
      // deterministic run-meta README so the download-zip experience always
      // includes an entry-point doc.
      ensureReadme(runId, workspace, userRequest, plan, execResult, reviewResult, startedAt);

      // ── 4b. Pre-resolve terminal status BEFORE the validation report.
      // 2026-05-10 — bug fix for dev run 61c9fffa where the run came back
      // status=COMPLETE + statusReason=CLEAN_WITH_INTERMEDIATE_WARNINGS but
      // the validation report was persisted with pipelineResult=FAILED.
      //
      // Cause: generateValidationReport runs at step 5; terminate (which
      // sets status_reason on the run row) runs at step 7. ValidationReport-
      // Service.reconcileWithRunStatus reads run.statusReason at step 5 —
      // gets null — falls through to the per-issue heuristic — which
      // returned FAILED because synthesiseReviewIssues had pushed unresolved
      // CRITICAL SYNTH-ACC-* rows from the accuracy LLM into the issue
      // list. Net effect: report says FAILED while the run says COMPLETE.
      //
      // Fix: resolve the terminal StatusAndReason once here, persist the
      // statusReason onto the run row before the validation report runs,
      // then reuse the same `sr` for terminate(). The reconcile path now
      // reads the right value at report-time and writes a consistent
      // pipelineResult.
      // 2026-05-13 audit — always derive the statusReason via resolveTerminalStatus,
      // even when the v2-verified scoring flag is off. Previously the off-path wrote
      // reason=null which left the terminate() write a no-op (see :3028), and downstream
      // surfaces fell back to the V108 backfill value "LEGACY_UNKNOWN" — so users saw
      // every PARTIAL/COMPLETE run tagged with a vague reason that conveyed nothing.
      // The flag should only gate the SCORING formula choice, not the reason field.
      StatusAndReason srDerived = resolveTerminalStatus(execResult, reviewResult);
      StatusAndReason sr =
          featureFlagService.isEnabled("scoring.v2-verified")
              ? srDerived
              : new StatusAndReason(mapOverallToV1Status(execResult.overall()), srDerived.reason());
      if (sr.reason() != null) {
        try {
          runRepo
              .findById(runId)
              .ifPresent(
                  r -> {
                    r.setStatusReason(sr.reason());
                    runRepo.save(r);
                  });
        } catch (Exception statusReasonEx) {
          // Non-fatal — terminate() will retry the same write a few lines
          // below. Worst case: report uses the heuristic for this one run.
          log.warn(
              "RunOrchestrator run={} pre-stamp statusReason failed: {}",
              runId,
              statusReasonEx.getMessage());
        }
      }

      // ── 5. Generate validation report + kick off test generation ────
      generateValidationReport(runId, execResult, reviewResult, plan, contractBreakingIssues);
      // V1's test generation assumes HTTP endpoints — it runs ApiProber against a
      // live preview and synthesizes REST tests. For CLI / LIBRARY / STATIC_WEB
      // projects that does not apply; skip until the dedicated CLI stdin/stdout
      // harness lands (see docs/runtime-surface-plan).
      if (plan.runtimeSurface() == Plan.RuntimeSurface.HTTP) {
        try {
          String planJson = objectMapper.writeValueAsString(plan);
          testGenOrchestrator.runTestGenPipeline(runId, userRequest, planJson);
        } catch (Exception e) {
          log.warn("RunOrchestrator run={} testGen kickoff failed: {}", runId, e.getMessage());
        }
      } else if (plan.runtimeSurface() == Plan.RuntimeSurface.CLI) {
        // Baseline CLI smoke: invoke the inferred entry point with no stdin,
        // record exit code + head of stdout. Full stdin/stdout fixture harness
        // (planner-emitted cliTestCases with expected stdout) is follow-up work.
        var cliResult = cliSmokeRunner.run(workspace);
        log.info(
            "RunOrchestrator run={} CLI smoke status={} exit={} cmd='{}' dur={}ms",
            runId,
            cliResult.status(),
            cliResult.exitCode(),
            cliResult.command(),
            cliResult.durationMs());
      } else {
        log.info(
            "RunOrchestrator run={} skipping test generation (runtimeSurface={})",
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
      // `sr` was resolved up at step 4b so the validation report could
      // see the right statusReason during reconcile. Reuse it here so
      // terminate() flips status + persists the (idempotent) reason.
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
          "RunOrchestrator run={} {} exec={} review={} reason={}",
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
        // Audit r5 (#5, 2026-05-06) — staged-PR-before-push override.
        // When the user submitted with skipAutoPush=true the
        // RunController stamped a Redis key; consume it here once.
        // The flag is one-shot — a manual re-push afterward goes
        // through the unchanged /runs/{id}/push-github path.
        if (shouldPush && runSkipPushFlag.consume(runId)) {
          shouldPush = false;
          log.info("V2 run={} auto-push skipped (skipAutoPush flag set)", runId);
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
      log.error("RunOrchestrator run={} unexpected failure", runId, unexpected);
      terminate(runId, "FAILED", "unexpected: " + shortMsg(unexpected), startedAt);
    } finally {
      perky.stop(runId);
      // F3 — drop the run-scoped model-override binding so it never leaks to the
      // next run that reuses this pooled pipeline thread.
      RunModelOverrides.clear();
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
        log.info("RunOrchestrator run={} workspace at {}", runId, workspace);
      }
    }
  }

  // ─── V182 PATCH-mode pipeline ──────────────────────────────────────────

  /**
   * Default per-run cost cap when bench services don't supply one. Bumped 2026-05-08 from $4 to $8
   * after the hard SWE-bench matrix showed Gemini hitting BUDGET_EXCEEDED at $4.06-$4.18 on
   * heavy-reasoning sqlfluff instances — within $0.20 of the cap, meaning $4 was strangling runs
   * that were close to completion. $8 gives Gemini Pro on hard SWE-bench instances enough headroom
   * to finish (observed peak $4.84 in the f79252b1 pyvista budget-exceeded case from the medium
   * matrix). Real protection against runaway loops is now ITER_LIMIT (120 iters) + Lever A
   * force-edit watchdog + Lever B salvage; the cost cap is a final defence-in-depth. Operator can
   * still override per-run via RunRequest.userCostCap if a tighter ceiling is desired for
   * cost-sensitive workloads.
   */
  private static final double DEFAULT_PATCH_HARD_CAP_USD = 8.0;

  /**
   * V182 — patch-mode dispatch. Skips planner, executor, reviewer, and the validation report; the
   * artefact is the workspace diff captured by the bench service's {@code captureOnTerminalAsync}
   * path. Idempotent against the run row, terminal-event-aware, and writes a status_reason that
   * downstream notifiers / dashboards can render meaningfully.
   *
   * <p>Cost cap: PatchAgent threads through to {@link com.navneet.executespec.agent.WorkerAgent}'s
   * iter-3 budget guard, which reads {@code pipeline_run.hard_cap_usd}. We default that to {@link
   * #DEFAULT_PATCH_HARD_CAP_USD} when unset so a runaway patch agent stops without manual
   * intervention.
   *
   * <p>Outcome → terminal status mapping:
   *
   * <ul>
   *   <li>{@code COMPLETED} / {@code COMPLETED_BY_STAGNATION} / {@code STOPPED_WITHOUT_COMPLETE} →
   *       {@code COMPLETE}. The diff is whatever's in the workspace at exit; the bench eval decides
   *       correctness, not us.
   *   <li>{@code ITERATION_LIMIT_EXCEEDED} / {@code BUDGET_EXCEEDED} → {@code PARTIAL}. The agent
   *       was making progress but ran out of headroom.
   *   <li>{@code ERROR} or any uncaught exception → {@code FAILED}.
   * </ul>
   */
  private void runPatchPipeline(
      String runId,
      String provider,
      PipelineRun run,
      String userRequest,
      ExecutionContext rootCtx,
      Instant startedAt) {

    // 2026-05-08 — BYOK cost-cap exemption. When the run's user is on an OWN_KEYS org
    // (pure BYOK — they're paying their own API bill), skip the platform's default $4 cap. We
    // shouldn't hard-stop a customer's run on OUR cost limit when WE'RE not paying. HYBRID and
    // SYSTEM stay capped because either path can fall back to platform spend mid-run.
    if (apiKeyResolver.isOwnKeysMode(run.getCreatedByUserId())) {
      log.info(
          "PATCH run={} BYOK exempt — skipping default hardCapUsd (org keySourceMode=OWN_KEYS)",
          runId);
    } else if (run.getHardCapUsd() == null || run.getHardCapUsd() <= 0) {
      run.setHardCapUsd(DEFAULT_PATCH_HARD_CAP_USD);
      runRepo.save(run);
      log.info(
          "PATCH run={} hardCapUsd default ${}",
          runId,
          String.format("%.2f", DEFAULT_PATCH_HARD_CAP_USD));
    }

    // Patch mode runs on PLANNING-tier (Sonnet 4.6 / GPT-5.4 / Gemini 3 Pro Preview).
    // submitRun stamped that on `plannerModel` when isPatch was true; fall back to the
    // run-level worker default if for some reason it's null.
    String model = run.getPlannerModel();
    if (model == null || model.isBlank()) model = run.getWorkerModel();

    // V182 layer-1 — read userRequest from the run row (not the param) so the
    // bench services' RunStarted-time updates flow through. SwebenchTaskService
    // captures the failing-test output inside the container and prepends it to
    // userRequest before the async pipeline thread picks the row up; this is
    // the read-back point where the prefixed request takes effect.
    String userRequestLive = run.getUserRequest();
    if (userRequestLive == null || userRequestLive.isBlank()) userRequestLive = userRequest;

    log.info(
        "PATCH dispatch run={} provider={} model={} userRequestLen={}",
        runId,
        provider,
        model,
        userRequestLive == null ? 0 : userRequestLive.length());
    broadcaster.emit(
        runId, RunEvent.log(runId, "patch", "Patch-mode agent starting (single-loop)"));

    perky.start(runId, "WORKING");
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setStatus("WORKING");
              runRepo.save(r);
            });

    // RL-2026-05-035 (PATCH-learning win) — inject the project's codemap so the PATCH agent
    // localizes from the generated map instead of grepping the tree from scratch. Project autofix
    // surfaces have a projectId + fresh codemap; blind bench runs have neither → no-op (fail-safe).
    if (run.getProjectId() != null && featureFlagService.isEnabled("codeintel.inject.enabled")) {
      try {
        String codeIntel =
            buildCodeIntelContext(
                run.getProjectId(), projectRepoService.resolveForRun(run.getProjectId(), null));
        if (!codeIntel.isBlank()) {
          userRequestLive = codeIntel + "\n\n---\n\nTASK:\n" + userRequestLive;
          log.info("PATCH run={} injected code-intel ({} chars)", runId, codeIntel.length());
        }
      } catch (Exception e) {
        log.warn("PATCH run={} code-intel injection skipped: {}", runId, e.getMessage());
      }
    }

    com.navneet.executespec.agent.WorkerResult result;
    try {
      result = patchAgent.run(provider, model, userRequestLive, rootCtx);
    } catch (Exception e) {
      log.error("PATCH run={} dispatch threw", runId, e);
      runRepo
          .findById(runId)
          .ifPresent(
              r -> {
                r.setStatus("FAILED");
                r.setStatusReason("PATCH_AGENT_ERROR");
                r.setCompletedAt(Instant.now());
                runRepo.save(r);
              });
      eventPublisher.publishEvent(
          new PipelineEvent.RunFailed(runId, "patch agent error: " + e.getClass().getSimpleName()));
      broadcaster.emit(runId, RunEvent.complete(runId, "FAILED", "PATCH_AGENT_ERROR"));
      return;
    }

    // Map outcome → terminal status + reason.
    final String terminalStatus;
    final String terminalReason;
    switch (result.outcome()) {
      case COMPLETED -> {
        terminalStatus = "COMPLETE";
        terminalReason = "CLEAN_COMPLETION";
      }
      case COMPLETED_BY_STAGNATION -> {
        terminalStatus = "COMPLETE";
        terminalReason = "PATCH_STAGNATION";
      }
      case STOPPED_WITHOUT_COMPLETE -> {
        terminalStatus = "COMPLETE";
        terminalReason = "PATCH_STOPPED_WITHOUT_COMPLETE";
      }
      case ITERATION_LIMIT_EXCEEDED -> {
        terminalStatus = "PARTIAL";
        terminalReason = "PATCH_ITER_LIMIT";
      }
      case BUDGET_EXCEEDED -> {
        terminalStatus = "PARTIAL";
        terminalReason = "PATCH_BUDGET_EXCEEDED";
      }
      case ERROR -> {
        terminalStatus = "FAILED";
        terminalReason = "PATCH_AGENT_ERROR";
      }
      default -> {
        terminalStatus = "FAILED";
        terminalReason = "PATCH_UNKNOWN_OUTCOME";
      }
    }

    // Snapshot cost + tokens BEFORE flipping to terminal so dashboards reflect the
    // patch agent's actual spend. The build-mode flow attributes per-phase under
    // persistPhaseTokens; patch mode has no phases to attribute against, so the
    // run row reads directly from the tracker's accumulator.
    RunCostTracker.Snapshot patchCost = costTracker.snapshot(runId);
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setStatus(terminalStatus);
              r.setStatusReason(terminalReason);
              r.setCompletedAt(Instant.now());
              if (patchCost.costUsd() > 0d) {
                r.setActualCostUsd(patchCost.costUsd());
              } else if (r.getActualCostUsd() == null) {
                r.setActualCostUsd(0d);
              }
              int persistedIn = r.getTotalInputTokens() == null ? 0 : r.getTotalInputTokens();
              int persistedOut = r.getTotalOutputTokens() == null ? 0 : r.getTotalOutputTokens();
              if (patchCost.inputTokens() > persistedIn) {
                r.setTotalInputTokens(patchCost.inputTokens());
              }
              if (patchCost.outputTokens() > persistedOut) {
                r.setTotalOutputTokens(patchCost.outputTokens());
              }
              runRepo.save(r);
            });

    log.info(
        "PATCH terminal run={} status={} reason={} iter={} toolCalls={} latencyMs={}"
            + " in={} out={} ${}",
        runId,
        terminalStatus,
        terminalReason,
        result.iterations(),
        result.toolCallsDispatched(),
        result.totalLatencyMs(),
        patchCost.inputTokens(),
        patchCost.outputTokens(),
        String.format("%.4f", patchCost.costUsd()));

    // V183 (2026-05-08) — finding-triggered patch runs are project-mode (PR review autofix,
    // tech debt fix, code review autofix, CI auto-fix). They live on a real customer
    // project + repo and want their workspace diff pushed to the remote — same way build
    // mode does. Bench sources (`swebench`, `programbench`) are bench-mode and the bench
    // services capture the diff via `docker exec git diff` themselves, so we skip the
    // push for them. The discriminator is the source field the FindingPatchSubmitter
    // stamps as `finding:<TYPE>`.
    if (("COMPLETE".equals(terminalStatus) || "PARTIAL".equals(terminalStatus))
        && run.getSourceType() != null
        && run.getSourceType().startsWith("finding:")) {
      // 2026-05-13 audit Fix-A — same PostEditFormatter pass the BUILD-mode reviewer
      // runs. Without this, autofix surfaces (PR Review / TechDebt / CI failure /
      // Code Review severity buckets) pushed unformatted code to the existing PR
      // branch and CI's `black --check` / `prettier --check` rejected it — making
      // the autofix look like it "didn't fix anything". Idempotent + never throws.
      try {
        PostEditFormatter.Result fmt = postEditFormatter.run(rootCtx.workspaceRoot(), runId);
        if (fmt.filesReformatted() > 0 || !fmt.errors().isEmpty()) {
          log.info(
              "PATCH run={} post-edit formatter — reformatted={} formatters={} errors={}",
              runId,
              fmt.filesReformatted(),
              fmt.formattersRun(),
              fmt.errors());
        }
      } catch (Exception fmtEx) {
        log.warn(
            "PATCH run={} post-edit formatter threw (continuing to push): {}",
            runId,
            fmtEx.getMessage());
      }
      try {
        PipelineRun fresh = runRepo.findById(runId).orElse(null);
        if (fresh != null) {
          scmPushService.push(fresh);
          log.info(
              "PATCH run={} auto-pushed to SCM (finding-triggered={})", runId, run.getSourceType());
        }
      } catch (Exception pushEx) {
        // Manual re-push via /runs/{id}/push-github is still available; log and continue.
        log.warn(
            "PATCH run={} auto-push failed (manual push still works): {}",
            runId,
            pushEx.getMessage());
      }
    }
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            "patch",
            "Patch-mode complete: " + result.outcome() + " in " + result.iterations() + " iters"));
    broadcaster.emit(runId, RunEvent.complete(runId, terminalStatus, terminalReason));

    if ("COMPLETE".equals(terminalStatus)) {
      eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
    } else {
      eventPublisher.publishEvent(new PipelineEvent.RunFailed(runId, terminalReason));
    }
  }

  // ─── persistence helpers ────────────────────────────────────────────────

  private void persistPlan(String runId, Plan plan) {
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              try {
                r.setPlanJson(objectMapper.writeValueAsString(plan));
              } catch (Exception e) {
                log.warn("RunOrchestrator run={} failed to serialize plan JSON", runId, e);
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
                    "RunOrchestrator run={} failed to serialize plan_requirements_json", runId, e);
              }
              runRepo.save(r);
            });
  }

  private void persistTasks(String runId, Plan plan) {
    String prefix = runId.substring(0, 8) + "_";
    for (PlannedTask task : plan.tasks()) {
      String taskId = prefix + task.id();
      AgentTask row =
          AgentTask.builder()
              .taskId(taskId)
              .runId(runId)
              .title(task.title())
              .complexity(task.complexity().name())
              .status("PENDING")
              // v2.6.2 slice 5d — record the planner-shape metadata so
              // StartupRunResumer can tell EXPANDED vs PENDING tasks
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
        log.warn("RunOrchestrator run={} failed to serialize task {}", runId, taskId, e);
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
            "RunOrchestrator run={} failed to serialize meets_requirements for {}",
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
   * from {@code PlannedTask.id}.
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
                log.warn("RunOrchestrator run={} failed to serialize review JSON", runId, e);
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
    log.debug("RunOrchestrator run={} workspace={}", runId, path);
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
      log.info("RunOrchestrator run={} final commit exit={}", runId, exit);
    } catch (Exception e) {
      log.warn("RunOrchestrator run={} final commit failed: {}", runId, e.getMessage());
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

    log.info("RunOrchestrator cancel: run={} prev={}", runId, status);

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

  /** Wall-clock cap for a paused run before {@link StartupRunResumer} treats it as abandoned. */
  private static final java.time.Duration PAUSED_RUN_MAX_AGE = java.time.Duration.ofHours(24);

  /**
   * Request a pause. Sets {@code pause_requested = true} on the row + emits the SSE so the UI flips
   * immediately. The actual transition to {@code PAUSED} happens cooperatively at the next task or
   * iteration boundary inside {@code SequentialExecutor.executeOne} or {@code WorkerAgent.run}; see
   * those for the checkpoint shape. The in-flight LLM round-trip is NOT cancelled (mid-call abort
   * is the deferred chunk #4 of the design — provider-specific work).
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

    log.info("RunOrchestrator pause: run={} prev={}", runId, status);

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
   * ContextStore}'s saved snapshot for the partly-done task (if any).
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
          "RunOrchestrator resume rejected: workspace not on this node (cross-JVM resume needs"
              + " GCS shipping — chunk #3 of pause/resume). run={} workspace={}",
          runId,
          workspace);
      return ResumeOutcome.WORKSPACE_GONE;
    }

    log.info(
        "RunOrchestrator resume: run={} prev=PAUSED pausedTaskId={} pausedAtIteration={}",
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
    // skips earlier tasks; ContextStore's saved snapshot rehydrates the
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

  /** Outcome of a {@link #resumeAfterCrash(String)} dispatch. */
  public enum CrashResumeOutcome {
    /** Resume queued — pipeline is now running on the worker pool. */
    OK,
    /** Run id not found in the DB. */
    NOT_FOUND,
    /** Run isn't in a status that this resume path handles (not PLANNING/WORKING/REVIEWING). */
    NOT_APPLICABLE,
    /** Hit the per-run resume-attempt cap; the run was failed instead of retried. */
    EXHAUSTED,
    /** Workspace gone from disk (deploy + scale-down on a different node). */
    WORKSPACE_GONE,
    /** Persisted plan missing or corrupted — can't reconstruct execution context. */
    PLAN_MISSING,
  }

  /**
   * Crash-recovery resume — runs interrupted by a JVM SIGTERM / OOM / crash mid-flight. Distinct
   * from the user-driven {@link #resume(String)} which only covers PAUSED runs (those have a
   * different state shape, e.g. {@code pause_requested=true} and a sibling Redis pause flag).
   *
   * <p>Called from {@link com.navneet.executespec.recovery.StartupRunResumer} on {@code
   * ApplicationReadyEvent}. Strategy by status:
   *
   * <ul>
   *   <li>{@code PLANNING} — re-plan from scratch by calling {@code runPipeline(...)} with the
   *       persisted spec. Planning is idempotent (classifier + outline are deterministic-ish); the
   *       previous JVM's plan attempt is discarded.
   *   <li>{@code WORKING} — load the persisted plan, reuse the existing workspace, call {@link
   *       com.navneet.executespec.harness.SequentialExecutor#execute} with a {@code ResumeRequest}
   *       so terminal AgentTasks are skipped and the in-flight task picks up from its Redis
   *       snapshot. Then runs reviewer + report.
   *   <li>{@code REVIEWING} — execute already finished pre-crash, re-run reviewer (idempotent per
   *       the workspace + plan it sees). Then report.
   *   <li>{@code AWAITING_APPROVAL} — left as-is (caller checked); the gate's Redis state carries
   *       through.
   * </ul>
   *
   * <p>Per-run cap: {@link com.navneet.executespec.recovery.StartupRunResumer#MAX_RESUME_ATTEMPTS}
   * — same constant the resumer enforces. Each call increments {@code pipeline_run.resume_attempts}
   * BEFORE submitting to the pool so a crash inside resume can't loop forever.
   */
  public CrashResumeOutcome resumeAfterCrash(String runId) {
    var opt = runRepo.findById(runId);
    if (opt.isEmpty()) return CrashResumeOutcome.NOT_FOUND;
    var run = opt.get();
    String status = run.getStatus();
    if (!"PLANNING".equals(status) && !"WORKING".equals(status) && !"REVIEWING".equals(status)) {
      return CrashResumeOutcome.NOT_APPLICABLE;
    }

    int attempts = run.getResumeAttempts() == null ? 0 : run.getResumeAttempts();
    if (attempts >= com.navneet.executespec.recovery.StartupRunResumer.MAX_RESUME_ATTEMPTS) {
      log.warn(
          "resumeAfterCrash run={} status={} attempts={} EXHAUSTED — failing run",
          runId,
          status,
          attempts);
      terminate(runId, "FAILED", "resume cap reached after " + attempts + " attempts", null);
      return CrashResumeOutcome.EXHAUSTED;
    }

    // Workspace must be on this node. Cross-node resume needs GCS shipping (chunk #3 of the
    // pause/resume design — same constraint as the existing PAUSED resume path).
    java.nio.file.Path workspace =
        java.nio.file.Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(workspace)) {
      // RL-2026-05-041 (audit #2) — mid-run durability. Before giving up, try to restore the latest
      // GCS checkpoint (written at each task boundary by GcsTaskCheckpointService). This makes
      // mid-run work durable across node loss / Cloud Run scale-down / MIG auto-heal instead of
      // failing WORKSPACE_GONE + refunding the customer for work that was actually done.
      boolean restored = false;
      try {
        workspaceRestoreService.ensureWorkspaceAvailable(runId);
        restored = java.nio.file.Files.isDirectory(workspace);
      } catch (Exception restoreEx) {
        log.warn(
            "resumeAfterCrash run={} GCS workspace restore failed: {}",
            runId,
            restoreEx.getMessage());
      }
      if (restored) {
        log.info(
            "resumeAfterCrash run={} restored workspace from GCS checkpoint — resuming", runId);
      } else {
        log.warn(
            "resumeAfterCrash run={} workspace not on this node + no restorable GCS checkpoint —"
                + " failing cleanly (credits refunded via RunFailed)",
            runId);
        terminate(runId, "FAILED", "workspace gone (" + workspace + ")", null);
        return CrashResumeOutcome.WORKSPACE_GONE;
      }
    }

    // PLANNING crash → just re-run the planner (cheap) by calling the standard runPipeline.
    // The previous plan attempt is discarded; idempotent enough for the planner phase.
    if ("PLANNING".equals(status)) {
      run.setResumeAttempts(attempts + 1);
      run.setResumedAt(java.time.Instant.now());
      runRepo.save(run);
      log.info(
          "resumeAfterCrash run={} status=PLANNING attempt={} re-running planner",
          runId,
          attempts + 1);
      broadcaster.emit(runId, RunEvent.runStatus(runId, "PLANNING"));
      broadcaster.emit(
          runId, RunEvent.log(runId, "resume", "Resuming after crash — re-running planner"));
      RunPipelineRequest req = RunPipelineRequest.fromPausedRow(run);
      Future<?> future =
          pool.submit(
              () -> {
                try {
                  runPipelineForResume(req);
                } finally {
                  activeRuns.remove(runId);
                }
              });
      activeRuns.put(runId, future);
      return CrashResumeOutcome.OK;
    }

    // WORKING / REVIEWING — load persisted plan and skip planning phase entirely.
    String planJson = run.getPlanJson();
    if (planJson == null || planJson.isBlank()) {
      log.warn(
          "resumeAfterCrash run={} status={} planJson missing — can't resume mid-execute, failing",
          runId,
          status);
      terminate(runId, "FAILED", "plan missing on resume", null);
      return CrashResumeOutcome.PLAN_MISSING;
    }
    final com.navneet.executespec.agent.Plan plan;
    try {
      plan = objectMapper.readValue(planJson, com.navneet.executespec.agent.Plan.class);
    } catch (Exception e) {
      log.warn(
          "resumeAfterCrash run={} status={} planJson deserialize failed: {}",
          runId,
          status,
          e.getMessage());
      terminate(runId, "FAILED", "plan corrupted on resume: " + shortMsg(e), null);
      return CrashResumeOutcome.PLAN_MISSING;
    }

    run.setResumeAttempts(attempts + 1);
    run.setResumedAt(java.time.Instant.now());
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, status));
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            "resume",
            "Resuming after crash from " + status + " (attempt " + (attempts + 1) + ")"));

    log.info(
        "resumeAfterCrash run={} status={} attempt={} dispatching to pool",
        runId,
        status,
        attempts + 1);

    final Path resumeWorkspace = workspace;
    final boolean resumeFromReviewing = "REVIEWING".equals(status);
    Future<?> future =
        pool.submit(
            () -> {
              try {
                runPipelineFromExecuteCheckpoint(runId, plan, resumeWorkspace, resumeFromReviewing);
              } finally {
                activeRuns.remove(runId);
              }
            });
    activeRuns.put(runId, future);
    return CrashResumeOutcome.OK;
  }

  /**
   * Inner crash-resume orchestration — assumes plan is loaded, workspace is present, and the
   * current JVM owns the row's lifecycle from here on. Skips clone / planning phase entirely;
   * dispatches straight to executor (with a {@link
   * com.navneet.executespec.harness.SequentialExecutor.ResumeRequest} so terminal AgentTasks are
   * skipped) or — for {@code REVIEWING} — straight to the reviewer.
   */
  private void runPipelineFromExecuteCheckpoint(
      String runId,
      com.navneet.executespec.agent.Plan plan,
      Path workspace,
      boolean fromReviewing) {
    Instant startedAt = Instant.now();
    try {
      // Re-resolve BYOK keys for this resumed run — same pattern as runPipeline().
      try {
        PipelineRun pre = runRepo.findById(runId).orElse(null);
        if (pre != null && pre.getCreatedByUserId() != null) {
          keyContextStore.resolveAndPut(runId, pre.getCreatedByUserId());
        }
      } catch (Exception keyEx) {
        log.warn("resume run={} BYOK resolution failed: {}", runId, keyEx.getMessage());
      }

      ExecutionContext rootCtx = new ExecutionContext(runId, "__root__", workspace.toRealPath());

      PipelineRun row = runRepo.findById(runId).orElse(null);
      if (row == null) {
        log.warn("resume run={} row vanished mid-resume", runId);
        return;
      }
      String provider = row.getAiProvider() == null ? "anthropic" : row.getAiProvider();
      String model = row.getWorkerModel();
      String upperProvider = provider.toUpperCase();
      String projectId = row.getProjectId();
      List<UUID> selectedRepoIds = deserializeRepoIds(row.getSelectedRepoIds());

      ExecutionResult execResult = null;
      RunCostTracker.Snapshot afterExec = null;
      if (!fromReviewing) {
        // Status is WORKING — call executor with ResumeRequest so terminal AgentTasks are
        // skipped and the in-flight task resumes from its Redis snapshot.
        updateStatus(runId, "WORKING");
        try {
          execResult =
              executor.execute(
                  ExecutionRequest.builder()
                      .plan(plan)
                      .provider(provider)
                      .model(model)
                      .runContext(rootCtx)
                      .maxIterationsPerTask(null)
                      .enableSmokeTests(true)
                      .designBlock(null) // not persisted on the row — runs without it on resume
                      .projectId(projectId)
                      .workspaceSummary(buildMultiRepoSummary(projectId, selectedRepoIds))
                      .build(),
                  // Auto-detect: ResumeRequest with null targetTaskId tells the executor to walk
                  // the plan and resume at the FIRST non-terminal task in DB.
                  new com.navneet.executespec.harness.SequentialExecutor.ResumeRequest(null));
        } catch (Exception e) {
          log.error("resume run={} executor failed", runId, e);
          terminate(runId, "FAILED", "executor on resume: " + shortMsg(e), startedAt);
          return;
        }
        applyExecutionResultToTasks(runId, execResult);
        afterExec = costTracker.snapshot(runId);
      }

      // Reviewer — runs for both WORKING-resume (after executor) and REVIEWING-resume (re-run
      // the reviewer; idempotent against the same workspace + plan).
      updateStatus(runId, "REVIEWING");
      ReviewerResult reviewResult = null;
      try {
        reviewResult =
            reviewer.review(
                ReviewerRequest.builder()
                    .provider(provider)
                    .model(model)
                    .context(rootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(false)
                    .build());
        persistReview(runId, reviewResult);
      } catch (Exception e) {
        log.warn("resume run={} reviewer failed — recording partial review", runId, e);
        persistReviewFailure(runId, e);
      }

      // README + validation report + test gen + preview — same as runPipeline's tail. We only
      // need to re-run these if execution actually ran (fromReviewing=true means they may have
      // already happened pre-crash, but they're idempotent so re-running is safe and cheap).
      ensureReadme(
          runId, workspace, row.getUserRequest(), plan, execResult, reviewResult, startedAt);
      generateValidationReport(runId, execResult, reviewResult, plan);

      // Terminal status — same decision tree as runPipeline.resolveTerminalStatus.
      String userRequest = row.getUserRequest();
      String prReviewComments = null; // not threaded for resumed runs
      finalizeRunAfterResume(
          runId, plan, execResult, reviewResult, startedAt, upperProvider, model);
    } catch (Exception e) {
      log.error("resume run={} pipeline failed unexpectedly", runId, e);
      terminate(runId, "FAILED", "resume failure: " + shortMsg(e), startedAt);
    }
  }

  /**
   * Compact terminal-status decision for crash-resumed runs. Mirrors the logic at the tail of
   * {@link #runPipeline} but works against the already-persisted execResult / reviewerResult
   * (callers compute terminal status from those plus persisted task outcomes). Reviewer-failure
   * with no fixable issues → COMPLETE_WITH_CONCERNS; everything else uses the existing helper.
   */
  private void finalizeRunAfterResume(
      String runId,
      com.navneet.executespec.agent.Plan plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Instant startedAt,
      String upperProvider,
      String model) {
    // Mark the run terminal. resolveTerminalStatus already exists for the regular path; the
    // crash-resume path reuses it so the (status, statusReason) tuple matches what users see on
    // a normal run completion. If execResult is null (pure REVIEWING-resume), pass an empty
    // result so the helper falls through cleanly.
    ExecutionResult effectiveExec =
        execResult == null
            ? ExecutionResult.builder()
                .overall(ExecutionResult.Overall.ALL_SUCCESS)
                .tasksExecuted(0)
                .tasksSuccessful(0)
                .tasksWithWarning(0)
                .tasksFailed(0)
                .totalLatencyMs(0L)
                .build()
            : execResult;
    var terminal = resolveTerminalStatus(effectiveExec, reviewResult);
    terminate(runId, terminal.status(), terminal.reason(), startedAt);
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
        "RunOrchestrator HALT-ALL by SUPER_ADMIN: {} runs cancelled, reason={}",
        halted.size(),
        reason == null ? "(none)" : reason);
    return halted;
  }

  /**
   * 2026-05-04 — admin escape hatch for stuck tasks (companion to the @Retryable persist that
   * prevents the bug class going forward). Flips {@code agent_task.status} from {@code WORKING} or
   * {@code PENDING} to {@code FAILED}, stamps {@code last_llm_failure} with the supplied reason for
   * the audit trail, and emits the SSE event so the dashboard updates immediately. Refuses to touch
   * tasks already in a terminal status — those finished legitimately and force- failing them would
   * lose information. Returns a small report for the admin endpoint.
   *
   * @throws IllegalArgumentException if the run/task isn't found
   * @throws IllegalStateException if the task is already in a terminal status
   */
  public java.util.Map<String, Object> forceFailTask(String runId, String taskId, String reason) {
    if (runRepo.findById(runId).isEmpty()) {
      throw new IllegalArgumentException("Run not found: " + runId);
    }
    String key = runId.substring(0, 8) + "_" + taskId;
    var task =
        taskRepo
            .findById(key)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    String current = task.getStatus();
    if ("COMPLETE".equals(current)
        || "FAILED".equals(current)
        || "PARTIAL".equals(current)
        || "BLOCKED".equals(current)
        || "COMPLETED_WITH_WARNING".equals(current)) {
      throw new IllegalStateException(
          "Task already terminal (status=" + current + "). Won't overwrite legitimate completion.");
    }
    task.setStatus("FAILED");
    task.setLastLlmFailure("FORCE_FAILED_BY_ADMIN: " + reason);
    if (task.getCompletedAt() == null) task.setCompletedAt(java.time.Instant.now());
    taskRepo.save(task);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "FAILED"));
    log.warn(
        "RunOrchestrator forceFailTask: run={} task={} prevStatus={} -> FAILED reason={}",
        runId,
        taskId,
        current,
        reason);
    return java.util.Map.of(
        "runId", runId,
        "taskId", taskId,
        "previousStatus", current,
        "newStatus", "FAILED",
        "reason", reason);
  }

  /**
   * Retry a single task within a terminal V2 run. 2026-05-04 rewrite: actually re-executes the task
   * instead of just flipping its status flag (the previous baseline was a documented stub).
   *
   * <p>Strategy:
   *
   * <ol>
   *   <li>Reset the target task's persisted status to {@code PENDING} so {@link
   *       SequentialExecutor#execute(ExecutionRequest, SequentialExecutor.ResumeRequest)} treats it
   *       as eligible to run.
   *   <li>Reuse the persisted plan from {@code pipeline_run.plan_json} so we don't re-plan.
   *   <li>Call {@code executor.execute(req, ResumeRequest(targetTaskId))} — that path skips every
   *       OTHER task that's already terminal (so completed tasks aren't redone) and runs the target
   *       task either fresh OR from its Redis snapshot if one is available (the latter only
   *       meaningful when retry is invoked while the task was mid-execution; for terminal retries
   *       the snapshot is typically gone).
   *   <li>After execute, the run's terminal status may have flipped — e.g. PARTIAL → COMPLETE if
   *       the retry succeeded. Re-resolve via the standard helper.
   * </ol>
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

    // Workspace + plan must still be on disk; retry is same-JVM only (cross-JVM resume needs
    // GCS shipping — same constraint as resumeAfterCrash).
    java.nio.file.Path workspace =
        java.nio.file.Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(workspace)) {
      throw new IllegalStateException(
          "Workspace gone — retry is same-JVM only. Submit a fresh run instead.");
    }
    String planJson = run.getPlanJson();
    if (planJson == null || planJson.isBlank()) {
      throw new IllegalStateException(
          "Plan JSON missing on the run row — can't retry a single task without it.");
    }
    final com.navneet.executespec.agent.Plan plan;
    try {
      plan = objectMapper.readValue(planJson, com.navneet.executespec.agent.Plan.class);
    } catch (Exception e) {
      throw new IllegalStateException("Plan JSON corrupted: " + e.getMessage());
    }

    log.info(
        "RunOrchestrator retryTask: run={} task={} dispatching real re-execution via"
            + " SequentialExecutor.execute(req, ResumeRequest)",
        runId,
        taskId);

    // Reset the task to PENDING so the executor's resume logic runs it fresh. PENDING is the
    // "never started" state; the executor's resolveResumeAction will return RUN_FRESH for it.
    task.setStatus("PENDING");
    task.setStartedAt(null);
    task.setCompletedAt(null);
    taskRepo.save(task);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, taskId, "PENDING"));

    // Flip the run row back to WORKING so SSE listeners + the dashboard show progress.
    String prevRunStatus = run.getStatus();
    run.setStatus("WORKING");
    run.setCompletedAt(null);
    runRepo.save(run);
    broadcaster.emit(runId, RunEvent.runStatus(runId, "WORKING"));
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            "retry",
            "Retrying task " + taskId + " (run was " + prevRunStatus + " → now WORKING)"));

    final java.nio.file.Path retryWorkspace = workspace;
    final String fullTargetTaskId = task.getTaskId(); // already prefixed with runId-short
    Future<?> future =
        pool.submit(
            () -> {
              try {
                // Resolve the existing run row's provider/model so we use the same dispatch
                // tier mapping as the original execution.
                PipelineRun row = runRepo.findById(runId).orElse(null);
                String provider =
                    row == null || row.getAiProvider() == null ? "anthropic" : row.getAiProvider();
                String model = row == null ? null : row.getWorkerModel();

                ExecutionContext rootCtx =
                    new ExecutionContext(runId, "__root__", retryWorkspace.toRealPath());
                List<UUID> selectedRepoIds =
                    row == null ? List.of() : deserializeRepoIds(row.getSelectedRepoIds());

                ExecutionResult result =
                    executor.execute(
                        ExecutionRequest.builder()
                            .plan(plan)
                            .provider(provider)
                            .model(model)
                            .runContext(rootCtx)
                            .maxIterationsPerTask(null)
                            .enableSmokeTests(true)
                            .designBlock(null)
                            .projectId(row == null ? null : row.getProjectId())
                            .workspaceSummary(
                                buildMultiRepoSummary(
                                    row == null ? null : row.getProjectId(), selectedRepoIds))
                            .build(),
                        new com.navneet.executespec.harness.SequentialExecutor.ResumeRequest(
                            fullTargetTaskId));
                applyExecutionResultToTasks(runId, result);

                // Re-resolve terminal status now that the retry has finished. Reviewer is NOT
                // re-run on a single-task retry — that's a different operation (retryValidation).
                var terminal = resolveTerminalStatus(result, null);
                terminate(runId, terminal.status(), terminal.reason(), null);
              } catch (Exception e) {
                log.error(
                    "RunOrchestrator retryTask dispatch failed run={} task={}", runId, taskId, e);
                terminate(runId, "FAILED", "retryTask dispatch: " + shortMsg(e), null);
              } finally {
                activeRuns.remove(runId);
              }
            });
    activeRuns.put(runId, future);
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
    log.info("RunOrchestrator retryValidation: run={}", runId);
    pool.submit(
        () -> {
          try {
            // Minimal inputs — the plan + execution result are reconstructed from
            // persisted task rows. This is a best-effort regeneration, not a full
            // re-review.
            generateValidationReport(runId, null, null, null);
          } catch (Exception e) {
            log.error("RunOrchestrator retryValidation failed run={}", runId, e);
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
   *   <li>Submits an async job that runs {@link ReviewerAgent#review} from scratch against the
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
    log.info("RunOrchestrator reReview: run={} previous status={}", runId, run.getStatus());

    // Synchronous reset so the caller's 202 reflects the live state — once we return, the run is
    // already showing REVIEWING in any subsequent GET. The actual reviewer pass kicks off async.
    validationReportRepository
        .findByRunId(runId)
        .ifPresent(
            existing -> {
              log.info(
                  "RunOrchestrator reReview: deleting existing validation_report id={} for"
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
            log.error("RunOrchestrator reReview run={} failed unexpectedly", runId, e);
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
      log.warn("RunOrchestrator reReview: run {} disappeared mid-flight", runId);
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
        modelResolver.resolve("ReviewerAgent", provider, AgentTier.PLANNING);

    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    if (!Files.isDirectory(workspace)) {
      log.warn(
          "RunOrchestrator reReview: workspace {} missing for run {}, marking PARTIAL",
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
        Plan plan = objectMapper.readValue(run.getPlanJson(), Plan.class);
        buildCommand = plan.buildCommand();
      } catch (Exception e) {
        log.debug(
            "RunOrchestrator reReview run={} could not parse planJson; relying on auto-detect",
            runId);
      }
    }

    ReviewerResult reviewResult = null;
    try {
      log.info(
          "RunOrchestrator reReview run={} provider={} model={} buildCmd={}",
          runId,
          provider,
          reviewerModel.getModel(),
          buildCommand == null ? "(auto)" : buildCommand);
      reviewResult =
          reviewer.review(
              ReviewerRequest.builder()
                  .provider(provider.name().toLowerCase())
                  .model(reviewerModel.getModel())
                  .context(rootCtx)
                  .buildCommand(buildCommand)
                  .skipWiringPass(false)
                  .build());
      persistReview(runId, reviewResult);
    } catch (Exception e) {
      log.warn("RunOrchestrator reReview run={} reviewer failed: {}", runId, e.getMessage());
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
          "RunOrchestrator reReview run={} report generation failed: {}", runId, e.getMessage());
    }

    StatusAndReason terminal = resolveTerminalStatus(execResult, reviewResult);
    var refreshed = runRepo.findById(runId).orElse(run);
    refreshed.setStatus(terminal.status());
    refreshed.setStatusReason(terminal.reason());
    refreshed.setCompletedAt(Instant.now());
    runRepo.save(refreshed);
    broadcaster.emit(runId, RunEvent.complete(runId, terminal.status(), terminal.reason()));
    log.info(
        "RunOrchestrator reReview run={} done status={} reason={} reviewerVerdict={}",
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
  /**
   * RL-2026-05-050 — true when this run is a benchmark "code-gen only" run (source prefix {@code
   * swebench-codegen}). These execute the real classifier→planner→worker path but STOP after the
   * worker (no reviewer / validation / push) so the captured diff is a clean, scoreable minimal
   * patch. Never true for production customer runs (their source is MANUAL / GITHUB_* / REST).
   */
  private boolean isCodeGenOnlyRun(String runId) {
    return runRepo
        .findById(runId)
        .map(r -> r.getSourceType())
        .filter(s -> s != null)
        .map(s -> s.startsWith("swebench-codegen"))
        .orElse(false);
  }

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
    RunCostTracker.Snapshot cost = costTracker.finalizeRun(runId);
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

              // Audit 2026-05-13 Stage 2 — record (feature vector → actual cost) for k-NN
              // cost estimation. Best-effort; failures swallowed inside the service.
              costHistoryService.recordOnTerminal(runId);
            });
    broadcaster.emit(runId, RunEvent.runStatus(runId, finalStatus));
    broadcaster.emit(runId, RunEvent.costUpdate(runId, cost.costUsd()));
    broadcaster.emit(runId, RunEvent.complete(runId, finalStatus, statusReason));
    broadcaster.complete(runId);
    // V2 run-level metrics are emitted by RunService only. This orchestrator is a
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
   * <p>The planner is instructed (in prompts/planner.md invariant #3) to emit a "Create README.md"
   * task for non-trivial specs. That user-facing README is authored by the worker and takes
   * precedence — we never overwrite it. For trivial specs (single-file, no framework) the planner
   * skips the README task; this method then writes a deterministic run-meta README so the
   * download-zip experience always includes an entry-point doc.
   *
   * <p>Best-effort — IO errors are logged and swallowed. A missing README isn't worth failing a run
   * over.
   */
  private void ensureReadme(
      String runId,
      Path workspace,
      String userRequest,
      Plan plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Instant startedAt) {
    Path readme = workspace.resolve("README.md");
    if (Files.exists(readme)) {
      log.debug(
          "RunOrchestrator run={} README.md already present (planner-authored), skipping", runId);
      return;
    }
    try {
      Files.writeString(
          readme, renderRunReadme(runId, userRequest, plan, execResult, reviewResult, startedAt));
      log.info(
          "RunOrchestrator run={} wrote run-meta README.md ({} bytes)", runId, Files.size(readme));
    } catch (IOException e) {
      log.warn("RunOrchestrator run={} failed to write README.md: {}", runId, e.getMessage());
    }
  }

  private String renderRunReadme(
      String runId,
      String userRequest,
      Plan plan,
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
   *   <li>{@link ReviewerResult} → synthesized {@link ReviewReport} with the mapped verdict +
   *       summary
   *   <li>Final build passed → {@link CompileResult#passed(int)} else {@code failed}
   * </ul>
   */
  private void generateValidationReport(
      String runId, ExecutionResult execResult, ReviewerResult reviewResult, Plan plan) {
    generateValidationReport(runId, execResult, reviewResult, plan, java.util.List.of());
  }

  /**
   * RL-2026-05-037 — overload accepting extra synthetic issues (breaking-change findings from the
   * contract gate) to append to the report's issue list so they surface in the run Issues panel.
   * The 4-arg form delegates here with an empty list.
   */
  private void generateValidationReport(
      String runId,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      Plan plan,
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> extraIssues) {
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

      ReviewReport review = new ReviewReport();
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
      // but V2's ReviewerAgent skips it, so the ValidationReport was coming
      // back with accuracyScore=null. Call it here so the report gets populated.
      try {
        // RL-2026-05-041 (audit #19) — no OPENAI→ANTHROPIC reroute. AccuracyAssessmentService runs
        // through FallbackLlmRouter.callWithFallbackTracked, which routes OpenAI via
        // OpenAiNativeToolClient (not Spring AI's OpenAiChatModel), so the extra_body 400 can't
        // occur on this path. OpenAI runs now assess on OpenAI (correct provider attribution).
        LlmProvider provider = providerForAccuracy(run.getAiProvider());
        // 2026-05-06 — was "ReviewerAgentV2" (legacy class name). The actual reviewer is
        // ReviewerAgent in the YAML agent-tier-map. The 3-arg resolve form bypasses the
        // tier lookup so this didn't trigger a warn, but the class-name drift was real.
        ResolvedModel planningModel =
            modelResolver.resolve("ReviewerAgent", provider, AgentTier.PLANNING);
        AccuracyReport accuracy =
            accuracyAssessmentService.assess(
                runId,
                planningModel.providerName(),
                planningModel,
                run.getUserRequest() == null ? "" : run.getUserRequest(),
                List.of());
        review.setAccuracyReport(accuracy);
      } catch (Exception e) {
        log.warn("RunOrchestrator run={} accuracy assessment failed: {}", runId, e.getMessage());
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

      // V2's ReviewerAgent doesn't emit structured ReviewIssues — it just returns
      // a verdict enum + summary text. That leaves ReviewScoringService with an empty
      // issues list, so every quality dimension flat-lined at the anchor regardless
      // of what actually happened. Synthesise issues from the signals we DO have
      // (build pass/fail, reviewer verdict, accuracy report) so the dimensions
      // actually move when things go wrong.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> synthIssues =
          new java.util.ArrayList<>(
              synthesiseReviewIssues(reviewResult, compile, review.getAccuracyReport()));
      if (extraIssues != null && !extraIssues.isEmpty()) {
        synthIssues.addAll(extraIssues);
      }
      review.setIssues(synthIssues);

      // RL-2026-05-001 — run the worker-written tests in the workspace and feed the REAL
      // passed/total into the verifiedScore TESTS slot, instead of an LLM coverage guess. The
      // command is resolver-derived (not worker text), validated by ShellSandboxPolicy, and run in
      // the cleanroom container where one exists. Empty → the scorer falls back to LLM coverage, so
      // this can only ever make the TESTS slot more honest, never break report generation.
      com.navneet.executespec.testgen.model.TestExecutionSummary testSummary = null;
      if (featureFlagService.isEnabled("scoring.real-test-exec")) {
        try {
          Path testWorkspace =
              Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
          testSummary = workspaceTestRunner.runTests(runId, testWorkspace).orElse(null);
        } catch (Exception e) {
          log.warn(
              "RunOrchestrator run={} workspace test execution failed (fallback to coverage): {}",
              runId,
              e.getMessage());
        }
      }
      ReportInput input =
          new ReportInput(run, workerReports, review, compile, tasks, testSummary, List.of());
      // RL-2026-05-041 (audit #19 + E#37) — the old OPENAI→ANTHROPIC reroute (which mutated
      // run.aiProvider in-memory around this call) is removed. ValidationReportGenerator runs
      // through FallbackLlmRouter.callWithFallbackTracked, which dispatches OpenAI via
      // OpenAiNativeToolClient — NOT Spring AI's OpenAiChatModel. The Spring AI `extra_body` 400 is
      // structurally impossible on that path (it's the bug the native client was built to bypass),
      // so the reroute guarded a code path that isn't taken for OpenAI while introducing a
      // billing-attribution hazard (any reader of run.aiProvider during the swap saw the wrong
      // provider). The report now generates on the run's actual provider, entity untouched.
      validationReportService.generateAndPersist(runId, input);
      log.info("RunOrchestrator run={} validation report generated", runId);
    } catch (Exception e) {
      log.warn("RunOrchestrator run={} validation report failed: {}", runId, e.getMessage(), e);
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
  private void writeAgentContext(Path workspace, Plan plan) {
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

  private void persistRunTitle(String runId, Plan plan) {
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
  private String buildDesignBlock(String userRequest, Plan plan) {
    try {
      String hint = uiStyleResolver.extractStyleHint(userRequest);
      String plannerStyle = null; // Plan has no explicit ui_style field today
      String projectType = plan.projectType() == null ? "unknown" : plan.projectType();
      String designName = uiStyleResolver.resolveDesignName(hint, plannerStyle, projectType);
      if (designName == null) return "";
      String content = uiStyleResolver.loadDesignContent(designName);
      String block = uiStyleResolver.buildFrontendConstraintBlock(null, content);
      if (block == null || block.isBlank()) return "";
      log.info(
          "RunOrchestrator run=? design resolved='{}' (hint='{}', project={})",
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

  /**
   * Best-effort repo count for the cost estimate. For project-scoped runs the count is the number
   * of selected/linked repos in the workspace. For standalone runs the count is 1 when a repo URL
   * is set, 0 otherwise. Audit 2026-05-13 Stage 1 — feeds the reviewer's per-extra- repo wiring
   * overhead in the estimator.
   */
  private static int computeRepoCountForEstimate(PipelineRun runRow) {
    if (runRow == null) return 1;
    if (runRow.getProjectId() != null) {
      List<UUID> ids = deserializeRepoIds(runRow.getSelectedRepoIds());
      if (!ids.isEmpty()) return ids.size();
      // Project run with no explicit selectedRepoIds — fall back to 1 to avoid a
      // 0-cost codemap term that hides per-repo overhead on legacy rows.
      return 1;
    }
    return (runRow.getRepoUrl() != null && !runRow.getRepoUrl().isBlank()) ? 1 : 0;
  }

  private void persistEstimate(String runId, String provider, String model, Plan plan) {
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
    // 2026-05-13 audit Stage 1 — feed repoCount + isProjectScoped to the estimator so
    // post-planning costs (reviewer × per-repo wiring, codemap per repo) are accounted for.
    // Run 6024f510 (1 LOW task × 3 repos × project-scoped × Anthropic) was estimated at
    // $0.05-$0.09 against an actual $1.04 — the missing reviewer/codemap/cache-write
    // overhead was the bulk of the gap.
    PipelineRun runRow = runRepo.findById(runId).orElse(null);
    int repoCount = computeRepoCountForEstimate(runRow);
    boolean isProjectScoped = runRow != null && runRow.getProjectId() != null;

    // Audit 2026-05-13 Stage 2 — try historical k-NN first. Falls back to the Stage-1
    // formula when fewer than CostHistoryService.MIN_SAMPLES similar runs exist. As the
    // historical table fills (after deploy), this kicks in for popular shapes and the
    // formula remains the bootstrap fallback for novel shapes.
    String providerKey = provider == null ? null : provider.toUpperCase();
    String strategyKey = strategy == null ? null : strategy.name();
    Optional<RunCostTracker.Estimate> historical =
        costHistoryService.estimateFromHistory(
            strategyKey, providerKey, repoCount, complexities.size());
    RunCostTracker.Estimate est =
        historical.orElseGet(
            () ->
                costTracker.estimateRunTierAware(
                    provider, complexities, strategy, repoCount, isProjectScoped));
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              r.setCostEstimateLow(est.lowUsd());
              r.setCostEstimateHigh(est.highUsd());
              r.setCreditsCharged(est.credits());
              // GA-readiness P3.2 — honour customer-supplied cost cap (P3.2 helper).
              // 2026-05-08 — BYOK cost-cap exemption. When the user's org is OWN_KEYS, skip
              // OUR platform cap entirely; the customer pays their own API bill so we shouldn't
              // hard-stop them on ours. BUT we still honour their EXPLICIT userCostCap (typed
              // into NewRunModal) — that's their voluntary ceiling, not ours.
              //
              // Decision matrix:
              //   BYOK    + userCostCap set    → userCostCap   (customer's voluntary cap honoured)
              //   BYOK    + userCostCap null   → null          (no cap; fully BYOK)
              //   non-BYOK + (any)             → clamp(platform, userCostCap) (existing behaviour)
              boolean byok = apiKeyResolver.isOwnKeysMode(r.getCreatedByUserId());
              if (byok) {
                Double userCap = r.getUserCostCap();
                r.setHardCapUsd(userCap != null && userCap > 0 ? userCap : null);
              } else {
                r.setHardCapUsd(clampHardCap(est.hardCapUsd(), r.getUserCostCap()));
              }
              runRepo.save(r);
            });
    log.info(
        "RunOrchestrator run={} estimate strategy={} low=${} high=${} credits={} cap=${}"
            + " tasks={}",
        runId,
        strategy,
        String.format("%.4f", est.lowUsd()),
        String.format("%.4f", est.highUsd()),
        est.credits(),
        String.format("%.2f", est.hardCapUsd()),
        est.taskCount());
  }

  /**
   * GA-readiness P3.2 — clamp the platform-derived hard cost cap by the optional customer override.
   * The customer can lower the ceiling but not raise it past what credits already authorised, so
   * the effective cap is {@code min(platformCap, userCap)} when userCap is set, otherwise the
   * platform cap unchanged. Negative or zero userCap is treated as "not set" (defensive — the DTO
   * validation already enforces ≥ 0.10 but we don't trust transitive state).
   *
   * <p>Static + package-private so {@code RunOrchestratorClampHardCapTest} can pin the contract
   * without spinning up the full orchestrator.
   */
  static double clampHardCap(double platformCap, Double userCostCap) {
    if (userCostCap == null || userCostCap <= 0) return platformCap;
    return Math.min(platformCap, userCostCap);
  }

  private void writeRunCostLog(PipelineRun r, RunCostTracker.Snapshot cost) {
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
          "RunOrchestrator run={} failed to write RunCostLog: {}", r.getRunId(), e.getMessage());
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
      ExecutionResult exec, com.navneet.executespec.reviewer.ReviewerResult review) {
    if (exec.overall() == ExecutionResult.Overall.ABORTED) {
      return new StatusAndReason("FAILED", "EXECUTION_ABORTED");
    }
    com.navneet.executespec.reviewer.ReviewerResult.Overall verdict =
        review != null ? review.overall() : null;
    boolean buildPassed = review != null && Boolean.TRUE.equals(review.finalBuildPassed());
    boolean anyWarn = exec.anyIntermediateWarning();
    boolean anyTaskSucceeded = exec.tasksSuccessful() > 0;

    if (buildPassed
        && verdict != null
        && (verdict == com.navneet.executespec.reviewer.ReviewerResult.Overall.CLEAN
            || verdict == com.navneet.executespec.reviewer.ReviewerResult.Overall.FIXED_ISSUES)) {
      return new StatusAndReason(
          "COMPLETE", anyWarn ? "CLEAN_WITH_INTERMEDIATE_WARNINGS" : "CLEAN_COMPLETION");
    }
    if (review != null && !buildPassed) {
      return new StatusAndReason("PARTIAL", "FINAL_BUILD_FAILED");
    }
    if (verdict == com.navneet.executespec.reviewer.ReviewerResult.Overall.UNRESOLVED_ISSUES) {
      return new StatusAndReason("PARTIAL", "REVIEWER_UNRESOLVED");
    }
    if (verdict == com.navneet.executespec.reviewer.ReviewerResult.Overall.REVIEW_FAILED) {
      return new StatusAndReason("PARTIAL", "REVIEWER_FAILED");
    }
    if (!anyTaskSucceeded && exec.tasksFailed() > 0) {
      return new StatusAndReason("FAILED", "ALL_TASKS_FAILED");
    }
    // Defensive fallback — reached when the reviewer didn't run (review == null)
    // or produced a verdict outside the known set. 2026-05-13 audit — the legacy
    // "LEGACY_UNKNOWN" label was inherited from V108's backfill of pre-v2.5.10
    // rows and conveys nothing for NEW runs. Tag the reason with what we actually
    // know — the reviewer didn't deliver a usable verdict — so dashboards +
    // retry logic can branch on it instead of treating it as "we have no idea".
    return new StatusAndReason(mapOverallToV1Status(exec.overall()), "NO_REVIEW_RESULT");
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
        case FIXED_ISSUES -> {
          // 2026-05-19 — Phase 1 auto-fix surface. Emit an informational issue
          // with autoFixed=true so the Issues panel reflects that the reviewer
          // pass actually made edits (and they landed — build is green when
          // verdict is FIXED_ISSUES). Pre-2026-05-19 these flags were always
          // false even when the agent had done real work, so the UI made the
          // pass invisible. countUnfixedCritical filters SYNTH-FIX-* out of the
          // critical penalty (severity MINOR + autoFixed=true), so this is
          // visibility-only — doesn't double-count against the score.
          String summary = describeReviewerFixes(reviewResult);
          out.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-FIX-1")
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
                  .category(com.navneet.executespec.reviewer.model.IssueCategory.LOGIC_ERROR)
                  .description(summary)
                  .autoFixed(true)
                  .fixSucceeded(true)
                  .build());
        }
        default -> {
          /* CLEAN — no synthetic */
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

  // ──────────────────────────────────────────────────────────────────────────
  // RL-2026-05-037 — enterprise enforcement wiring (StandardsRunner + breaking-change classifier).
  // ──────────────────────────────────────────────────────────────────────────

  /** Cap the workspace file walk for standards enforcement (bounded cost on large repos). */
  private static final int STANDARDS_WALK_FILE_CAP = 6_000;

  /** Cap persisted standards-violation rows per run (avoid a runaway lint dump). */
  private static final int STANDARDS_MAX_VIOLATIONS_PERSIST = 200;

  /** Dirs never worth walking for standards (vendored / build output / VCS). */
  private static final java.util.Set<String> STANDARDS_SKIP_DIRS =
      java.util.Set.of(
          ".git",
          "node_modules",
          "target",
          "dist",
          "build",
          ".venv",
          "venv",
          "__pycache__",
          "vendor",
          ".gradle",
          ".idea",
          ".mvn",
          "out",
          "bin",
          "obj",
          ".next",
          "coverage");

  /**
   * Run deterministic coding standards (lint / file-pattern / AST) against the final workspace and
   * persist any violations to {@code standards_violation} so the {@code /standards/report} endpoint
   * reflects reality. Project-gated by the caller; non-fatal here. Zero overhead when the org has
   * no standards defined (resolver returns empty → early return).
   */
  private void runStandardsEnforcement(String runId, String projectId, Path workspaceRoot) {
    UUID runUuid;
    try {
      runUuid = UUID.fromString(runId);
    } catch (Exception e) {
      runUuid = null; // run_id is nullable on standards_violation
    }
    String orgId =
        projectRepository
            .findById(projectId)
            .map(com.navneet.executespec.auth.entity.Project::getOrgId)
            .orElse(null);
    if (orgId == null) return;
    var effective = effectiveStandardsResolver.resolve(orgId, projectId, null);
    if (effective == null || effective.isEmpty()) return; // common case → no-op
    java.util.List<String> repoFilePaths = new java.util.ArrayList<>();
    java.util.List<Path> sourceFiles = new java.util.ArrayList<>();
    collectWorkspaceFiles(workspaceRoot, repoFilePaths, sourceFiles);
    var report = standardsRunner.run(effective, workspaceRoot, repoFilePaths, sourceFiles);
    java.util.List<com.navneet.executespec.standards.StandardsViolation> rows =
        toStandardsViolationRows(runUuid, projectId, report, STANDARDS_MAX_VIOLATIONS_PERSIST);
    int persisted = 0;
    for (var row : rows) {
      try {
        standardsViolationRepository.save(row);
        persisted++;
      } catch (Exception e) {
        log.debug("RunOrchestrator run={} standards persist skipped: {}", runId, e.getMessage());
      }
    }
    log.info(
        "RunOrchestrator run={} standards: {} standard(s) resolved, {} violation(s) persisted",
        runId,
        effective.size(),
        persisted);
  }

  /** Pure mapping: StandardsRunner violations → persistable {@code standards_violation} rows. */
  static java.util.List<com.navneet.executespec.standards.StandardsViolation>
      toStandardsViolationRows(
          UUID runId,
          String projectId,
          com.navneet.executespec.standards.StandardsRunner.RunReport report,
          int cap) {
    java.util.List<com.navneet.executespec.standards.StandardsViolation> out =
        new java.util.ArrayList<>();
    if (report == null || report.violations() == null) return out;
    for (var v : report.violations()) {
      if (out.size() >= cap) break;
      out.add(
          com.navneet.executespec.standards.StandardsViolation.builder()
              .runId(runId)
              .projectId(projectId)
              .standardId(v.standardId())
              .enforcementType(v.enforcementType())
              .severity(v.severity() == null ? "ADVISORY" : v.severity().name())
              .violationSummary(truncate(v.message() + " @ " + v.location(), 1000))
              .build());
    }
    return out;
  }

  /**
   * Classify breaking changes to contracts the run touched. Previous content comes from the latest
   * {@link com.navneet.executespec.contracts.ContractVersion#getInlineContent()}; new content is
   * read from the workspace disk at the contract's detected path (per-repo subdir for multi-repo
   * runs). Returns report-only (MINOR) run issues so a breaking change is visible in the Issues
   * panel without triggering auto-fix or penalising the score. Non-fatal; empty on any miss.
   */
  private java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue>
      detectBreakingChangeIssues(
          String runId, String projectId, Path workspaceRoot, List<UUID> selectedRepoIds) {
    if (selectedRepoIds == null || selectedRepoIds.isEmpty()) return java.util.List.of();
    var repos = projectRepoService.resolveForRun(projectId, selectedRepoIds);
    if (repos == null || repos.isEmpty()) return java.util.List.of();
    boolean multiRepo = repos.size() > 1;
    java.util.List<com.navneet.executespec.contracts.gate.PlanBreakingChangeReport.IntendedChange>
        intended = new java.util.ArrayList<>();
    for (var repo : repos) {
      Path repoRoot =
          multiRepo
              ? workspaceRoot
                  .resolve("repos")
                  .resolve(lastPathSegment(repo.getGithubRepoFullName()))
              : workspaceRoot;
      for (var contract : contractRegistryRepository.findByRepoId(repo.getId())) {
        if (contract.getDetectedPath() == null || contract.getDetectedPath().isBlank()) continue;
        String prev =
            contractVersionRepository
                .findFirstByContractIdOrderByRegistryVersionDesc(contract.getId())
                .map(com.navneet.executespec.contracts.ContractVersion::getInlineContent)
                .orElse(null);
        if (prev == null) continue; // first-version or GCS-pointer contract: skip (best-effort)
        Path f = repoRoot.resolve(contract.getDetectedPath());
        if (!java.nio.file.Files.isRegularFile(f)) continue;
        String now;
        try {
          now = java.nio.file.Files.readString(f);
        } catch (Exception e) {
          continue;
        }
        if (now.equals(prev)) continue; // unchanged → not a candidate
        intended.add(
            new com.navneet.executespec.contracts.gate.PlanBreakingChangeReport.IntendedChange(
                contract.getId(), prev, now));
      }
    }
    if (intended.isEmpty()) return java.util.List.of();
    var report = planBreakingChangeReport.compose(projectId, intended);
    var issues = toBreakingChangeIssues(report);
    if (!issues.isEmpty()) {
      log.info(
          "RunOrchestrator run={} breaking-change: {} contract(s) flagged (typedAck={})",
          runId,
          issues.size(),
          report.requiresTypedAck());
    }
    return issues;
  }

  /** Pure mapping: breaking-change report rows → report-only (MINOR) run issues. */
  static java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> toBreakingChangeIssues(
      com.navneet.executespec.contracts.gate.PlanBreakingChangeReport.Report report) {
    java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> out =
        new java.util.ArrayList<>();
    if (report == null || report.breakingChanges() == null) return out;
    int n = 0;
    for (var row : report.breakingChanges()) {
      n++;
      String consumers =
          row.affectedConsumers() == null || row.affectedConsumers().isEmpty()
              ? "no declared consumers"
              : "consumers: " + String.join(", ", row.affectedConsumers());
      out.add(
          com.navneet.executespec.reviewer.model.ReviewIssue.builder()
              .id("SYNTH-CONTRACT-" + n)
              .severity(com.navneet.executespec.reviewer.model.IssueSeverity.MINOR)
              .category(com.navneet.executespec.reviewer.model.IssueCategory.CROSS_TASK_MISMATCH)
              .description(
                  "Breaking change to contract '"
                      + row.contractName()
                      + "' ("
                      + row.type()
                      + "): "
                      + truncate(row.summary(), 160)
                      + " — "
                      + consumers
                      + (row.requiresFriction() ? " [REQUIRES ACK]" : ""))
              .autoFixed(false)
              .fixSucceeded(false)
              .build());
    }
    return out;
  }

  /** Bounded, pruned workspace walk for standards enforcement. */
  private void collectWorkspaceFiles(
      Path root, java.util.List<String> relPaths, java.util.List<Path> sourceFiles) {
    if (root == null || !java.nio.file.Files.isDirectory(root)) return;
    try {
      java.nio.file.Files.walkFileTree(
          root,
          new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (!dir.equals(root) && STANDARDS_SKIP_DIRS.contains(dir.getFileName().toString())) {
                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
              if (relPaths.size() >= STANDARDS_WALK_FILE_CAP) {
                return java.nio.file.FileVisitResult.TERMINATE;
              }
              relPaths.add(root.relativize(file).toString());
              sourceFiles.add(file);
              return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException e) {
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
    } catch (Exception e) {
      log.debug("collectWorkspaceFiles walk failed: {}", e.getMessage());
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }

  // RL-2026-05-035 — "consumption is the liability" fix. The codemap/architecture pipeline produces
  // a per-repo symbol map but it was never fed to the agents that write code (planner / worker /
  // PATCH). This builds a HARD-BOUNDED code-intel block (per-repo codemap symbol maps) to inject so
  // localization uses the generated map instead of re-discovering structure from scratch. Best-
  // effort + language-neutral; flag-gated by callers (`codeintel.inject.enabled`).
  private static final int CODEINTEL_TOTAL_CAP = 12_000;
  private static final int CODEINTEL_PER_REPO_CAP = 6_000;

  /**
   * Per-block cap for enterprise-knowledge planner injection (contracts + ADRs, flag {@code
   * enterprise.inject.enabled}). Each block is independently truncated so a large registry can't
   * blow the planner cold-start budget.
   */
  private static final int ENTERPRISE_INJECT_CAP = 8_000;

  private String buildCodeIntelContext(
      String projectId, java.util.List<com.navneet.executespec.project.entity.ProjectRepo> repos) {
    if (projectId == null || repos == null || repos.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    int budget = CODEINTEL_TOTAL_CAP;
    try {
      for (com.navneet.executespec.project.entity.ProjectRepo r : repos) {
        if (budget <= 0) break;
        var cm = projectArchitectureService.latestCodeMap(projectId, r.getId());
        if (cm.isEmpty() || cm.get().getContent() == null || cm.get().getContent().isBlank()) {
          continue;
        }
        String body = truncate(cm.get().getContent(), Math.min(budget, CODEINTEL_PER_REPO_CAP));
        sb.append("### Codemap — ")
            .append(lastPathSegment(r.getGithubRepoFullName()))
            .append("\n")
            .append(body)
            .append("\n\n");
        budget -= body.length();
      }
    } catch (Exception e) {
      log.warn("buildCodeIntelContext failed (continuing without code-intel): {}", e.getMessage());
      return "";
    }
    String out = sb.toString().trim();
    if (out.isBlank()) return "";
    return "EXISTING CODEBASE MAP — use this to LOCATE the right files/symbols before editing;"
        + " do NOT re-discover the structure from scratch:\n\n"
        + out;
  }

  /**
   * Build a short user-facing description of what the reviewer auto-fixed during this run. Prefers
   * the reviewer's own pass-summary text (the model's completeTask() output) so the description
   * reads like a human-written changelog line. Falls back to a generic message if both passes were
   * silent.
   */
  private static String describeReviewerFixes(ReviewerResult result) {
    String general = result.generalPassSummary();
    String wiring = result.wiringPassSummary();
    StringBuilder sb = new StringBuilder("Reviewer auto-fixed issues during the review pass.");
    if (general != null && !general.isBlank()) {
      sb.append(" General pass: ").append(truncate(general.trim(), 160));
    }
    if (wiring != null && !wiring.isBlank()) {
      sb.append(" Wiring pass: ").append(truncate(wiring.trim(), 160));
    }
    return truncate(sb.toString(), 480);
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

  /**
   * Run-level worker model default — used when {@code RunRequest.modelName} is blank.
   *
   * <p>2026-05-06 single-source consolidation: routes through {@link
   * ModelRegistry#workhouseModelFor(String)} so the YAML matrix is the only source of truth.
   * Previously a hardcoded switch returned Haiku/flash/mini even after YAML moved Anthropic
   * WORKHORSE to Sonnet (2026-04-19) and Gemini WORKHORSE to Pro (2026-05-04).
   *
   * <p>Final-fallback safety net: if YAML lookup returns null (e.g. provider mis-configured), fall
   * back to the historical Anthropic-Haiku default rather than NPE'ing the run.
   */
  private String defaultModelFor(String provider) {
    String model = modelRegistry.workhouseModelFor(provider);
    if (model != null && !model.isBlank()) return model;
    log.warn(
        "RunOrchestrator: no workhorse model in YAML matrix for provider '{}'; falling back to"
            + " claude-haiku-4-5-20251001",
        provider);
    return "claude-haiku-4-5-20251001";
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
   * <p>The block instructs the planner to TRANSLATE filename / extension conventions in the spec to
   * match the repo's actual language. Without this block, planners templated TypeScript filenames
   * into Python repos because the user's spec wording happened to use {@code .ts} extensions (run
   * a076c876 / T2). The planner now sees an explicit rule and either translates up-front or rejects
   * the mismatch.
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
      lines.append("- `repos/").append(name).append("/` is **").append(language);
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
   * Conservative: when the language doesn't match any known mapping, return a generic "match the
   * existing repo conventions" line so the planner at least sees the language name in the parity
   * block.
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
      case "ruby" -> "use snake_case `.rb` filenames, `ENV[]` for config, RSpec for tests.";
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
   * SINGLE_SHOT/LARGE/LEGACY to {@link PlannerAgent}. Returns null when the classifier failed
   * outright — the caller should treat that as the legacy path (PlannerAgent handles every
   * classification).
   *
   * <p>Cost note: the classifier's LLM call (when it fires) flows through {@link LlmRouter} →
   * {@link RunCostTracker#record}, so it auto-aggregates into the run's grand totals. We diff the
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

      RunCostTracker.Snapshot before = costTracker.snapshot(runId);
      ClassificationResult result =
          smartClassifier.classify(
              userRequest, repoNames, projectId, projectType, providerEnum, projectMemorySnippet);
      RunCostTracker.Snapshot after = costTracker.snapshot(runId);

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
  private Plan runOutlinePhase(String runId, PlannerRequest req, OutlinePlannerAgent.Mode mode) {
    RunCostTracker.Snapshot before = costTracker.snapshot(runId);
    try {
      Plan plan = outlinePlanner.plan(req, mode);
      RunCostTracker.Snapshot after = costTracker.snapshot(runId);

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
   * <p>Per-phase cost is diff'd from {@link RunCostTracker#snapshot} and persisted to the matching
   * {@code pipeline_run.{outline,expansion,coherence}_*} columns. Run total auto-aggregates via the
   * tracker's accumulator.
   */
  private Plan runLargePipeline(
      String runId, PlannerRequest req, ExecutionContext rootCtx, boolean enableEpicRecursion) {
    // ── Phase 1: skeleton outline ─────────────────────────────────────
    Plan skeleton = runOutlinePhase(runId, req, OutlinePlannerAgent.Mode.SKELETON_ONLY);
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
    RunCostTracker.Snapshot beforeExpand = costTracker.snapshot(runId);
    long expansionStart = System.currentTimeMillis();
    List<PlannedTask> tasksToExpand = skeleton.tasks();
    // Per-skeleton-id → list of resulting LEAF tasks (1 for LEAF, N for
    // recursively-expanded EPIC).
    java.util.concurrent.ConcurrentMap<String, List<PlannedTask>> expanded =
        new java.util.concurrent.ConcurrentHashMap<>();
    java.util.concurrent.ConcurrentMap<String, Throwable> expansionFailures =
        new java.util.concurrent.ConcurrentHashMap<>();

    List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
    for (PlannedTask t : tasksToExpand) {
      futures.add(
          java.util.concurrent.CompletableFuture.runAsync(
              () -> {
                try {
                  if (enableEpicRecursion && t.taskType() == PlannedTask.TaskType.EPIC) {
                    List<PlannedTask> children = expandEpic(runId, t, skeleton, req, rootCtx);
                    expanded.put(t.id(), children);
                  } else {
                    ExecutionContext taskCtx =
                        new ExecutionContext(runId, "_expand_" + t.id(), rootCtx.workspaceRoot());
                    PlannedTask full = taskExpander.expand(t, skeleton, req, taskCtx);
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
                          PlannedTask.builder()
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
                              .taskType(PlannedTask.TaskType.LEAF)
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
    List<PlannedTask> assembledTasks = new ArrayList<>(tasksToExpand.size());
    for (PlannedTask sk : tasksToExpand) {
      List<PlannedTask> result = expanded.get(sk.id());
      if (result == null || result.isEmpty()) {
        assembledTasks.add(sk);
      } else {
        assembledTasks.addAll(result);
      }
    }
    Plan assembled =
        Plan.builder()
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

    RunCostTracker.Snapshot afterExpand = costTracker.snapshot(runId);
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
    RunCostTracker.Snapshot beforeCoherence = costTracker.snapshot(runId);
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
    RunCostTracker.Snapshot afterCoherence = costTracker.snapshot(runId);
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
  private List<PlannedTask> expandEpic(
      String runId,
      PlannedTask epic,
      Plan parentPlan,
      PlannerRequest parentReq,
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

    PlannerRequest subReq =
        PlannerRequest.builder()
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
            .contracts(parentReq.contracts())
            .projectAdrs(parentReq.projectAdrs())
            .tddMode(parentReq.tddMode())
            .build();

    Plan subSkeleton;
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

    List<PlannedTask> children = subSkeleton.tasks();
    if (children == null || children.isEmpty()) {
      log.warn(
          "V2 run={} sub-outline for EPIC {} returned 0 children — coercing to single LEAF",
          runId,
          epic.id());
      return List.of(coerceToLeafFallback(epic));
    }

    // Re-id with parent prefix and force LEAF (depth cap).
    List<PlannedTask> reIdChildren = new ArrayList<>(children.size());
    for (int i = 0; i < children.size(); i++) {
      PlannedTask c = children.get(i);
      String childId = epic.id() + "." + (i + 1);
      reIdChildren.add(
          PlannedTask.builder()
              .id(childId)
              .title(c.title())
              .description(c.description())
              .complexity(c.complexity() == null ? PlannedTask.Complexity.MEDIUM : c.complexity())
              .touchesFiles(c.touchesFiles())
              .dependsOn(c.dependsOn())
              .hardConstraints(c.hardConstraints())
              .meetsRequirements(c.meetsRequirements())
              .taskType(PlannedTask.TaskType.LEAF) // depth cap: no further recursion
              .build());
    }

    // Build a sub-plan view for the expander prompt so siblings cross-reference correctly.
    Plan subPlanForContext =
        Plan.builder()
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
    java.util.concurrent.ConcurrentMap<String, PlannedTask> bodied =
        new java.util.concurrent.ConcurrentHashMap<>();
    List<java.util.concurrent.CompletableFuture<Void>> subFutures = new ArrayList<>();
    for (PlannedTask child : reIdChildren) {
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
                      PlannedTask.builder()
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
                          .taskType(PlannedTask.TaskType.LEAF)
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
    List<PlannedTask> result = new ArrayList<>(reIdChildren.size());
    for (PlannedTask c : reIdChildren) {
      result.add(bodied.getOrDefault(c.id(), c));
    }
    log.info("V2 run={} expandEpic {} → {} LEAF children", runId, epic.id(), result.size());
    return result;
  }

  /**
   * Fallback when sub-outline fails: collapse the EPIC into a single LEAF carrying the title +
   * description so the worker still sees the work.
   */
  private static PlannedTask coerceToLeafFallback(PlannedTask epic) {
    return PlannedTask.builder()
        .id(epic.id())
        .title(epic.title())
        .description(
            epic.description() == null || epic.description().isBlank()
                ? "Implement: " + epic.title() + " (sub-outline failed; treat as a single task)"
                : epic.description())
        .complexity(epic.complexity() == null ? PlannedTask.Complexity.HIGH : epic.complexity())
        .touchesFiles(epic.touchesFiles())
        .dependsOn(epic.dependsOn())
        .hardConstraints(epic.hardConstraints())
        .meetsRequirements(epic.meetsRequirements())
        .taskType(PlannedTask.TaskType.LEAF)
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
