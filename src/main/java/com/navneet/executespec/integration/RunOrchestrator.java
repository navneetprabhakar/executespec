package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.computeRepoCountForEstimate;
import static com.navneet.executespec.integration.RunPipelineSupport.deserializeRepoIds;
import static com.navneet.executespec.integration.RunPipelineSupport.isTerminal;
import static com.navneet.executespec.integration.RunPipelineSupport.lastPathSegment;
import static com.navneet.executespec.integration.RunPipelineSupport.mapOverallToV1Status;
import static com.navneet.executespec.integration.RunPipelineSupport.serializeRepoIds;
import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlanStrategyCircuitBreaker;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.SpecClassifier;
import com.navneet.executespec.assessment.FeatureAssessmentService;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.codeintel.RunCodeMapService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.CostHistoryService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.CodeGenEngine;
import com.navneet.executespec.engine.EngineRequest;
import com.navneet.executespec.engine.EngineResult;
import com.navneet.executespec.engine.VerifyMode;
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.reviewer.PostEditFormatter;
import com.navneet.executespec.reviewer.ReviewerAgent;
import com.navneet.executespec.reviewer.ReviewerRequest;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.router.FireworksModelMapper;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.router.OpenRouterModelMapper;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProviderAvailabilityMap;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.run.RunContinuationBenchmarkPriorFlag;
import com.navneet.executespec.run.RunContinuationConfirmationFlag;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.TestGenOrchestrator;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.beans.factory.annotation.Value;
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
public class RunOrchestrator implements RunControlPort {

  /**
   * Back-compat classification for {@link PlannerRequest}. Live planning strategy flows through
   * {@link com.navneet.executespec.agent.SmartClassifierAgent} and {@link PlanStrategy}; this enum
   * remains only for legacy validation/task-cap paths that still read {@code
   * PlannerRequest.classification}.
   */
  private static final Classification DEFAULT_CLASSIFICATION = Classification.MEDIUM;

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;
  private final OutlinePlannerAgent outlinePlanner;
  private final PlanStrategyCircuitBreaker planStrategyBreaker;
  private final SequentialExecutor executor;
  private final GoalContinuationAdvisor goalContinuationAdvisor;
  private final ReviewerAgent reviewer;
  private final PostEditFormatter postEditFormatter;

  /** Single-task code-gen engine used by PATCH-mode runs. */
  private final CodeGenEngine codeGenEngine;

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
  private final RunPersistenceWriter persistenceWriter;
  private final RunContextComposer contextComposer;
  private final RunPlanningCoordinator planningCoordinator;
  private final RunStandardsEnforcer standardsEnforcer;
  private final RunValidationKickoff validationKickoff;
  private final RunResourceCleaner resourceCleaner;
  private final TestGenOrchestrator testGenOrchestrator;
  private final PerkyMessenger perky;
  private final GateStore gateStore;
  private final ModelResolver modelResolver;
  private final AvailableProviderResolver availableProviderResolver;
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
  private final com.navneet.executespec.contracts.gate.PlanBreakingChangeReport
      planBreakingChangeReport;

  private final com.navneet.executespec.contracts.ContractRegistryRepository
      contractRegistryRepository;

  private final com.navneet.executespec.contracts.ContractVersionRepository
      contractVersionRepository;

  /** Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode. */
  private final com.navneet.executespec.run.RunSkipPushFlag runSkipPushFlag;

  /**
   * P3 local-hands (RL-2026-06-014) — per-run LOCAL marker + the remote-tool transport it
   * activates.
   */
  private final com.navneet.executespec.run.RunLocalExecutionFlag runLocalExecutionFlag;

  /** Per-run marker for confirmation-required automatic continuation spend. */
  private final RunContinuationConfirmationFlag runContinuationConfirmationFlag;

  /** Per-run marker for benchmark-prior automatic continuation policy. */
  private final RunContinuationBenchmarkPriorFlag runContinuationBenchmarkPriorFlag;

  private final com.navneet.executespec.tool.remote.RemoteToolDispatcher remoteToolDispatcher;

  /** Wave 2.1 — activation funnel event tracker (no-op when PostHog is unconfigured). */
  private final com.navneet.executespec.analytics.EventTrackingService eventTracking;

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

  /** runId → in-flight pipeline future; the cancel/pause/halt interrupt handle (invariant #4). */
  private final ConcurrentMap<String, Future<?>> activeRuns = new ConcurrentHashMap<>();

  @Value("${execution.continuation.post-review.enabled:true}")
  private boolean postReviewContinuationEnabled = true;

  @Value("${execution.continuation.required-quality-threshold:70.0}")
  private double postReviewRequiredQualityThreshold = 70.0;

  @Value("${execution.continuation.max-provider-switches-per-run:1}")
  private int postReviewMaxProviderSwitches = 1;

  @Value("${execution.continuation.max-model-step-ups-per-task:1}")
  private int postReviewMaxModelStepUps = 1;

  @Value("${execution.continuation.max-model-step-downs-per-task:1}")
  private int postReviewMaxModelStepDowns = 1;

  @Value("${execution.continuation.require-benchmark-prior-for-automatic-continuation:false}")
  private boolean postReviewRequireBenchmarkPriorForAutomaticContinuation = false;

  // --- RunControlPort: the narrow seam RunLifecycleService reaches the orchestrator through.
  // activeRuns ownership stays here (the submit + runPipeline path register/unregister through the
  // same map); the lifecycle service register/removes via these accessors.

  @Override
  public void putActiveRun(String runId, Future<?> future) {
    activeRuns.put(runId, future);
  }

  @Override
  public Future<?> removeActiveRun(String runId) {
    return activeRuns.remove(runId);
  }

  /**
   * Run-orchestration thread pool — extracted to {@link PipelineExecutorPool} (god-class split
   * inc-4b). Injected so the LARGE-pipeline fan-out + the lifecycle resubmits (resume / retry /
   * re-review) share one bean; the sizing, daemon {@code v2-orchestrator-N} threads, and the 30s
   * graceful {@code @PreDestroy} shutdown live there now.
   */
  private final PipelineExecutorPool pool;

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
   * BUILD-mode submit overload with per-run continuation options. Used by REST submit so those
   * options are marked before the async pipeline can consume them.
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
      Boolean requireConfirmationBeforeExpensiveContinuation,
      Boolean requireBenchmarkPriorForAutomaticContinuation,
      String lowComplexityWorkerStartTier) {
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
        com.navneet.executespec.state.RunMode.BUILD,
        requireConfirmationBeforeExpensiveContinuation,
        requireBenchmarkPriorForAutomaticContinuation,
        lowComplexityWorkerStartTier);
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
        runMode,
        null,
        null,
        null);
  }

  /**
   * Full submit signature with continuation policy options that must be visible before the async
   * pipeline thread can consume per-run markers.
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
      String runMode,
      Boolean requireConfirmationBeforeExpensiveContinuation,
      Boolean requireBenchmarkPriorForAutomaticContinuation) {
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
        runMode,
        requireConfirmationBeforeExpensiveContinuation,
        requireBenchmarkPriorForAutomaticContinuation,
        null);
  }

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
      String runMode,
      Boolean requireConfirmationBeforeExpensiveContinuation,
      Boolean requireBenchmarkPriorForAutomaticContinuation,
      String lowComplexityWorkerStartTier) {
    String runId = UUID.randomUUID().toString();
    String provider = contextComposer.normalizeProvider(aiProvider, projectId, runId);

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
    String model =
        (hasPin && enterpriseSubmit) ? modelName.trim() : contextComposer.defaultModelFor(provider);

    // 2026-05-06 — defaultModelFor was a hardcoded provider→model fallback that drifted from
    // the YAML matrix (returned Haiku/flash/mini when YAML PLANNING wanted Sonnet/Pro/gpt-5.4).
    // After the consolidation it routes through ModelRegistry.workhouseModelFor(provider) so
    // the run-level worker default tracks YAML.

    // Resolve per-tier models so RunDetail surfaces what the planner/reviewer
    // will actually call, not the run-level worker default — adversarial-test
    // Finding 14. An ENTERPRISE per-run pin is honoured for all three roles to
    // preserve the override contract.
    //
    // V182 PATCH-mode override: a patch run never invokes the planner or reviewer, but it *must*
    // run on PLANNING-tier (Sonnet 4.6 / GPT-5.4 / Gemini 3 Pro Preview) regardless of any
    // caller-supplied modelName. CodeGenEngine receives `plannerDbModel` as the worker model; the
    // reasoning gap between Haiku-class and Sonnet-class is bigger than any harness improvement on
    // bug-fix shapes.
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

    applyContinuationSubmitOptions(
        runId,
        requireConfirmationBeforeExpensiveContinuation,
        requireBenchmarkPriorForAutomaticContinuation,
        runContinuationConfirmationFlag,
        runContinuationBenchmarkPriorFlag);

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
    final String lowComplexityWorkerStartTierFinal =
        normalizeLowComplexityWorkerStartTier(lowComplexityWorkerStartTier);
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
                    pinnedModelFinal,
                    lowComplexityWorkerStartTierFinal);
              } finally {
                activeRuns.remove(runIdFinal);
              }
            });
    activeRuns.put(runIdFinal, future);
    return run;
  }

  static void applyContinuationSubmitOptions(
      String runId,
      Boolean requireConfirmationBeforeExpensiveContinuation,
      Boolean requireBenchmarkPriorForAutomaticContinuation,
      RunContinuationConfirmationFlag confirmationFlag,
      RunContinuationBenchmarkPriorFlag benchmarkPriorFlag) {
    if (Boolean.TRUE.equals(requireConfirmationBeforeExpensiveContinuation)) {
      confirmationFlag.mark(runId);
    }
    if (requireBenchmarkPriorForAutomaticContinuation != null) {
      benchmarkPriorFlag.mark(runId, requireBenchmarkPriorForAutomaticContinuation);
    }
  }

  static String normalizeLowComplexityWorkerStartTier(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    return "LIGHTWEIGHT".equals(normalized) || "WORKHORSE".equals(normalized) ? normalized : null;
  }

  // pipeline (background thread)

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
        null,
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
      String pinnedModel,
      String lowComplexityWorkerStartTierOverride) {
    Instant startedAt = Instant.now();
    Path workspace = null;
    String upperProvider = provider == null ? "UNKNOWN" : provider.toUpperCase();
    // P3 local-hands (RL-2026-06-014) — declared before the try so the finally can unregister the
    // remote-tool session on every terminal/cancel path. Assigned once below (after the workspace
    // exists); stays false unless worker.local-hands.enabled AND the submit requested LOCAL
    // execution.
    boolean localRun = false;
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
      boolean routePinned = false;
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
            String canonical = modelRegistry.canonicalize(pinnedModel.trim());
            String owner = modelRegistry.providerFor(canonical);
            if (owner == null || canRoutePinnedModel(pinProvider, canonical, owner)) {
              validatedPin = canonical;
            } else {
              log.warn(
                  "V2 run={} per-run model pin '{}' cannot be routed via {} — ignoring pin",
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
          routePinned = true;
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

      // P3 local-hands (RL-2026-06-014): choose LOCAL vs remote execution once, here. When on, the
      // worker's tool calls are proxied to the caller's machine (clone / smoke / reviewer / push
      // are
      // all skipped below). Flag OFF ⇒ false ⇒ every branch below is inert and prod is
      // byte-identical.
      localRun =
          featureFlagService.isEnabled("worker.local-hands.enabled")
              && runLocalExecutionFlag.consume(runId);
      boolean requireContinuationConfirmation = runContinuationConfirmationFlag.consume(runId);
      boolean requireContinuationBenchmarkPrior =
          runContinuationBenchmarkPriorFlag
              .consume(runId)
              .orElse(postReviewRequireBenchmarkPriorForAutomaticContinuation);

      // Pilot v3.0 — clone any project-linked repos the user selected for this run
      // into <workspace>/<runId>/repos/<name>/ subdirectories. Non-fatal: if a clone
      // fails we log and continue so the run can at least try to plan against what
      // was fetched. (Skipped entirely for LOCAL runs — the working tree is the client's.)
      if (!localRun && projectId != null && selectedRepoIds != null && !selectedRepoIds.isEmpty()) {
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
      persistenceWriter.setWorkspacePath(runId, workspace.toString());

      // Bench services (swebench / programbench) submit runs with
      // RunMode.PATCH and own the workspace setup themselves (docker cp +
      // bind-mount). Skip everything build-mode does — codemap, assessment,
      // classifier, planner, executor, reviewer, validation report — and dispatch the whole user
      // request to a single CodeGenEngine loop. Product entry points stay on RunMode.BUILD by
      // default and fall through to the unchanged build-mode flow below.
      {
        PipelineRun modeProbe = runRepo.findById(runId).orElse(null);
        if (modeProbe != null
            && com.navneet.executespec.state.RunMode.isPatch(modeProbe.getRunMode())) {
          runPatchPipeline(runId, provider, modeProbe, userRequest, rootCtx, startedAt);
          return;
        }
      }

      // P3 local-hands: for a LOCAL build-mode run, register the remote-tool session so the
      // worker's
      // existing dispatch (ToolRegistry.isRemoteRun ← RemoteToolDispatcher) proxies every tool call
      // to
      // the caller's machine. Mirrors TerminalBenchRunService; unregistered in the runPipeline
      // finally.
      if (localRun) {
        remoteToolDispatcher.register(runId);
        log.info(
            "RunOrchestrator run={} LOCAL-hands mode: tool calls proxied to client;"
                + " clone/smoke/push skipped",
            runId);
      }

      // 0b. Pilot v3.0 follow-up: per-run codemap for paid existing-repo
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

      // 0c. Pilot R4: feature assessment
      // Runs only when a project context is available so the assessment can
      // reason about linked repos + architecture annotations. Result is
      // persisted, broadcast over SSE, and available to the planner via
      // the run row. Heuristic for now (no extra LLM cost).
      if (projectId != null) {
        try {
          String assessmentJson =
              featureAssessmentService.assessAsJson(runId, projectId, userRequest);
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

      perky.start(runId, "PLANNING");
      Plan plan;
      try {
        Classification autoClassification = SpecClassifier.classify(userRequest);
        log.info(
            "V2 run={} spec classified as {} (len={})",
            runId,
            autoClassification,
            userRequest == null ? 0 : userRequest.length());

        // Smart classifier verdict drives downstream routing: SMALL/MEDIUM use the outline planner
        // in full-plan mode; LARGE/LEGACY use skeleton expansion; SINGLE_SHOT is synthesized
        // locally. Cost flows through RunCostTracker so it auto-aggregates into the run total.
        PlanStrategy planStrategy =
            planningCoordinator.runClassifierShadow(
                runId, runRow, userRequest, projectId, selectedRepoIds, provider);
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
        String multiRepoSummary = contextComposer.buildMultiRepoSummary(projectId, selectedRepoIds);
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
                contextComposer.buildCodeIntelContext(
                    projectId,
                    projectRepoService.resolveForRun(projectId, selectedRepoIds),
                    userRequest,
                    featureFlagService.isEnabled("codeintel.tiered.enabled"));
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
        // Architecture-doc injection — feed the project's high-level design (cross-repo HLD/LLD)
        // into the planner alongside the codemap. Flag-gated (`codeintel.inject-arch.enabled`,
        // default OFF), bounded (~8k chars), best-effort; no project / no arch doc → no-op
        // (greenfield + blind-bench fail-safe). Mirrors the codemap-injection pattern above.
        if (projectId != null && featureFlagService.isEnabled("codeintel.inject-arch.enabled")) {
          try {
            String arch = contextComposer.buildArchitectureContext(projectId);
            if (!arch.isBlank()) {
              summary = summary + "\n\n" + arch;
              log.info(
                  "V2 run={} injected architecture into planner ({} chars)", runId, arch.length());
            }
          } catch (Exception e) {
            log.warn("V2 run={} architecture planner injection skipped: {}", runId, e.getMessage());
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
        //   SINGLE_SHOT / null / classifier failure → synthetic one-task plan.
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
            plan =
                planningCoordinator.runOutlinePhase(
                    runId, plannerReq, OutlinePlannerAgent.Mode.FULL_PLAN);
            planStrategyBreaker.recordSuccess(effective);
          } catch (Exception outlineEx) {
            planStrategyBreaker.recordFailure(effective);
            log.warn(
                "V2 run={} outline planner ({}) failed → SINGLE_SHOT fallback", runId, effective);
            plan = outlinePlanner.synthesizeSingleShot(plannerReq);
          }
        } else if (effective == PlanStrategy.LARGE) {
          try {
            plan =
                planningCoordinator.runLargePipeline(runId, plannerReq, rootCtx, legacyRecursion);
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
          // spec verbatim.
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
        terminate(runId, "FAILED", INTERNAL_ERROR_REASON, startedAt);
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
      persistenceWriter.persistPlan(runId, plan);
      persistenceWriter.persistTasks(runId, plan);
      persistenceWriter.persistRunTitle(runId, plan);
      contextComposer.writeAgentContext(workspace, plan);
      persistEstimate(runId, provider, model, plan);
      String designBlock = contextComposer.buildDesignBlock(userRequest, plan);
      // Phase 1: attribute tokens used so far to the planner.
      RunCostTracker.Snapshot afterPlan = costTracker.snapshot(runId);
      tokenAttributor.attributePhase(
          runId,
          com.navneet.executespec.cost.RunTokenAttributor.Phase.PLANNER,
          afterPlan.inputTokens(),
          afterPlan.outputTokens(),
          upperProvider,
          model);

      // 1b. Plan approval gate (PRODUCTION only)
      // PROTOTYPE runs + integration-triggered runs (skipApproval=true) bypass
      // the gate so they flow straight into execution. PRODUCTION runs block
      // on GateStore.awaitPlanGate until the user approves or rejects via
      // /api/runs/{id}/approve.
      boolean needsApproval = !skipApproval && "PRODUCTION".equalsIgnoreCase(executionMode);
      if (needsApproval) {
        perky.stop(runId);
        persistenceWriter.updateStatus(runId, "AWAITING_APPROVAL");
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

      persistenceWriter.updateStatus(runId, "WORKING");
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
                    .enableSmokeTests(!localRun)
                    .localHandsMode(localRun)
                    .requireConfirmationBeforeExpensiveContinuation(requireContinuationConfirmation)
                    .requireBenchmarkPriorForAutomaticContinuation(requireContinuationBenchmarkPrior)
                    .routePinned(routePinned)
                    .lowComplexityWorkerStartTier(lowComplexityWorkerStartTierOverride)
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
                    .workspaceSummary(
                        contextComposer.buildMultiRepoSummary(projectId, selectedRepoIds))
                    .build());
      } catch (Exception e) {
        log.error("RunOrchestrator run={} executor failed", runId, e);
        terminate(runId, "FAILED", INTERNAL_ERROR_REASON, startedAt);
        return;
      }
      persistenceWriter.applyExecutionResultToTasks(runId, execResult);
      // Phase 2 delta: tokens spent by the executor (all worker tasks).
      RunCostTracker.Snapshot afterExec = costTracker.snapshot(runId);
      int workerIn = afterExec.inputTokens() - afterPlan.inputTokens();
      int workerOut = afterExec.outputTokens() - afterPlan.outputTokens();
      tokenAttributor.attributeWorker(runId, execResult, workerIn, workerOut, upperProvider, model);

      // 2.5 Code-gen-only stop (benchmark)
      // RL-2026-05-050 — the SWE-bench harness wants the REAL classifier→planner→worker decisioning
      // (the system picks its own strategy) but a CLEAN minimal diff for scoring. Everything after
      // the worker pollutes that diff, so a code-gen-only run STOPS HERE — right after the worker,
      // BEFORE the lint auto-fix below + the reviewer + validation + push. This MUST precede 2.6:
      // the lint pass runs `black` over the WHOLE workspace (observed reformatting 225–490 files →
      // a 13 MB git diff that buried the actual fix and made the patch unscoreable). Tightly gated
      // on the benchmark source, so every production run is byte-identical (never taken for a real
      // customer run).
      if (isCodeGenOnlyRun(runId)) {
        log.info(
            "RunOrchestrator run={} CODEGEN_ONLY — stopping after worker (skip"
                + " lint/reviewer/validation/push)",
            runId);
        broadcaster.emit(
            runId,
            RunEvent.log(
                runId, null, "Code generation complete — code-gen-only mode (reviewer skipped)"));
        terminate(
            runId, "COMPLETE", "CODEGEN_ONLY", "code-gen complete; reviewer skipped", startedAt);
        return;
      }

      // P3 local-hands (RL-2026-06-014) — LOCAL runs STOP HERE too, right after the worker (like
      // the
      // code-gen-only stop above). The worker's edits/tests already ran on the CALLER's machine
      // (tool
      // calls were proxied); everything below — lint, reviewer, standards, validation, commit,
      // auto-push — operates on the empty SERVER workspace and would be meaningless. Nothing is
      // published; the diff lives in the client's working tree. Gated OFF by default ⇒ never taken
      // for
      // a normal run.
      if (localRun) {
        broadcaster.emit(
            runId,
            RunEvent.log(
                runId, null, "Local-hands run complete — changes are in your working tree"));
        terminate(
            runId,
            "COMPLETE",
            "LOCAL_EXECUTION",
            "local-hands run; diff in the client working tree (server-side steps skipped)",
            startedAt);
        return;
      }

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

      persistenceWriter.updateStatus(runId, "REVIEWING");
      perky.start(runId, "REVIEWING");
      ReviewerResult reviewResult = null;
      boolean reviewerErrored = false;
      // 2026-05-08 — programbench runs need more reviewer iters to bisect library link
      // errors. Default cap is 70 (per ReviewerAgent.DEFAULT_MAX_ITER_PER_PASS); cmatrix
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
        ProviderModel reviewerRoute = reviewerRouteFor(provider, model, execResult);
        if (!sameRoute(provider, model, reviewerRoute)) {
          log.info(
              "RunOrchestrator run={} reviewer route promoted after continuation action:"
                  + " {}/{} -> {}/{}",
              runId,
              provider,
              model,
              reviewerRoute.provider(),
              reviewerRoute.model());
        }
        reviewResult =
            reviewer.review(
                ReviewerRequest.builder()
                    .provider(reviewerRoute.provider())
                    .model(reviewerRoute.model())
                    .context(rootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(skipWiring)
                    .maxIterationsPerPass(reviewerMaxIter)
                    .build());
        persistenceWriter.persistReview(runId, reviewResult);
        // Phase 3 delta: tokens spent by reviewer (general + wiring passes).
        RunCostTracker.Snapshot afterReview = costTracker.snapshot(runId);
        int reviewerIn = afterReview.inputTokens() - afterExec.inputTokens();
        int reviewerOut = afterReview.outputTokens() - afterExec.outputTokens();
        tokenAttributor.attributePhase(
            runId,
            com.navneet.executespec.cost.RunTokenAttributor.Phase.REVIEWER,
            reviewerIn,
            reviewerOut,
            reviewerRoute.provider().toUpperCase(Locale.ROOT),
            reviewerRoute.model());
      } catch (Exception e) {
        log.warn("RunOrchestrator run={} reviewer failed — recording partial review", runId, e);
        reviewerErrored = true;
        reviewResult = reviewerFailedResult(e);
        // Review failure is non-fatal; the execution already succeeded or partially
        // succeeded. Record the failure in reviewJson so the UI can surface it.
        persistenceWriter.persistReviewFailure(runId, e);
      }
      RunCostTracker.Snapshot afterInitialReview = costTracker.snapshot(runId);
      Optional<PostReviewContinuation> postReviewContinuation =
          maybeEmitPostReviewContinuationDecision(
              runId,
              execResult,
              reviewResult,
              reviewerErrored,
              requireContinuationConfirmation,
              requireContinuationBenchmarkPrior,
              routePinned);
      Optional<PostReviewContinuationResult> postReviewContinuationResult =
          maybeActOnPostReviewContinuation(
              runId,
              plan,
              rootCtx,
              execResult,
              postReviewContinuation,
              afterInitialReview,
              requireContinuationConfirmation,
              requireContinuationBenchmarkPrior,
              designBlock,
              projectId,
              selectedRepoIds,
              skipWiring,
              reviewerMaxIter);
      if (postReviewContinuationResult.isPresent()) {
        PostReviewContinuationResult result = postReviewContinuationResult.get();
        execResult = result.execResult();
        reviewResult = result.reviewResult();
      }

      // 3b. Enterprise enforcement (project runs only) — make the formerly
      // "decorative" engines real (RL-2026-05-037). StandardsRunner persists
      // standards violations so /standards/report is populated; the breaking-
      // change classifier surfaces touched-contract breaks as report-only run
      // issues. Both project-gated + flag-gated (default OFF) + non-fatal.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> contractBreakingIssues =
          java.util.List.of();
      if (projectId != null) {
        if (featureFlagService.isEnabled("standards.enforcement.enabled")) {
          try {
            standardsEnforcer.runStandardsEnforcement(runId, projectId, rootCtx.workspaceRoot());
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

      // 4. Ensure README.md exists in the workspace
      // If the planner emitted a "create README" task the file is already there
      // and we leave it alone. If not (typical for trivial specs), write a
      // deterministic run-meta README so the download-zip experience always
      // includes an entry-point doc.
      contextComposer.ensureReadme(
          runId, workspace, userRequest, plan, execResult, reviewResult, startedAt);

      // 4b. Pre-resolve terminal status BEFORE the validation report.
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
      stampRunStatusReason(runId, sr);

      // 5. Generate validation report + kick off test generation
      Optional<ValidationReport> validationReport =
          validationKickoff.generateValidationReport(
          runId, execResult, reviewResult, plan, contractBreakingIssues);
      Optional<PostReviewContinuation> postValidationContinuation =
          maybeEmitPostValidationContinuationDecision(
              runId,
              execResult,
              validationReport.orElse(null),
              requireContinuationConfirmation,
              requireContinuationBenchmarkPrior,
              routePinned);
      Optional<PostReviewContinuationResult> postValidationContinuationResult =
          maybeActOnPostReviewContinuation(
              runId,
              plan,
              rootCtx,
              execResult,
              postValidationContinuation,
              costTracker.snapshot(runId),
              requireContinuationConfirmation,
              requireContinuationBenchmarkPrior,
              designBlock,
              projectId,
              selectedRepoIds,
              skipWiring,
              reviewerMaxIter);
      if (postValidationContinuationResult.isPresent()) {
        PostReviewContinuationResult result = postValidationContinuationResult.get();
        execResult = result.execResult();
        reviewResult = result.reviewResult();
        srDerived = resolveTerminalStatus(execResult, reviewResult);
        sr =
            featureFlagService.isEnabled("scoring.v2-verified")
                ? srDerived
                : new StatusAndReason(mapOverallToV1Status(execResult.overall()), srDerived.reason());
        stampRunStatusReason(runId, sr);
        Optional<ValidationReport> rerunValidationReport =
            validationKickoff.generateValidationReport(
                runId, execResult, reviewResult, plan, contractBreakingIssues);
        if (validationReportQualityFailed(
            rerunValidationReport.orElse(null), postReviewRequiredQualityThreshold)) {
          sr = new StatusAndReason("PARTIAL", "VALIDATION_REPORT_LOW_QUALITY");
          stampRunStatusReason(runId, sr);
        }
      } else if (validationReportQualityFailed(
          validationReport.orElse(null), postReviewRequiredQualityThreshold)) {
        sr = new StatusAndReason("PARTIAL", "VALIDATION_REPORT_LOW_QUALITY");
        stampRunStatusReason(runId, sr);
      }
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

      // 6. Final workspace commit so `/push-github` has refs to push
      commitWorkspaceOutputs(runId, workspace);

      // 6b. Project memory: append compressed ledger entry for project-scoped runs.
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
      String persistedFinalStatus =
          runRepo.findById(runId).map(PipelineRun::getStatus).orElse(finalStatus);
      log.info(
          "RunOrchestrator run={} {} exec={} review={} reason={}",
          runId,
          persistedFinalStatus,
          execResult.overall(),
          reviewResult == null ? "n/a" : reviewResult.overall(),
          finalReason);

      // 7b. Auto-push every successful run
      // Any run that reaches COMPLETE/PARTIAL and targets an EXISTING repo
      // gets pushed to GitHub automatically. Previously only project-scoped
      // runs auto-pushed; standalone /api/runs runs required the user to
      // click "Push & open PR" — a footgun when the run looks complete in
      // the UI but no PR exists. Manual "Push & open PR" still works as a
      // re-push. Feature flag `project.runs.auto-push` still gates the
      // project-scoped path for back-compat; standalone runs are always on.
      if ("COMPLETE".equals(persistedFinalStatus) || "PARTIAL".equals(persistedFinalStatus)) {
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
      terminate(runId, "FAILED", INTERNAL_ERROR_REASON, startedAt);
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
      resourceCleaner.cleanupRun(runId);
      // P3 local-hands: drop the remote-tool session on EVERY terminal/exception/cancel path. This
      // finally is the single guaranteed unwind point (cancel interrupts the pipeline thread and
      // unwinds through here). No-op for non-LOCAL runs.
      if (localRun) {
        try {
          remoteToolDispatcher.unregister(runId);
        } catch (Exception ignore) {
          /* best-effort */
        }
      }
      // Workspace retained for code-view / download endpoints (Phase 11.C).
      if (workspace != null) {
        log.info("RunOrchestrator run={} workspace at {}", runId, workspace);
      }
    }
  }

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
   * <p>Cost cap: CodeGenEngine threads through to {@link
   * com.navneet.executespec.engine.WorkerAgent}'s iter-3 budget guard, which reads {@code
   * pipeline_run.hard_cap_usd}. We default that to {@link #DEFAULT_PATCH_HARD_CAP_USD} when unset
   * so a runaway patch run stops without manual intervention.
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
            contextComposer.buildCodeIntelContext(
                run.getProjectId(),
                projectRepoService.resolveForRun(run.getProjectId(), null),
                userRequestLive,
                featureFlagService.isEnabled("codeintel.tiered.enabled"));
        if (!codeIntel.isBlank()) {
          userRequestLive = codeIntel + "\n\n---\n\nTASK:\n" + userRequestLive;
          log.info("PATCH run={} injected code-intel ({} chars)", runId, codeIntel.length());
        }
      } catch (Exception e) {
        log.warn("PATCH run={} code-intel injection skipped: {}", runId, e.getMessage());
      }
    }

    EngineResult result;
    try {
      result =
          codeGenEngine.solve(
              EngineRequest.builder()
                  .taskSpec(userRequestLive)
                  .workspace(rootCtx.workspaceRoot())
                  .provider(provider)
                  .model(model)
                  .runId(runId)
                  .taskId(rootCtx.taskId())
                  .verifyMode(VerifyMode.HARD_GATE)
                  .build());
    } catch (Exception e) {
      log.error("PATCH run={} dispatch threw", runId, e);
      terminate(
          runId,
          "FAILED",
          "PATCH_ENGINE_ERROR",
          "patch engine error: " + e.getClass().getSimpleName(),
          startedAt);
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
      case STAGNATED -> {
        terminalStatus = "COMPLETE";
        terminalReason = "PATCH_STAGNATION";
      }
      case STOPPED -> {
        terminalStatus = "COMPLETE";
        terminalReason = "PATCH_STOPPED_WITHOUT_COMPLETE";
      }
      case ITER_LIMIT -> {
        terminalStatus = "PARTIAL";
        terminalReason = "PATCH_ITER_LIMIT";
      }
      case BUDGET -> {
        terminalStatus = "PARTIAL";
        terminalReason = "PATCH_BUDGET_EXCEEDED";
      }
      case WALL_CLOCK -> {
        terminalStatus = "PARTIAL";
        terminalReason = "PATCH_WALL_CLOCK_EXCEEDED";
      }
      case ERROR -> {
        terminalStatus = "FAILED";
        terminalReason = "PATCH_ENGINE_ERROR";
      }
      default -> {
        terminalStatus = "FAILED";
        terminalReason = "PATCH_UNKNOWN_OUTCOME";
      }
    }

    RunCostTracker.Snapshot patchCost = costTracker.snapshot(runId);

    log.info(
        "PATCH terminal run={} status={} reason={} iter={} toolCalls={} in={} out={} ${}",
        runId,
        terminalStatus,
        terminalReason,
        result.iterations(),
        result.toolCalls(),
        patchCost.inputTokens(),
        patchCost.outputTokens(),
        String.format("%.4f", patchCost.costUsd()));

    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            "patch",
            "Patch-mode complete: " + result.outcome() + " in " + result.iterations() + " iters"));
    terminate(
        runId,
        terminalStatus,
        terminalReason,
        "patch outcome="
            + result.outcome()
            + "; iterations="
            + result.iterations()
            + "; toolCalls="
            + result.toolCalls(),
        startedAt);
    String persistedTerminalStatus =
        runRepo.findById(runId).map(PipelineRun::getStatus).orElse(terminalStatus);

    // V183 (2026-05-08) — finding-triggered patch runs are project-mode (PR review autofix,
    // tech debt fix, code review autofix, CI auto-fix). They live on a real customer
    // project + repo and want their workspace diff pushed to the remote — same way build
    // mode does. Bench sources (`swebench`, `programbench`) are bench-mode and the bench
    // services capture the diff via `docker exec git diff` themselves, so we skip the
    // push for them. The discriminator is the source field the FindingPatchSubmitter
    // stamps as `finding:<TYPE>`.
    if (("COMPLETE".equals(persistedTerminalStatus) || "PARTIAL".equals(persistedTerminalStatus))
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

  // cancel(runId) moved to RunLifecycleService (god-class split inc-6, audit #43).

  // V168 (2026-05-02) — pause + resume

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

  // pause(runId) + the dead PAUSED_RUN_MAX_AGE constant moved/removed in RunLifecycleService
  // (god-class split inc-6, audit #43).

  // resume(runId) moved to RunLifecycleService (god-class split inc-6b, audit #43).

  /**
   * Resume entry-point invoked from {@code RunLifecycleService}'s resume paths via {@link
   * RunControlPort}. Calls the canonical {@code runPipeline(...)} 11-arg overload — the deepest
   * one. The executor + worker check {@code pipeline_run.paused_task_id} on the row at every
   * boundary and skip / replay accordingly. Kept as a named seam (the staying port impl) because
   * every resume must come through here, which simplifies tests + observability hooks.
   */
  @Override
  public void runPipelineForResume(RunPipelineRequest req) {
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

  /** Outcome of a {@code RunLifecycleService.resumeAfterCrash(String)} dispatch. */
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

  // resumeAfterCrash(runId) moved to RunLifecycleService (god-class split inc-6b, audit #43).

  // runPipelineFromExecuteCheckpoint + finalizeRunAfterResume moved to RunLifecycleService
  // (god-class split inc-6b, audit #43).

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

  // haltAllActiveRuns(reason) + NON_TERMINAL_STATUSES + forceFailTask(runId, taskId, reason)
  // moved to RunLifecycleService (god-class split inc-6, audit #43).

  // retryTask(runId, taskId) moved to RunLifecycleService (god-class split inc-6b, audit #43).

  // retryValidation(runId) + reReview(runId) + runReReviewBlocking(runId) moved to
  // RunLifecycleService (god-class split inc-6b, audit #43).

  /**
   * Build a minimal {@link ExecutionResult} from the persisted {@code agent_task} rows, just enough
   * that {@link #resolveTerminalStatus} can read the four counters it cares about ({@code overall},
   * {@code tasksSuccessful}, {@code tasksFailed}, {@code anyIntermediateWarning}). {@code perTask}
   * is intentionally empty — the only consumer that iterates it ({@code generateValidationReport})
   * is invoked with {@code execResult=null} on the re-review path so it falls through to its own
   * task-row reconstruction branch.
   */
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

  @Override
  public void terminate(String runId, String finalStatus, String note, Instant startedAt) {
    terminate(runId, finalStatus, null, note, startedAt);
  }

  /**
   * v2.5.10 — 5-arg overload that also persists {@code status_reason} on the run and threads it to
   * the SSE complete event so the UI can show "PARTIAL because FINAL_BUILD_FAILED" instead of a
   * silent badge.
   */
  @Override
  public void terminate(
      String runId, String finalStatus, String statusReason, String note, Instant startedAt) {
    RunCostTracker.Snapshot cost = costTracker.finalizeRun(runId);
    String[] persistedStatus = {finalStatus};
    String[] persistedReason = {statusReason};
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
              String currentStatus = r.getStatus();
              if (!"CANCELLED".equals(currentStatus) && !"PAUSED".equals(currentStatus)) {
                r.setStatus(finalStatus);
                persistedStatus[0] = finalStatus;
              } else {
                persistedStatus[0] = currentStatus;
              }
              if (!"PAUSED".equals(persistedStatus[0])) {
                r.setCompletedAt(Instant.now());
              }
              if ("FAILED".equals(finalStatus) && r.getFailureReason() == null) {
                r.setFailureReason(note);
              }
              // v2.5.10 — persist status_reason so /api/runs/{id} surfaces it and the
              // UI's status badge + tooltip can explain "PARTIAL because X".
              // Only write if non-null: legacy callers still reach here without a
              // reason and we don't want to clobber a previously-set value.
              if (statusReason != null
                  && !isTerminal(currentStatus)
                  && !"PAUSED".equals(currentStatus)) {
                r.setStatusReason(statusReason);
              }
              persistedReason[0] = r.getStatusReason();
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
              persistenceWriter.writeRunCostLog(r, cost);

              // R-162 — referral grant hook. Delegates to ReferralCompletionHook
              // so the conditional + try/catch + idempotency contract lives in
              // one testable place. Fires only on COMPLETE + non-null owner;
              // ReferralService.onRefereeFirstReadyRun itself is idempotent.
              referralCompletionHook.onTerminalStatus(runId, r.getStatus(), r.getCreatedByUserId());

              // Audit 2026-05-13 Stage 2 — record (feature vector → actual cost) for k-NN
              // cost estimation. Best-effort; failures swallowed inside the service.
              costHistoryService.recordOnTerminal(runId);
            });
    String actualStatus = persistedStatus[0];
    String actualReason = persistedReason[0];
    broadcaster.emit(runId, RunEvent.runStatus(runId, actualStatus));
    broadcaster.emit(runId, RunEvent.costUpdate(runId, cost.costUsd()));
    broadcaster.emit(runId, RunEvent.complete(runId, actualStatus, actualReason));
    if (!"PAUSED".equals(actualStatus)) {
      broadcaster.complete(runId);
    }
    // V2 run-level metrics are emitted by RunService only. This orchestrator is a
    // parallel entry path — record run.outcome + run.latency here too so V1-endpoint-
    // submitted V2 runs are counted.
    metrics.recordRunOutcome(actualStatus);
    Instant effectiveStartedAt =
        startedAt != null
            ? startedAt
            : runRepo.findById(runId).map(PipelineRun::getStartedAt).orElse(Instant.now());
    metrics.recordRunLatency(
        actualStatus, java.time.Duration.between(effectiveStartedAt, Instant.now()));
    // v2.5.8 — fire Spring ApplicationEvent so RunCompletionNotifier (in-app
    // notification), RunQuotaRefundListener (refund on failure), and
    // WorkspaceGcsUploadListener (archive workspace) all get notified.
    // V1 PipelineHarness already publishes this; V2 previously did not,
    // so notifications silently never fired for any V2 run.
    try {
      if ("COMPLETE".equalsIgnoreCase(actualStatus) || "PARTIAL".equalsIgnoreCase(actualStatus)) {
        eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
      } else if ("FAILED".equalsIgnoreCase(actualStatus)
          || "CANCELLED".equalsIgnoreCase(actualStatus)) {
        eventPublisher.publishEvent(
            new PipelineEvent.RunFailed(runId, note == null ? actualStatus : note));
      }
    } catch (Exception eventEx) {
      log.warn("V2 run={} event publish failed (non-fatal): {}", runId, eventEx.getMessage());
    }

    // Wave 2.1 — activation funnel step 5. Fires once per user on the
    // first run that reaches COMPLETE or PARTIAL (terminal-but-usable).
    // FAILED/CANCELLED don't count as activation signals — a user who
    // submits 3 runs that all fail hasn't hit the "my code works" moment.
    if ("COMPLETE".equalsIgnoreCase(actualStatus) || "PARTIAL".equalsIgnoreCase(actualStatus)) {
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
                            "terminalStatus", actualStatus,
                            "statusReason", actualReason == null ? "" : actualReason,
                            "aiProvider", r.getAiProvider() == null ? "" : r.getAiProvider()));
                  }
                });
      } catch (Exception trackEx) {
        log.warn(
            "Activation track RUN_COMPLETE failed for run={}: {}", runId, trackEx.getMessage());
      }
    }
  }

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
  // Run-end validation-report kickoff (assemble ReportInput + accuracy + synthesise ReviewIssues +
  // score) moved to RunValidationKickoff (god-class split inc-5b); called via validationKickoff.

  /**
   * Writes {@code .agent-context/project.json} with the planner's declared project type so {@link
   * com.navneet.executespec.deployment.preview.DevDockerfileGenerator} picks the right Dockerfile
   * strategy for preview / deploy on V2 runs.
   *
   * <p>V1 does the same in {@code PipelineHarness.initAgentContext()}; without it, the generator
   * falls back to filesystem heuristics — works for simple cases but less reliable for polyglot
   * workspaces.
   */
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

  private void stampRunStatusReason(String runId, StatusAndReason statusReason) {
    if (statusReason == null || statusReason.reason() == null) {
      return;
    }
    try {
      runRepo
          .findById(runId)
          .ifPresent(
              r -> {
                r.setStatusReason(statusReason.reason());
                runRepo.save(r);
              });
    } catch (Exception statusReasonEx) {
      // Non-fatal — terminate() will retry the same write later. Worst case: report uses the
      // heuristic for this one run.
      log.warn(
          "RunOrchestrator run={} pre-stamp statusReason failed: {}",
          runId,
          statusReasonEx.getMessage());
    }
  }

  private Optional<PostReviewContinuation> maybeEmitPostReviewContinuationDecision(
      String runId,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      boolean reviewerErrored,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned) {
    if (!postReviewContinuationEnabled || !reviewerQualityFailed(reviewResult, reviewerErrored)) {
      return Optional.empty();
    }
    Optional<TaskOutcome> target = reviewerContinuationTarget(execResult);
    if (target.isEmpty()) {
      log.warn(
          "RunOrchestrator run={} post-review continuation skipped: no retryable worker task",
          runId);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              null,
              "Post-review continuation skipped: reviewer failed but no retryable task was found"));
      return Optional.empty();
    }

    TaskOutcome task = target.get();
    GoalDonenessDecision goalDecision =
        reviewerLowQualityGoalDecision(reviewResult, reviewerErrored);
    GoalContinuationPolicyDecision decision =
        goalContinuationAdvisor.advise(
            GoalContinuationAdvisorRequest.builder()
                .goalDecision(goalDecision)
                .currentProvider(task.dispatchedProvider())
                .currentModel(task.dispatchedModel())
                .taskCategory("PROJECT_PATCH")
                .requiredQualityThreshold(postReviewRequiredQualityThreshold)
                .providerAvailability(providerAvailability(runId))
                .providerPreferenceOrder(reviewerProviderPreferenceOrder(task.dispatchedProvider()))
                .qualityInsufficient(true)
                .routePinned(routePinned)
                .expensiveContinuationRequiresConfirmation(confirmationRequired)
                .benchmarkQualityPriorRequiredForAutomaticContinuation(benchmarkPriorRequired)
                .providerSwitchesUsed(providerSwitchesUsed(execResult))
                .maxProviderSwitches(Math.max(0, postReviewMaxProviderSwitches))
                .modelStepUpsUsed(modelStepUpsUsed(task))
                .maxModelStepUps(Math.max(0, postReviewMaxModelStepUps))
                .modelStepDownsUsed(modelStepDownsUsed(task))
                .maxModelStepDowns(Math.max(0, postReviewMaxModelStepDowns))
                .build());
    decision = withContinuationSource(decision, "POST_REVIEW");

    String summary =
        "Post-review continuation decision: "
            + decision.decision()
            + " ("
            + firstReason(decision)
            + ")";
    log.warn(
        "RunOrchestrator run={} task={} post-review continuation decision={} reason={}",
        runId,
        task.taskId(),
        decision.decision(),
        firstReason(decision));
    broadcaster.emit(
        runId,
        RunEvent.continuationDecision(
            runId,
            task.taskId(),
            summary,
            postReviewContinuationDecisionData(
                decision,
                confirmationRequired,
                benchmarkPriorRequired,
                routePinned,
                "POST_REVIEW")));
    broadcaster.emit(runId, RunEvent.log(runId, task.taskId(), summary));
    persistPostReviewContinuationDecision(
        runId,
        task.taskId(),
        decision,
        confirmationRequired,
        benchmarkPriorRequired,
        routePinned,
        "POST_REVIEW");
    return Optional.of(new PostReviewContinuation(task, decision));
  }

  private Optional<PostReviewContinuation> maybeEmitPostValidationContinuationDecision(
      String runId,
      ExecutionResult execResult,
      ValidationReport report,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned) {
    if (!postReviewContinuationEnabled
        || !validationReportQualityFailed(report, postReviewRequiredQualityThreshold)) {
      return Optional.empty();
    }
    Optional<TaskOutcome> target = reviewerContinuationTarget(execResult);
    if (target.isEmpty()) {
      log.warn(
          "RunOrchestrator run={} post-validation continuation skipped: no retryable worker task",
          runId);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              null,
              "Post-validation continuation skipped: low validation score but no retryable task was"
                  + " found"));
      return Optional.empty();
    }

    TaskOutcome task = target.get();
    GoalDonenessDecision goalDecision =
        validationReportLowQualityGoalDecision(report, postReviewRequiredQualityThreshold);
    GoalContinuationPolicyDecision decision =
        goalContinuationAdvisor.advise(
            GoalContinuationAdvisorRequest.builder()
                .goalDecision(goalDecision)
                .currentProvider(task.dispatchedProvider())
                .currentModel(task.dispatchedModel())
                .taskCategory("PROJECT_PATCH")
                .requiredQualityThreshold(postReviewRequiredQualityThreshold)
                .providerAvailability(providerAvailability(runId))
                .providerPreferenceOrder(reviewerProviderPreferenceOrder(task.dispatchedProvider()))
                .qualityInsufficient(true)
                .routePinned(routePinned)
                .expensiveContinuationRequiresConfirmation(confirmationRequired)
                .benchmarkQualityPriorRequiredForAutomaticContinuation(benchmarkPriorRequired)
                .providerSwitchesUsed(providerSwitchesUsed(execResult))
                .maxProviderSwitches(Math.max(0, postReviewMaxProviderSwitches))
                .modelStepUpsUsed(modelStepUpsUsed(task))
                .maxModelStepUps(Math.max(0, postReviewMaxModelStepUps))
                .modelStepDownsUsed(modelStepDownsUsed(task))
                .maxModelStepDowns(Math.max(0, postReviewMaxModelStepDowns))
                .build());
    decision = withContinuationSource(decision, "POST_VALIDATION_REPORT");

    String summary =
        "Post-validation continuation decision: "
            + decision.decision()
            + " ("
            + firstReason(decision)
            + ")";
    log.warn(
        "RunOrchestrator run={} task={} post-validation continuation decision={} reason={}",
        runId,
        task.taskId(),
        decision.decision(),
        firstReason(decision));
    broadcaster.emit(
        runId,
        RunEvent.continuationDecision(
            runId,
            task.taskId(),
            summary,
            postReviewContinuationDecisionData(
                decision,
                confirmationRequired,
                benchmarkPriorRequired,
                routePinned,
                "POST_VALIDATION_REPORT")));
    broadcaster.emit(runId, RunEvent.log(runId, task.taskId(), summary));
    persistPostReviewContinuationDecision(
        runId,
        task.taskId(),
        decision,
        confirmationRequired,
        benchmarkPriorRequired,
        routePinned,
        "POST_VALIDATION_REPORT");
    return Optional.of(new PostReviewContinuation(task, decision));
  }

  private record PostReviewContinuation(
      TaskOutcome targetTask, GoalContinuationPolicyDecision decision) {}

  private Optional<PostReviewContinuationResult> maybeActOnPostReviewContinuation(
      String runId,
      Plan plan,
      ExecutionContext rootCtx,
      ExecutionResult previousExecResult,
      Optional<PostReviewContinuation> continuation,
      RunCostTracker.Snapshot beforePostReviewAction,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      String designBlock,
      String projectId,
      List<UUID> selectedRepoIds,
      boolean skipWiring,
      Integer reviewerMaxIter) {
    if (continuation == null || continuation.isEmpty()) {
      return Optional.empty();
    }
    PostReviewContinuation action = continuation.get();
    GoalContinuationPolicyDecision decision = action.decision();
    if (!actionablePostReviewContinuationDecision(decision)) {
      return Optional.empty();
    }
    ModelProviderScore candidate = decision.selectedCandidate().orElse(null);
    if (candidate == null
        || action.targetTask() == null
        || sameProviderModel(
            candidate, action.targetTask().dispatchedProvider(), action.targetTask().dispatchedModel())) {
      return Optional.empty();
    }

    String targetTaskId = action.targetTask().taskId();
    String targetTaskKey = fullTaskKey(runId, targetTaskId);
    var taskRow = taskRepo.findById(targetTaskKey);
    if (taskRow.isEmpty()) {
      log.warn(
          "RunOrchestrator run={} post-review continuation skipped: task row not found {}",
          runId,
          targetTaskKey);
      return Optional.empty();
    }

    String reason = firstReason(decision);
    String summary =
        "Post-review continuation action: "
            + decision.decision()
            + " -> rerun task with "
            + candidate.label()
            + " ("
            + reason
            + ")";
    log.warn(
        "RunOrchestrator run={} task={} post-review continuation action={} selected={} reason={}",
        runId,
        targetTaskId,
        decision.decision(),
        candidate.label(),
        reason);
    ContinuationUsage seededUsage =
        postReviewContinuationUsage(previousExecResult, action.targetTask(), decision);
    ContinuationLimitData limitData =
        postReviewContinuationLimitData(action.targetTask(), decision, seededUsage);
    broadcaster.emit(
        runId,
        RunEvent.continuationAction(
            runId,
            targetTaskId,
            summary,
            decision.decision().name(),
            candidate.provider(),
            candidate.model(),
            reason,
            1,
            1,
            limitData.limitName(),
            limitData.used(),
            limitData.max()));
    broadcaster.emit(runId, RunEvent.log(runId, targetTaskId, summary));
    appendPostReviewContinuationAction(taskRow.get(), decision, candidate, limitData, reason);

    var task = taskRow.get();
    String previousStatus = task.getStatus();
    Instant previousStartedAt = task.getStartedAt();
    Instant previousCompletedAt = task.getCompletedAt();
    Long previousDurationMs = task.getDurationMs();
    task.setStatus("PENDING");
    task.setStartedAt(null);
    task.setCompletedAt(null);
    taskRepo.save(task);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, targetTaskId, "PENDING"));

    try {
      ExecutionResult rerunResult =
          executor.execute(
              ExecutionRequest.builder()
                  .plan(plan)
                  .provider(candidate.provider())
                  .model(candidate.model())
                  .runContext(rootCtx)
                  .maxIterationsPerTask(null)
                  .enableSmokeTests(true)
                  .requireConfirmationBeforeExpensiveContinuation(confirmationRequired)
                  .requireBenchmarkPriorForAutomaticContinuation(benchmarkPriorRequired)
                  .initialProviderSwitchesUsed(seededUsage.providerSwitches())
                  .initialModelStepUpsUsed(seededUsage.modelStepUps())
                  .initialModelStepDownsUsed(seededUsage.modelStepDowns())
                  .designBlock(designBlock)
                  .projectId(projectId)
                  .workspaceSummary(
                      contextComposer.buildMultiRepoSummary(projectId, selectedRepoIds))
                  .build(),
              new SequentialExecutor.ResumeRequest(targetTaskKey));
      persistenceWriter.applyExecutionResultToTasks(runId, rerunResult);
      RunCostTracker.Snapshot afterPostReviewExec = costTracker.snapshot(runId);
      int workerIn = afterPostReviewExec.inputTokens() - beforePostReviewAction.inputTokens();
      int workerOut = afterPostReviewExec.outputTokens() - beforePostReviewAction.outputTokens();
      tokenAttributor.attributeWorkerAdditionalTask(
          runId,
          targetTaskId,
          workerIn,
          workerOut,
          candidate.provider().toUpperCase(Locale.ROOT),
          candidate.model());

      maybeRunLintAutofix(runId, rootCtx.workspaceRoot());

      ProviderModel reviewerRoute = reviewerRouteFor(candidate.provider(), candidate.model(), rerunResult);
      ReviewerResult rerunReviewResult = null;
      try {
        rerunReviewResult =
            reviewer.review(
                ReviewerRequest.builder()
                    .provider(reviewerRoute.provider())
                    .model(reviewerRoute.model())
                    .context(rootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(skipWiring)
                    .maxIterationsPerPass(reviewerMaxIter)
                    .build());
        persistenceWriter.persistReview(runId, rerunReviewResult);
        RunCostTracker.Snapshot afterRerunReview = costTracker.snapshot(runId);
        int reviewerIn = afterRerunReview.inputTokens() - afterPostReviewExec.inputTokens();
        int reviewerOut = afterRerunReview.outputTokens() - afterPostReviewExec.outputTokens();
        tokenAttributor.attributePhaseAdditional(
            runId,
            com.navneet.executespec.cost.RunTokenAttributor.Phase.REVIEWER,
            reviewerIn,
            reviewerOut,
            reviewerRoute.provider().toUpperCase(Locale.ROOT),
            reviewerRoute.model());
      } catch (Exception ex) {
        log.warn(
            "RunOrchestrator run={} post-review continuation reviewer failed — recording partial review",
            runId,
            ex);
        rerunReviewResult = reviewerFailedResult(ex);
        persistenceWriter.persistReviewFailure(runId, ex);
      }
      return Optional.of(new PostReviewContinuationResult(rerunResult, rerunReviewResult));
    } catch (Exception ex) {
      log.warn(
          "RunOrchestrator run={} post-review continuation action failed for task={}: {}",
          runId,
          targetTaskId,
          ex.getMessage(),
          ex);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              targetTaskId,
              "Post-review continuation action failed: " + ex.getMessage()));
      restoreTaskTerminalState(
          targetTaskKey, previousStatus, previousStartedAt, previousCompletedAt, previousDurationMs);
      return Optional.empty();
    }
  }

  private record PostReviewContinuationResult(
      ExecutionResult execResult, ReviewerResult reviewResult) {}

  static ReviewerResult reviewerFailedResult(Exception ex) {
    String message =
        ex == null || ex.getMessage() == null || ex.getMessage().isBlank()
            ? "reviewer failed"
            : ex.getMessage();
    return ReviewerResult.builder()
        .overall(ReviewerResult.Overall.REVIEW_FAILED)
        .generalPassSummary("Reviewer failed: " + truncate(message, 500))
        .finalBuildPassed(false)
        .finalBuildOutput(truncate(message, 2000))
        .build();
  }

  static boolean actionablePostReviewContinuationDecision(GoalContinuationPolicyDecision decision) {
    if (decision == null || decision.selectedCandidate().isEmpty()) {
      return false;
    }
    return switch (decision.decision()) {
      case STEP_UP_MODEL, CHANGE_PROVIDER -> true;
      case STEP_DOWN_MODEL,
              CONTINUE_SAME_MODEL_PROVIDER,
              STOP_DONE,
              STOP_BLOCKED,
              STOP_FAILED,
              STOP_WASTE_GUARD ->
          false;
    };
  }

  private static boolean sameProviderModel(
      ModelProviderScore candidate, String dispatchedProvider, String dispatchedModel) {
    return candidate != null
        && candidate.provider().equalsIgnoreCase(dispatchedProvider == null ? "" : dispatchedProvider)
        && candidate.model().equalsIgnoreCase(dispatchedModel == null ? "" : dispatchedModel);
  }

  static String fullTaskKey(String runId, String taskId) {
    if (runId == null || runId.length() < 8 || taskId == null || taskId.isBlank()) {
      return taskId;
    }
    String prefix = runId.substring(0, 8) + "_";
    return taskId.startsWith(prefix) ? taskId : prefix + taskId;
  }

  static ContinuationUsage postReviewContinuationUsage(
      ExecutionResult execResult, TaskOutcome task, GoalContinuationPolicyDecision decision) {
    int providerSwitches = providerSwitchesUsed(execResult);
    int modelStepUps = modelStepUpsUsed(task);
    int modelStepDowns = modelStepDownsUsed(task);
    if (decision != null && decision.decision() == RunContinuationDecision.CHANGE_PROVIDER) {
      providerSwitches++;
    } else if (decision != null && decision.decision() == RunContinuationDecision.STEP_UP_MODEL) {
      modelStepUps++;
    } else if (decision != null && decision.decision() == RunContinuationDecision.STEP_DOWN_MODEL) {
      modelStepDowns++;
    }
    return new ContinuationUsage(providerSwitches, modelStepUps, modelStepDowns);
  }

  private ContinuationLimitData postReviewContinuationLimitData(
      TaskOutcome task, GoalContinuationPolicyDecision decision, ContinuationUsage seededUsage) {
    if (decision != null && decision.decision() == RunContinuationDecision.CHANGE_PROVIDER) {
      return new ContinuationLimitData(
          "maxProviderSwitches",
          seededUsage.providerSwitches(),
          Math.max(0, postReviewMaxProviderSwitches));
    }
    if (decision != null && decision.decision() == RunContinuationDecision.STEP_UP_MODEL) {
      return new ContinuationLimitData(
          "maxModelStepUps", seededUsage.modelStepUps(), Math.max(0, postReviewMaxModelStepUps));
    }
    if (decision != null && decision.decision() == RunContinuationDecision.STEP_DOWN_MODEL) {
      return new ContinuationLimitData(
          "maxModelStepDowns",
          seededUsage.modelStepDowns(),
          Math.max(0, postReviewMaxModelStepDowns));
    }
    return new ContinuationLimitData("postReview", 1, 1);
  }

  private void appendPostReviewContinuationAction(
      com.navneet.executespec.state.entity.AgentTask task,
      GoalContinuationPolicyDecision decision,
      ModelProviderScore candidate,
      ContinuationLimitData limitData,
      String reason) {
    if (task == null || decision == null || candidate == null || limitData == null) {
      return;
    }
    try {
      ArrayNode actions = readContinuationActions(task.getContinuationActionsJson());
      ObjectNode action = actions.addObject();
      action.put("status", "EXECUTED");
      action.put("decision", decision.decision().name());
      action.put("provider", candidate.provider());
      action.put("model", candidate.model());
      action.put("reason", reason == null ? "" : reason);
      action.put("limitName", limitData.limitName());
      action.put("usedLimit", limitData.used());
      action.put("maxLimit", limitData.max());
      action.put("source", continuationActionSource(decision));
      action.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepo.saveAndFlush(task);
    } catch (Exception ex) {
      log.warn(
          "post-review continuation: failed to append action history for task {}: {}",
          task.getTaskId(),
          ex.getMessage(),
          ex);
    }
  }

  private static String continuationActionSource(GoalContinuationPolicyDecision decision) {
    Object source = decision == null || decision.policy() == null ? null : decision.policy().get("source");
    return source == null || source.toString().isBlank() ? "POST_REVIEW" : source.toString();
  }

  private ArrayNode readContinuationActions(String json)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (json == null || json.isBlank()) {
      return objectMapper.createArrayNode();
    }
    JsonNode parsed = objectMapper.readTree(json);
    if (parsed != null && parsed.isArray()) {
      ArrayNode copy = objectMapper.createArrayNode();
      parsed.forEach(copy::add);
      return copy;
    }
    return objectMapper.createArrayNode();
  }

  private void maybeRunLintAutofix(String runId, Path workspaceRoot) {
    if (!featureFlagService.isEnabled("lint.autofix.enabled")) {
      return;
    }
    try {
      com.navneet.executespec.lint.LintPass.LintReport lintReport = lintPass.run(workspaceRoot);
      if (lintReport.changedAnything()) {
        log.info("RunOrchestrator run={} post-review lint auto-fix: {}", runId, lintReport.summary());
        broadcaster.emit(runId, RunEvent.lintAutofixApplied(runId, lintReport.summary()));
      } else if (!lintReport.skipped()) {
        log.debug("RunOrchestrator run={} post-review lint auto-fix: no changes", runId);
      }
    } catch (Exception e) {
      log.warn(
          "RunOrchestrator run={} post-review lint auto-fix failed (continuing): {}",
          runId,
          e.getMessage());
    }
  }

  private void restoreTaskTerminalState(
      String taskKey, String status, Instant startedAt, Instant completedAt, Long durationMs) {
    if (taskKey == null || taskKey.isBlank()) {
      return;
    }
    try {
      taskRepo
          .findById(taskKey)
          .ifPresent(
              task -> {
                task.setStatus(status);
                task.setStartedAt(startedAt);
                task.setCompletedAt(completedAt);
                task.setDurationMs(durationMs);
                taskRepo.save(task);
              });
    } catch (RuntimeException ex) {
      log.warn(
          "post-review continuation: failed to restore task state for {}: {}",
          taskKey,
          ex.getMessage());
    }
  }

  record ContinuationUsage(int providerSwitches, int modelStepUps, int modelStepDowns) {}

  private record ContinuationLimitData(String limitName, int used, int max) {}

  static boolean reviewerQualityFailed(ReviewerResult reviewResult, boolean reviewerErrored) {
    if (reviewerErrored) {
      return true;
    }
    if (reviewResult == null || reviewResult.overall() == null) {
      return false;
    }
    return Boolean.FALSE.equals(reviewResult.finalBuildPassed())
        || reviewResult.overall() == ReviewerResult.Overall.UNRESOLVED_ISSUES
        || reviewResult.overall() == ReviewerResult.Overall.REVIEW_FAILED;
  }

  static boolean validationReportQualityFailed(
      ValidationReport report, double requiredQualityThreshold) {
    if (report == null) {
      return false;
    }
    if (report.getPipelineResult() == com.navneet.executespec.report.entity.PipelineResult.FAILED
        || report.getPipelineResult()
            == com.navneet.executespec.report.entity.PipelineResult.NEEDS_ATTENTION) {
      return true;
    }
    Integer verifiedScore = report.getVerifiedScore();
    return verifiedScore != null
        && verifiedScore < sanitizeRequiredQualityThreshold(requiredQualityThreshold);
  }

  private static double sanitizeRequiredQualityThreshold(double requiredQualityThreshold) {
    return requiredQualityThreshold <= 0.0 ? 70.0 : Math.min(100.0, requiredQualityThreshold);
  }

  static GoalDonenessDecision validationReportLowQualityGoalDecision(
      ValidationReport report, double requiredQualityThreshold) {
    int score = report == null || report.getVerifiedScore() == null ? -1 : report.getVerifiedScore();
    String result =
        report == null || report.getPipelineResult() == null
            ? "UNKNOWN"
            : report.getPipelineResult().name();
    double threshold = sanitizeRequiredQualityThreshold(requiredQualityThreshold);
    String scoreText = score < 0 ? "unknown" : String.valueOf(score);
    return new GoalDonenessDecision(
        GoalDonenessState.FAILED,
        RunContinuationDecision.STOP_FAILED,
        "final validation report quality is below threshold: verifiedScore="
            + scoreText
            + ", requiredQualityThreshold="
            + threshold
            + ", pipelineResult="
            + result,
        "final validation report",
        List.of(),
        List.of("validation report did not prove acceptable output quality"),
        List.of("verifiedScore=" + scoreText, "pipelineResult=" + result),
        List.of("a stronger model/provider may be needed to improve final quality evidence"),
        false,
        true);
  }

  static GoalDonenessDecision reviewerLowQualityGoalDecision(
      ReviewerResult reviewResult, boolean reviewerErrored) {
    String verdict =
        reviewerErrored
            ? "REVIEWER_EXCEPTION"
            : reviewResult != null && Boolean.FALSE.equals(reviewResult.finalBuildPassed())
                ? "FINAL_BUILD_FAILED"
                : reviewResult == null || reviewResult.overall() == null
                ? "UNKNOWN"
                : reviewResult.overall().name();
    return new GoalDonenessDecision(
        GoalDonenessState.FAILED,
        RunContinuationDecision.STOP_FAILED,
        "final reviewer produced low-quality evidence: " + verdict,
        "final BUILD-mode reviewer",
        List.of(),
        List.of("reviewer did not prove the implementation is acceptable"),
        List.of("reviewerVerdict=" + verdict),
        List.of("a stronger model/provider may be needed to finish or repair the implementation"),
        false,
        true);
  }

  static Optional<TaskOutcome> reviewerContinuationTarget(ExecutionResult execResult) {
    if (execResult == null || execResult.perTask() == null || execResult.perTask().isEmpty()) {
      return Optional.empty();
    }
    List<TaskOutcome> perTask = execResult.perTask();
    for (int i = perTask.size() - 1; i >= 0; i--) {
      TaskOutcome task = perTask.get(i);
      if (successfulEnoughForReviewPromotion(task)
          && hasText(task.dispatchedProvider())
          && hasText(task.dispatchedModel())) {
        return Optional.of(task);
      }
    }
    return Optional.empty();
  }

  static List<String> reviewerProviderPreferenceOrder(String currentProvider) {
    List<String> providers = new ArrayList<>();
    addProviderPreference(providers, currentProvider);
    addProviderPreference(providers, "anthropic");
    addProviderPreference(providers, "openai");
    addProviderPreference(providers, "gemini");
    addProviderPreference(providers, "deepseek");
    addProviderPreference(providers, "openrouter");
    addProviderPreference(providers, "fireworks");
    return List.copyOf(providers);
  }

  private Map<String, Boolean> providerAvailability(String runId) {
    if (availableProviderResolver == null) {
      return Map.of();
    }
    try {
      Set<LlmProvider> available = availableProviderResolver.resolve(runId);
      return ProviderAvailabilityMap.fromAvailableProviders(available, knownProviderNames());
    } catch (RuntimeException ex) {
      log.warn(
          "Post-review continuation provider availability lookup failed for run={}: {}",
          runId,
          ex.getMessage());
      return Map.of();
    }
  }

  private Set<String> knownProviderNames() {
    Set<String> providers = new LinkedHashSet<>();
    if (modelRegistry != null && modelRegistry.allModels() != null) {
      modelRegistry.allModels().values().stream()
          .filter(config -> config != null && hasText(config.getProvider()))
          .map(com.navneet.executespec.router.ModelMatrixProperties.ModelConfig::getProvider)
          .forEach(providers::add);
    }
    return providers;
  }

  private static void addProviderPreference(List<String> providers, String provider) {
    if (!hasText(provider)) {
      return;
    }
    boolean alreadyPresent =
        providers.stream().anyMatch(existing -> existing.equalsIgnoreCase(provider));
    if (!alreadyPresent) {
      providers.add(provider);
    }
  }

  private static int providerSwitchesUsed(ExecutionResult execResult) {
    if (execResult == null || execResult.perTask() == null) {
      return 0;
    }
    return (int)
        execResult.perTask().stream()
            .filter(
                task ->
                    task != null
                        && task.continuationAction() == RunContinuationDecision.CHANGE_PROVIDER)
            .count();
  }

  private static int modelStepUpsUsed(TaskOutcome task) {
    return task != null && task.continuationAction() == RunContinuationDecision.STEP_UP_MODEL
        ? 1
        : 0;
  }

  private static int modelStepDownsUsed(TaskOutcome task) {
    return task != null && task.continuationAction() == RunContinuationDecision.STEP_DOWN_MODEL
        ? 1
        : 0;
  }

  private Map<String, Object> postReviewContinuationDecisionData(
      GoalContinuationPolicyDecision decision,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned,
      String source) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("decision", decision.decision().name());
    data.put("reasons", decision.reasons());
    Map<String, Object> policy = new LinkedHashMap<>();
    if (decision.policy() != null) {
      policy.putAll(decision.policy());
    }
    policy.put("taskCategory", "PROJECT_PATCH");
    policy.put("requiredQualityThreshold", postReviewRequiredQualityThreshold);
    policy.put("providerSwitchesUsed", Math.max(0, providerSwitchesUsedFromPolicy(decision)));
    policy.put("maxProviderSwitches", Math.max(0, postReviewMaxProviderSwitches));
    policy.put("maxModelStepUps", Math.max(0, postReviewMaxModelStepUps));
    policy.put("maxModelStepDowns", Math.max(0, postReviewMaxModelStepDowns));
    policy.put("requireConfirmationBeforeExpensiveContinuation", confirmationRequired);
    policy.put("requireBenchmarkPriorForAutomaticContinuation", benchmarkPriorRequired);
    policy.put("routePinned", routePinned);
    policy.put("source", continuationSource(source));
    data.put("policy", policy);
    decision
        .selectedCandidate()
        .ifPresent(candidate -> data.put("selectedCandidate", selectedCandidateData(candidate)));
    String approvalRequiredReason = postReviewContinuationApprovalRequiredReason(decision, policy);
    if (approvalRequiredReason != null) {
      data.put("approvalRequiredReason", approvalRequiredReason);
    }
    data.put("source", continuationSource(source));
    return data;
  }

  private static String continuationSource(String source) {
    return source == null || source.isBlank() ? "POST_REVIEW" : source;
  }

  private static GoalContinuationPolicyDecision withContinuationSource(
      GoalContinuationPolicyDecision decision, String source) {
    if (decision == null) {
      return null;
    }
    Map<String, Object> policy = new LinkedHashMap<>();
    if (decision.policy() != null) {
      policy.putAll(decision.policy());
    }
    policy.put("source", continuationSource(source));
    return new GoalContinuationPolicyDecision(
        decision.decision(), decision.selectedCandidate(), decision.reasons(), policy);
  }

  private static String postReviewContinuationApprovalRequiredReason(
      GoalContinuationPolicyDecision decision, Map<String, Object> policy) {
    if (decision == null
        || decision.decision() != RunContinuationDecision.STOP_BLOCKED
        || decision.selectedCandidate().isEmpty()) {
      return null;
    }
    ModelProviderScore candidate = decision.selectedCandidate().get();
    if (Boolean.TRUE.equals(policy.get("routePinned"))) {
      return "ROUTE_PINNED";
    }
    if (Boolean.TRUE.equals(policy.get("requireBenchmarkPriorForAutomaticContinuation"))
        && !candidate.benchmarkQualityPriorAvailable()) {
      return "BENCHMARK_PRIOR_REQUIRED";
    }
    if (Boolean.TRUE.equals(policy.get("requireConfirmationBeforeExpensiveContinuation"))) {
      return "EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED";
    }
    return null;
  }

  private void persistPostReviewContinuationDecision(
      String runId,
      String taskId,
      GoalContinuationPolicyDecision decision,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned,
      String source) {
    if (runId == null || taskId == null || decision == null) {
      return;
    }
    String taskKey = fullTaskKey(runId, taskId);
    try {
      taskRepo
          .findById(taskKey)
          .ifPresent(
              task -> {
                try {
                  task.setContinuationDecisionJson(
                      objectMapper.writeValueAsString(
                          postReviewContinuationDecisionData(
                              decision,
                              confirmationRequired,
                              benchmarkPriorRequired,
                              routePinned,
                              source)));
                  taskRepo.saveAndFlush(task);
                } catch (Exception ex) {
                  throw new IllegalStateException(ex);
                }
              });
    } catch (RuntimeException ex) {
      log.warn(
          "post-review continuation: failed to persist decision for task {}: {}",
          taskKey,
          ex.getMessage());
    }
  }

  private static int providerSwitchesUsedFromPolicy(GoalContinuationPolicyDecision decision) {
    Object value = decision == null || decision.policy() == null ? null : decision.policy().get("providerSwitchesUsed");
    if (value instanceof Number number) {
      return Math.max(0, number.intValue());
    }
    return 0;
  }

  private static Map<String, Object> selectedCandidateData(ModelProviderScore candidate) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("provider", candidate.provider());
    data.put("model", candidate.model());
    data.put("taskCategory", candidate.taskCategory());
    data.put("qualityScore", candidate.qualityScore());
    data.put("requiredQualityThreshold", candidate.requiredQualityThreshold());
    data.put("costPerMillionTokens", candidate.costPerMillionTokens());
    data.put("latencyP50Ms", candidate.latencyP50Ms());
    data.put("latencyP95Ms", candidate.latencyP95Ms());
    data.put("benchmarkQualityPriorAvailable", candidate.benchmarkQualityPriorAvailable());
    data.put("reasons", candidate.reasons());
    return data;
  }

  private static String firstReason(GoalContinuationPolicyDecision decision) {
    if (decision == null || decision.reasons().isEmpty()) {
      return "no continuation reason supplied";
    }
    return decision.reasons().get(0);
  }

  record ProviderModel(String provider, String model) {}

  static ProviderModel reviewerRouteFor(
      String defaultProvider, String defaultModel, ExecutionResult exec) {
    ProviderModel fallback = new ProviderModel(defaultProvider, defaultModel);
    if (exec == null || exec.perTask() == null || exec.perTask().isEmpty()) {
      return fallback;
    }
    List<TaskOutcome> perTask = exec.perTask();
    for (int i = perTask.size() - 1; i >= 0; i--) {
      TaskOutcome task = perTask.get(i);
      if (!successfulEnoughForReviewPromotion(task) || !qualityContinuationAction(task)) {
        continue;
      }
      ProviderModel candidate =
          new ProviderModel(task.continuationActionProvider(), task.continuationActionModel());
      if (sameRoute(defaultProvider, defaultModel, candidate)) {
        return fallback;
      }
      return candidate;
    }
    return fallback;
  }

  private static boolean successfulEnoughForReviewPromotion(TaskOutcome task) {
    if (task == null || task.status() == null) {
      return false;
    }
    return switch (task.status()) {
      case SUCCESS, SUCCESS_AFTER_RETRY, COMPLETED_WITH_WARNING -> true;
      case WORKER_FAILED, SKIPPED -> false;
    };
  }

  private static boolean qualityContinuationAction(TaskOutcome task) {
    if (!hasText(task.continuationActionProvider()) || !hasText(task.continuationActionModel())) {
      return false;
    }
    RunContinuationDecision action = task.continuationAction();
    return action == RunContinuationDecision.STEP_UP_MODEL
        || action == RunContinuationDecision.CHANGE_PROVIDER;
  }

  private static boolean sameRoute(String provider, String model, ProviderModel route) {
    if (route == null) {
      return false;
    }
    return equalsIgnoreCase(provider, route.provider()) && equalsIgnoreCase(model, route.model());
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    if (left == null || right == null) {
      return left == right;
    }
    return left.equalsIgnoreCase(right);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
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
    if (verdict == com.navneet.executespec.reviewer.ReviewerResult.Overall.REVIEW_FAILED) {
      return new StatusAndReason("PARTIAL", "REVIEWER_FAILED");
    }
    if (review != null && !buildPassed) {
      return new StatusAndReason("PARTIAL", "FINAL_BUILD_FAILED");
    }
    if (verdict == com.navneet.executespec.reviewer.ReviewerResult.Overall.UNRESOLVED_ISSUES) {
      return new StatusAndReason("PARTIAL", "REVIEWER_UNRESOLVED");
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

  private static boolean canRoutePinnedModel(
      LlmProvider provider, String canonicalModel, String ownerProvider) {
    if (provider == null || canonicalModel == null) return false;
    if (provider.name().equalsIgnoreCase(ownerProvider)) return true;
    return switch (provider) {
      case OPENROUTER -> OpenRouterModelMapper.isSupported(canonicalModel);
      case FIREWORKS -> FireworksModelMapper.isSupported(canonicalModel);
      case DEEPSEEK -> canonicalModel.startsWith("deepseek-v4-");
      default -> false;
    };
  }

  /**
   * Per-block cap for enterprise-knowledge planner injection (contracts + ADRs, flag {@code
   * enterprise.inject.enabled}). Each block is independently truncated so a large registry can't
   * blow the planner cold-start budget.
   */
  private static final int ENTERPRISE_INJECT_CAP = 8_000;

  /**
   * The multi-repo workspace summary + language-parity block moved to {@link RunContextComposer}
   * (god-class split inc-3b); {@code languageConventionHint} moved to {@link RunPipelineSupport}.
   */

  /**
   * The classify → outline → expand planning phases ({@code runClassifierShadow} / {@code
   * runOutlinePhase} / {@code runLargePipeline} / {@code expandEpic}) live in {@link
   * RunPlanningCoordinator} (god-class split inc-4 / inc-4b); the orchestrator drives them via
   * {@code planningCoordinator}.
   */

  /**
   * Customer-safe terminal {@code statusReason} for internal / infrastructure failures. The raw
   * exception (class + message + stack) is logged at each call site for operators; it must NEVER
   * reach {@code statusReason}, which surfaces verbatim in the customer failure email + UI badge.
   * (2026-05-29: a JDK spawn-helper IOException stack trace had been leaking into both.)
   */
  static final String INTERNAL_ERROR_REASON = "INTERNAL_ERROR";
}
