package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.mapOverallToV1Status;
import static com.navneet.executespec.integration.RunPipelineSupport.serializeRepoIds;
import static com.navneet.executespec.integration.RunPipelineSupport.truncate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Classification;
import com.navneet.executespec.agent.OutlinePlannerAgent;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlanStrategy;
import com.navneet.executespec.agent.PlanStrategyCircuitBreaker;
import com.navneet.executespec.agent.PlannerRequest;
import com.navneet.executespec.agent.SpecClassifier;
import com.navneet.executespec.assessment.FeatureAssessmentService;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.config.ManagedAiProperties;
import com.navneet.executespec.billing.cost.RunCostLogRepository;
import com.navneet.executespec.codeintel.RunCodeMapService;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.CostCapValues;
import com.navneet.executespec.cost.CostHistoryService;
import com.navneet.executespec.cost.PlannerPhaseAttribution;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.engine.CodeGenEngine;
import com.navneet.executespec.engine.EngineRequest;
import com.navneet.executespec.engine.EngineResult;
import com.navneet.executespec.engine.VerifyMode;
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.execution.service.GoalContinuationAdvisor;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.PostEditFormatter;
import com.navneet.executespec.reviewer.ReviewerAgent;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProviderAvailabilityMap;
import com.navneet.executespec.router.RunModelOverrides;
import com.navneet.executespec.run.RunContinuationBenchmarkPriorFlag;
import com.navneet.executespec.run.RunContinuationConfirmationFlag;
import com.navneet.executespec.security.ContextSanitizer;
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
 * <p>{@link RunController} routes each {@code POST /api/runs} to this orchestrator (V2 is the only
 * harness; the historical HarnessVersionResolver flag was retired). The {@code submitRun(...)}
 * signature originally mirrored V1's {@code PipelineHarness.submitRun(...)} (since deleted) so the
 * controller's call site needed only an if/else swap during the V1 → V2 cutover.
 *
 * <p>Writes to V1's {@code pipeline_run} and {@code agent_task} tables so the existing UI keeps
 * working unchanged — {@code GET /api/runs/{id}} returns V2 runs as V1-shaped JSON. The only
 * distinguishing field is {@code harnessVersion = "v2"}.
 *
 * <p>The V2 path now owns the complete run lifecycle on top of those compatibility tables,
 * including cost attribution, code/evidence views, validation, and SCM handoff through their
 * dedicated collaborators.
 *
 * <p><b>Compatibility notes</b>:
 *
 * <ul>
 *   <li>Production runs persist a non-blocking {@code AWAITING_APPROVAL} plan gate; prototype and
 *       explicitly integration-triggered runs may bypass it.
 *   <li>Batch execution remains a separate operator path; realtime execution is the default.
 *   <li>Preview, deploy, GitHub push, and billing are integrated through their dedicated services.
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RunOrchestrator implements RunControlPort {
  private static final int ENTERPRISE_INJECT_CAP = 8_000;

  /** Recovery policy: persisted client intent may only resume through the local-hands transport. */
  static boolean shouldResumeLocalHands(boolean persistedIntent, boolean localHandsEnabled) {
    return persistedIntent && localHandsEnabled;
  }

  /**
   * Back-compat classification for {@link PlannerRequest}. Live planning strategy flows through
   * {@link com.navneet.executespec.agent.SmartClassifierAgent} and {@link PlanStrategy}; this enum
   * remains only for legacy validation/task-cap paths that still read {@code
   * PlannerRequest.classification}.
   */
  private static final Classification DEFAULT_CLASSIFICATION = Classification.MEDIUM;

  private final PipelineRunRepository runRepo;
  private final AgentTaskRepository taskRepo;

  /** RR-034 — bounded final-commit of the run workspace (extracted from this god class). */
  private final WorkspaceGitCommitService workspaceGitCommitService;

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
  private final ContextSanitizer contextSanitizer;
  private final AgentProperties agentProps;
  private final RunCostTracker costTracker;
  private final CostHistoryService costHistoryService;

  /** Prices a planned run and persists its estimate, credits and hard spend ceiling. */
  private final RunCostEstimatePersister costEstimatePersister;

  private final com.navneet.executespec.cost.RunTokenAttributor tokenAttributor;
  private final RunCostLogRepository runCostLogRepo;
  private final WorkspaceInitService workspaceInit;
  private final StandaloneExistingWorkspaceAdmission standaloneExistingWorkspaceAdmission;
  private final SseBroadcaster broadcaster;
  private final RunPersistenceWriter persistenceWriter;
  private final RunPlanGateCoordinator planGateCoordinator;
  private final RunContextComposer contextComposer;
  private final RunPlanningCoordinator planningCoordinator;
  private final RunStandardsEnforcer standardsEnforcer;
  private final RunInvariantEnforcer invariantEnforcer;
  private final RunValidationKickoff validationKickoff;
  private final RunResourceCleaner resourceCleaner;
  private final TestGenOrchestrator testGenOrchestrator;
  private final BoundedTestGenKickoff boundedTestGenKickoff;
  private final PerkyMessenger perky;
  private final ModelResolver modelResolver;
  private final AvailableProviderResolver availableProviderResolver;
  private final LlmExecutionPolicyFactory llmExecutionPolicyFactory;
  private final RunLlmOperationLifecycleService runOperationLifecycle;
  private final OrgModelOverrideService orgModelOverrideService;
  private final ModelRegistry modelRegistry;
  private final ManagedAiProperties managedAiProperties;
  private final com.navneet.executespec.smoketest.CliSmokeRunner cliSmokeRunner;
  private final ProjectRepoService projectRepoService;
  private final FeatureAssessmentService featureAssessmentService;
  private final RunCodeMapService runCodeMapService;
  private final AppUserRepository appUserRepository;
  private final com.navneet.executespec.agent.ApiKeyResolver apiKeyResolver;
  private final com.navneet.executespec.memory.ProjectMemoryProvider projectMemoryProvider;
  private final com.navneet.executespec.codeintel.ProjectBriefService projectBriefService;
  private final com.navneet.executespec.codeintel.StalenessService stalenessService;
  private final PrePlannerArchitectureRefreshService prePlannerArchitectureRefreshService;
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
   * {@code /standards/report} endpoint is no longer always-empty; {@link RunBreakingChangeDetector}
   * classifies touched contracts and surfaces breaking changes as report-only run issues. Both
   * project-gated + flag-gated ({@code standards.enforcement.enabled} / {@code
   * contracts.breaking-change.enabled}, default OFF) + non-fatal.
   *
   * <p>RR-819 — that post-run detector is the SECOND, tighten-only gate. {@link
   * com.navneet.executespec.contracts.gate.PlanContractGateService} is the one that can actually
   * refuse: it fires at the plan gate, before worker spend or publication.
   */
  private final RunBreakingChangeDetector runBreakingChangeDetector;

  private final com.navneet.executespec.contracts.gate.PlanContractGateService planContractGate;

  private final RunPublicationFence publicationFence;

  /** Deterministic pre/post Spring HTTP compatibility gate for brownfield runs. */
  private final com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard
      brownfieldHttpContractGuard;

  /** Reversible workspace and durable-ledger lifecycle for automatic correction attempts. */
  private final AutomaticCorrectionLifecycle automaticCorrectionLifecycle;

  private final CorrectionValidationAdmission correctionValidationAdmission;

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

  /** Submit-time handoff used to stamp local intent before the async pipeline can start. */
  private final ThreadLocal<Boolean> submitLocalExecutionIntent = new ThreadLocal<>();

  /** Submit-time controls stamped on the initial run row before asynchronous work can start. */
  public record SubmitPolicy(
      Double userCostCap,
      boolean skipAutoPush,
      String tenantOrgId,
      String billingOrgId,
      String triggeredByFinding,
      String checkoutBranch,
      String publicationRepoFullName,
      Integer publicationPrNumber,
      String publicationHeadSha) {
    public static final SubmitPolicy NONE =
        new SubmitPolicy(null, false, null, null, null, null, null, null, null);
  }

  private final ThreadLocal<SubmitPolicy> submitPolicyIntent = new ThreadLocal<>();

  public PipelineRun withReservedRunId(
      String reservedRunId, java.util.function.Supplier<PipelineRun> submission) {
    return reservedPipelineRunStore.withReservedRunId(reservedRunId, submission);
  }

  /**
   * Source-workspace lineage for a follow-up BUILD run. Like the other submit intents above, this
   * is consumed while the initial row is built, before the async pipeline can observe it.
   */
  private final ThreadLocal<String> submitSourceRunIntent = new ThreadLocal<>();

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

  /** Owns the in-flight pipeline futures and their durable execution-owner heartbeat fence. */
  private final ActiveRunRegistry activeRunRegistry;

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
  // The lifecycle service and submit path share one registry so replacement/removal stays fenced.

  @Override
  public void putActiveRun(String runId, Future<?> future) {
    activeRunRegistry.put(runId, future);
  }

  @Override
  public void putExternallyHeartbeatedActiveRun(String runId, Future<?> future) {
    activeRunRegistry.putExternallyHeartbeated(runId, future);
  }

  @Override
  public boolean adoptRuntimeHeartbeat(String runId, Future<?> expectedFuture) {
    return activeRunRegistry.adoptRuntimeHeartbeat(runId, expectedFuture);
  }

  @Override
  public Future<?> removeActiveRun(String runId) {
    return activeRunRegistry.remove(runId);
  }

  @Override
  public boolean removeActiveRun(String runId, Future<?> expectedFuture) {
    return activeRunRegistry.remove(runId, expectedFuture);
  }

  /**
   * Run-orchestration thread pool — extracted to {@link PipelineExecutorPool} (god-class split
   * inc-4b). Injected so the LARGE-pipeline fan-out + the lifecycle resubmits (resume / retry /
   * re-review) share one bean; the sizing, daemon {@code v2-orchestrator-N} threads, and the 30s
   * graceful {@code @PreDestroy} shutdown live there now.
   */
  private final PipelineExecutorPool pool;

  /**
   * RR-241 — the single authority on plan-gate bypass. {@code skipApproval} arriving from any
   * caller is a <em>request</em>, never a grant; it is honoured only when the submitting surface is
   * a server-internal automation surface with its own authorization step.
   */
  private final PlanGateBypassPolicy planGateBypassPolicy;

  /** Assigned-id INSERT boundary used only by durable Execution View attempt reservations. */
  private final ReservedPipelineRunStore reservedPipelineRunStore;

  /** Resolves the durable provider/model route a submitted run advertises. */
  private final RunRouteContractResolver routeContractResolver;

  /**
   * Submit a new run through V2. Returns a freshly-persisted {@link PipelineRun} with status {@code
   * PLANNING}; the async pipeline thread continues in the background.
   *
   * <p>Signature originally mirrored V1's {@code PipelineHarness.submitRun(...)} (since deleted) so
   * the controller could delegate with zero argument transformation.
   */
  /** Full submit variant that stamps local-hands intent atomically with the run row. */
  public PipelineRun submitRunWithLocalExecution(
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
      String lowComplexityWorkerStartTier,
      boolean localExecutionRequested) {
    return submitRunWithLocalExecution(
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
        lowComplexityWorkerStartTier,
        localExecutionRequested,
        SubmitPolicy.NONE);
  }

  /**
   * Creates a lineage-preserving BUILD iteration and starts the normal asynchronous V2 pipeline.
   *
   * <p>The route, project/repository boundary, execution mode, TDD mode, cost ceiling and
   * publication consent are inherited from the source. The current actor's SCM credentials are
   * used; credentials are never copied from the source row.
   */
  public PipelineRun submitIterationRun(
      PipelineRun source,
      String followUpRequest,
      String actorId,
      String actorOrgId,
      String githubPat,
      String githubOwner) {
    if (source == null || source.getRunId() == null || source.getRunId().isBlank()) {
      throw new IllegalArgumentException("source run is required");
    }
    IterationPolicy inherited = iterationPolicy(source);
    submitSourceRunIntent.set(source.getRunId());
    try {
      return submitRunWithLocalExecution(
          followUpRequest,
          "ITERATION",
          source.getRepoUrl(),
          source.getRepoMode(),
          githubPat,
          githubOwner,
          false,
          false,
          inherited.pinnedModel(),
          inherited.provider(),
          actorId,
          actorOrgId,
          inherited.projectId(),
          inherited.executionMode(),
          inherited.selectedRepoIds(),
          source.getFigmaUrl(),
          inherited.tddMode(),
          null,
          null,
          com.navneet.executespec.state.RunMode.BUILD,
          null,
          null,
          null,
          false,
          IterationSubmitPolicyFactory.from(inherited, actorOrgId));
    } finally {
      submitSourceRunIntent.remove();
    }
  }

  static String iterationPinnedModel(PipelineRun source) {
    return source != null && source.isRoutePinned() ? source.getRequestedModel() : null;
  }

  static IterationPolicy iterationPolicy(PipelineRun source) {
    if (source == null) throw new IllegalArgumentException("source run is required");
    return new IterationPolicy(
        firstNonBlank(source.getRequestedProvider(), source.getAiProvider()),
        iterationPinnedModel(source),
        source.getProjectId(),
        RunPipelineSupport.deserializeRepoIds(source.getSelectedRepoIds()),
        normalizeIterationExecutionMode(source.getExecutionMode()),
        source.isTddMode(),
        source.getUserCostCap(),
        source.isSkipAutoPush(),
        source.getOrgId(),
        source.getCheckoutBranch());
  }

  record IterationPolicy(
      String provider,
      String pinnedModel,
      String projectId,
      List<UUID> selectedRepoIds,
      String executionMode,
      boolean tddMode,
      Double userCostCap,
      boolean skipAutoPush,
      String tenantOrgId,
      String checkoutBranch) {}

  static String normalizeIterationExecutionMode(String executionMode) {
    return "PROTOTYPE".equalsIgnoreCase(executionMode) ? "PROTOTYPE" : "PRODUCTION";
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary != null && !primary.isBlank() ? primary : fallback;
  }

  /**
   * RR-233 / RR-234 / RR-236 — the submit entry point that stamps submit-time policy atomically
   * with run creation.
   *
   * <p>Callers that carry a customer cost cap, a "do not auto-publish" consent, or a host tenant
   * distinct from the actor's payer org MUST use this overload. Persisting any of those after
   * {@code submitRun} returns is a race against the pipeline thread that consumes them.
   */
  public PipelineRun submitRunWithLocalExecution(
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
      String lowComplexityWorkerStartTier,
      boolean localExecutionRequested,
      SubmitPolicy submitPolicy) {
    submitLocalExecutionIntent.set(localExecutionRequested);
    submitPolicyIntent.set(submitPolicy == null ? SubmitPolicy.NONE : submitPolicy);
    try {
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
          lowComplexityWorkerStartTier);
    } finally {
      submitLocalExecutionIntent.remove();
      submitPolicyIntent.remove();
    }
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
    String runId = reservedPipelineRunStore.currentOrRandomRunId();
    SubmitPolicy policy =
        submitPolicyIntent.get() == null ? SubmitPolicy.NONE : submitPolicyIntent.get();
    RunBranchPublicationIntent.Resolved branchIntent =
        RunBranchPublicationIntent.resolve(branchOverride, policy);
    final String branchOverrideFinal = branchIntent.publicationBranch();
    final String checkoutBranchFinal = branchIntent.checkoutBranch();
    String provider = contextComposer.normalizeProvider(aiProvider, projectId, runId);

    // RR-241 — the plan gate is the cost/governance checkpoint before provider spend and
    // publication. A caller-supplied skipApproval bit is a request, not a grant: it survives only
    // for a server-internal automation surface that carries its own authorization
    // (PlanGateBypassPolicy). Everything else — including every ordinary customer submit and every
    // fork — falls back to the human gate. Enforced HERE rather than at each of the ~15 submit
    // overloads/entry points so no future caller can reintroduce the bypass.
    if (skipApproval && !planGateBypassPolicy.bypassAuthorized(source)) {
      log.warn(
          "RunOrchestrator run={} source={} requested skipApproval without automation authority —"
              + " plan gate ENFORCED",
          runId,
          source);
      skipApproval = false;
    }

    // The durable route contract (managed-tier lock > ENTERPRISE pin > YAML tier defaults) is
    // resolved by RunRouteContractResolver so the persisted planner/worker/reviewer columns stay
    // honest about what routing will actually call.
    RunRouteContractResolver.RouteContract route =
        routeContractResolver.resolve(runId, provider, modelName, runMode, createdByUserId);
    provider = route.provider();
    String canonicalPin = route.canonicalPin();
    String model = route.workerModel();
    String plannerDbModel = route.plannerModel();
    String reviewerDbModel = route.reviewerModel();

    // RR-236 — a run's tenant is the org that OWNS the work, which for a project-scoped run is the
    // authorized host project's org, not the actor's home org. An external collaborator's run used
    // to carry the host projectId with their personal orgId, splitting the run away from the tenant
    // whose project, quota and audit trail it actually belongs to. The actor stays on
    // createdByUserId.
    RunSubmitAuthority submitAuthority = RunSubmitAuthority.resolve(policy, orgId, runId);

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
            .branchOverride(branchOverrideFinal)
            .checkoutBranch(checkoutBranchFinal)
            .publicationRepoFullName(policy.publicationRepoFullName())
            .publicationPrNumber(policy.publicationPrNumber())
            .publicationHeadSha(policy.publicationHeadSha())
            .aiProvider(provider.toUpperCase())
            .requestedProvider(provider.toUpperCase())
            .requestedModel(canonicalPin)
            .routingMode(canonicalPin == null ? "PROVIDER_PREFERRED" : "EXACT_LOCK")
            .routePinned(canonicalPin != null)
            .plannerModel(plannerDbModel)
            .workerModel(model)
            .reviewerModel(reviewerDbModel)
            .skipApproval(skipApproval)
            .autoMerge(autoMerge)
            .executionMode(executionMode != null ? executionMode : "PRODUCTION")
            .approvedExecutionMode("REALTIME")
            .createdByUserId(createdByUserId)
            .orgId(submitAuthority.tenantOrgId())
            .billingOrgId(submitAuthority.billingOrgId())
            .projectId(projectId)
            // V359 — finding automation must stamp its immutable delivery identity in the initial
            // INSERT. Writing it after submitRun returns leaves a crash/race window in which a
            // duplicate webhook cannot discover the already-created PATCH run.
            .triggeredByFinding(policy.triggeredByFinding())
            // RR-233 — expose the hard ceiling before the planner can persist its estimate; a
            // post-submit write races a fast planner and clamps against the wrong cap.
            .userCostCap(policy.userCostCap())
            // A customer ceiling applies to the whole run, including classification and planning.
            // The plan-derived cap is unavailable here, so seed hard_cap_usd with the voluntary
            // ceiling; persistEstimate may only tighten it. Otherwise an early planning failure
            // leaves dispatch and the Cost tab uncapped despite a durable user_cost_cap_usd.
            .hardCapUsd(CostCapValues.requireValidOptional(policy.userCostCap()))
            // RR-234 — publication consent is durable; losing it on crash turns "do not publish"
            // into "publish".
            .skipAutoPush(policy.skipAutoPush())
            .queuePosition(0)
            .startedAt(Instant.now())
            .executingInstance(activeRunRegistry.executionOwner())
            .executionHeartbeat(Instant.now())
            .totalInputTokens(0)
            .totalOutputTokens(0)
            .figmaUrl(figmaUrl)
            .tddMode(tddMode)
            .selectedRepoIds(serializeRepoIds(selectedRepoIds))
            .sourceRunId(submitSourceRunIntent.get())
            .localExecutionRequested(Boolean.TRUE.equals(submitLocalExecutionIntent.get()))
            .build();
    ReservedPipelineRunStore.InitialRunPersistence initialPersistence =
        ReservedPipelineRunStore.persistInitial(
            reservedPipelineRunStore, runRepo, run, reservedPipelineRunStore.hasReservation());
    if (!initialPersistence.created()) {
      log.info(
          "RunOrchestrator reserved run identity {} already exists; returning canonical row",
          runId);
      return initialPersistence.run();
    }
    run = initialPersistence.run();

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
    final String pinnedModelFinal = canonicalPin;
    AtomicReference<Future<?>> registeredFuture = new AtomicReference<>();
    FutureTask<Void> future =
        new FutureTask<>(
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
                removeActiveRun(runIdFinal, registeredFuture.get());
              }
              return null;
            });
    registeredFuture.set(future);
    putActiveRun(runIdFinal, future);
    try {
      pool.executor().execute(future);
    } catch (RuntimeException dispatchFailure) {
      removeActiveRun(runIdFinal, future);
      throw dispatchFailure;
    }
    return run;
  }

  static String normalizeBranchOverride(String branchOverride) {
    return RunBranchPublicationIntent.normalize(branchOverride);
  }

  static String resolveCheckoutBranch(PipelineRun run, String legacyBranchOverride) {
    return RunBranchPublicationIntent.checkoutBranch(run, legacyBranchOverride);
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
        prReviewComments,
        pinnedModel,
        lowComplexityWorkerStartTierOverride,
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
      String pinnedModel,
      String lowComplexityWorkerStartTierOverride,
      PlanGateContinuation approvedPlanContinuation) {
    Instant startedAt = Instant.now();
    Path workspace = null;
    String upperProvider = provider == null ? "UNKNOWN" : provider.toUpperCase();
    // P3 local-hands (RL-2026-06-014) — declared before the try so the finally can unregister the
    // remote-tool session on every terminal/cancel path. Assigned once below (after the workspace
    // exists); stays false unless worker.local-hands.enabled AND the submit requested LOCAL
    // execution.
    boolean localRun = false;
    boolean asyncTestGenOwnsKeyContext = false;
    RunLlmOperationLifecycleService.PhaseLease op = null;
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
        // BYOK boundary — fail CLOSED for pure-BYOK runs (deep-dive §7 #2, 2026-07-17). For a
        // HYBRID
        // run this is genuinely non-fatal (platform fallback is intended), but for an OWN_KEYS run
        // a
        // resolution failure must terminate the run before any provider spend rather than silently
        // billing the platform's credentials.
        boolean ownKeys = false;
        try {
          PipelineRun pre = runRepo.findById(runId).orElse(null);
          ownKeys = pre != null && apiKeyResolver.isOwnKeysMode(pre.getCreatedByUserId());
        } catch (Exception ignore) {
          // If we cannot even determine the mode, stay permissive (HYBRID semantics).
        }
        if (ownKeys) {
          log.error(
              "V2 run={} OWN_KEYS BYOK resolution failed; failing run closed (no platform"
                  + " fallback): {}",
              runId,
              keyEx.getMessage());
          terminate(
              runId,
              "FAILED",
              "BYOK_CONTEXT_UNAVAILABLE",
              "BYOK key resolution failed for an OWN_KEYS run; refused to fall back to platform"
                  + " keys.",
              startedAt);
          return;
        }
        log.warn(
            "V2 run={} BYOK resolution failed; using platform keys (HYBRID): {}",
            runId,
            keyEx.getMessage());
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
        String durablePinnedModel =
            ovProbe != null && ovProbe.isRoutePinned() ? ovProbe.getRequestedModel() : pinnedModel;

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
        boolean enterprise =
            (ovProbe != null && ovProbe.isRoutePinned())
                || "ENTERPRISE".equalsIgnoreCase(ownerTier);

        LlmProvider pinProvider = null;
        String validatedPin = null;
        if (enterprise && durablePinnedModel != null && !durablePinnedModel.isBlank()) {
          try {
            pinProvider = LlmProvider.valueOf(provider.toUpperCase());
            String canonical = modelRegistry.canonicalize(durablePinnedModel.trim());
            String owner = modelRegistry.providerFor(canonical);
            if (owner == null
                || RunRouteContractResolver.canRoutePinnedModel(pinProvider, canonical, owner)) {
              validatedPin = canonical;
            } else {
              throw new IllegalStateException(
                  "Persisted exact model '"
                      + durablePinnedModel
                      + "' cannot be routed via "
                      + pinProvider.name());
            }
          } catch (IllegalArgumentException badProvider) {
            throw new IllegalStateException(
                "Persisted exact route has unrecognised provider '" + provider + "'", badProvider);
          }
        } else if (durablePinnedModel != null && !durablePinnedModel.isBlank()) {
          log.debug(
              "V2 run={} per-run model pin '{}' ignored — owner tier {} is not ENTERPRISE",
              runId,
              durablePinnedModel,
              ownerTier);
        }

        if (validatedPin != null) {
          routePinned = true;
        }
        RunModelOverrides.bind(
            ManagedTierRouteLock.apply(
                managedAiProperties,
                modelRegistry,
                ownerTier,
                orgOverrides,
                pinProvider,
                validatedPin));
      } catch (Exception ovEx) {
        RunModelOverrides.clear();
        boolean exactRoute = runRepo.findById(runId).map(PipelineRun::isRoutePinned).orElse(false);
        if (exactRoute) {
          throw new IllegalStateException(
              "Exact run route binding failed for run " + runId + ": " + ovEx.getMessage(), ovEx);
        }
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
      String sourceRunId =
          runRepo
              .findById(runId)
              .map(PipelineRun::getSourceRunId)
              .filter(s -> !s.isBlank())
              .orElse(null);
      // P3 local-hands (RL-2026-06-014): choose LOCAL vs remote execution once, here. When on, the
      // worker's tool calls are proxied to the caller's machine (clone / smoke / reviewer / push
      // are
      // all skipped below). Flag OFF ⇒ false ⇒ every branch below is inert and prod is
      // byte-identical.
      boolean localHandsEnabled = featureFlagService.isEnabled("worker.local-hands.enabled");
      boolean persistedLocalIntent =
          runRepo.findById(runId).map(PipelineRun::isLocalExecutionRequested).orElse(false);
      boolean continuationLocalIntent =
          approvedPlanContinuation != null && approvedPlanContinuation.localRun();
      if ((persistedLocalIntent || continuationLocalIntent) && !localHandsEnabled) {
        log.error(
            "V2 run={} local-hands intent cannot be honoured: feature flag is disabled", runId);
        terminate(runId, "FAILED", "LOCAL_HANDS_DISABLED", startedAt);
        return;
      }
      localRun =
          approvedPlanContinuation != null
              ? approvedPlanContinuation.localRun()
              : localHandsEnabled && (runLocalExecutionFlag.consume(runId) || persistedLocalIntent);
      boolean requireContinuationConfirmation =
          approvedPlanContinuation != null
              ? approvedPlanContinuation.requireContinuationConfirmation()
              : runContinuationConfirmationFlag.consume(runId);
      boolean requireContinuationBenchmarkPrior =
          approvedPlanContinuation != null
              ? approvedPlanContinuation.requireContinuationBenchmarkPrior()
              : runContinuationBenchmarkPriorFlag
                  .consume(runId)
                  .orElse(postReviewRequireBenchmarkPriorForAutomaticContinuation);

      if (approvedPlanContinuation == null) {
        PipelineRun workspaceRun = runRepo.findById(runId).orElse(null);
        String cloneBranch = resolveCheckoutBranch(workspaceRun, branchOverride);
        StandaloneExistingWorkspaceAdmission.Admission admission =
            standaloneExistingWorkspaceAdmission.prepareInitial(
                runId,
                projectId,
                sourceRunId,
                localRun,
                workspaceRun == null ? null : workspaceRun.getRepoMode(),
                workspaceRun == null ? null : workspaceRun.getRepoUrl(),
                githubPat,
                cloneBranch);
        if (!admission.admitted()) {
          terminate(runId, "FAILED", "WORKSPACE_PREFLIGHT_FAILED:" + admission.reason(), startedAt);
          return;
        }
      }

      // Brownfield admission — clone the exact selected boundary and prove that every requested
      // repository contains a real source inventory before the first model call. Local-hands runs
      // are admitted by the client-side working-tree handshake instead.
      if (approvedPlanContinuation == null && !localRun && projectId != null) {
        try {
          List<ProjectRepo> repos =
              projectRepoService.resolveForRunStrict(projectId, selectedRepoIds);
          if (repos.isEmpty()) {
            log.info("V2 run={} project has no linked brownfield repositories", runId);
          } else {
            PipelineRun workspaceRun = runRepo.findById(runId).orElse(null);
            String cloneBranch = resolveCheckoutBranch(workspaceRun, branchOverride);
            List<WorkspaceInitService.RepoToClone> toClone =
                repos.stream()
                    .map(
                        r ->
                            new WorkspaceInitService.RepoToClone(
                                r.getGithubRepoFullName(),
                                "https://github.com/" + r.getGithubRepoFullName(),
                                r.getId(),
                                cloneBranch != null ? cloneBranch : r.getDefaultBranch()))
                    .toList();
            if (sourceRunId == null) {
              workspaceInit.cloneMultipleRepos(runId, toClone, githubPat);
            }
            WorkspaceInitService.WorkspaceAdmission admission =
                workspaceInit.verifyMultipleRepos(runId, toClone);
            if (!admission.admitted()) {
              terminate(
                  runId, "FAILED", "WORKSPACE_PREFLIGHT_FAILED:" + admission.reason(), startedAt);
              return;
            }
            log.info("V2 run={} cloned {} project repos", runId, toClone.size());
          }
        } catch (Exception cloneEx) {
          log.error("V2 run={} multi-repo clone failed: {}", runId, cloneEx.getMessage(), cloneEx);
          terminate(
              runId,
              "FAILED",
              "WORKSPACE_PREFLIGHT_FAILED:repository clone or inventory failed",
              startedAt);
          return;
        }
      }

      // Existing-PR authority includes the exact head observed during synchronous admission. A
      // branch name alone is insufficient: it may advance between preflight and async clone. Prove
      // the actual cloned HEAD before classifier/planner spend, including crash-recovered clones.
      if (approvedPlanContinuation == null && !localRun) {
        PipelineRun publicationRun = runRepo.findById(runId).orElse(null);
        if (publicationRun != null && publicationRun.getPublicationHeadSha() != null) {
          try {
            workspaceInit.requirePublicationHead(
                runId,
                publicationRun.getPublicationRepoFullName(),
                publicationRun.getPublicationHeadSha());
          } catch (RuntimeException staleHead) {
            log.warn(
                "V2 run={} existing-PR head admission failed: {}", runId, staleHead.getMessage());
            terminate(
                runId, "FAILED", "WORKSPACE_PREFLIGHT_FAILED:pull-request head changed", startedAt);
            return;
          }
        }
      }

      var llmExecutionReference = llmExecutionPolicyFactory.forRun(runId);
      ExecutionContext baseRootCtx =
          new ExecutionContext(runId, "__root__", workspace.toRealPath(), llmExecutionReference);
      persistenceWriter.setWorkspacePath(runId, workspace.toString());

      // PATCH owns its prepared workspace and dispatches one governed CodeGenEngine loop.
      if (approvedPlanContinuation == null) {
        PipelineRun modeProbe = runRepo.findById(runId).orElse(null);
        if (modeProbe != null
            && com.navneet.executespec.state.RunMode.isPatch(modeProbe.getRunMode())) {
          op = runOperationLifecycle.claimPatch(baseRootCtx, runId, provider, model);
          ExecutionContext patchRootCtx = op.context();
          runPatchPipeline(runId, provider, modeProbe, userRequest, patchRootCtx, startedAt);
          op = runOperationLifecycle.closeAndClear(runId, op);
          return;
        }
      }

      if (approvedPlanContinuation == null) {
        op = runOperationLifecycle.claimPlanning(baseRootCtx, runId, provider, model);
      }
      ExecutionContext rootCtx = op == null ? baseRootCtx : op.context();
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

      Plan plan;
      String designBlock;
      String standardsBlock =
          RunStandardsPromptResolver.resolve(
              featureFlagService,
              standardsEnforcer,
              runId,
              projectId,
              rootCtx.workspaceRoot(),
              selectedRepoIds);
      RunCostTracker.Snapshot afterPlan;
      // RR-1561 — PLANNER-delta baseline, taken the moment the smart classifier returns.
      RunCostTracker.Snapshot afterClassifier = null;
      if (approvedPlanContinuation == null) {

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
              broadcaster.emit(
                  runId, RunEvent.log(runId, "assessment", "Feature assessment ready"));
            }
          } catch (Exception assessEx) {
            log.warn("V2 run={} assessment failed: {}", runId, assessEx.getMessage());
          }
        }

        perky.start(runId, "PLANNING");
        PlannerRequest plannerReq;
        try {
          Classification autoClassification = SpecClassifier.classify(userRequest);
          log.info(
              "V2 run={} spec classified as {} (len={})",
              runId,
              autoClassification,
              userRequest == null ? 0 : userRequest.length());

          // Smart classifier verdict drives downstream routing: SMALL/MEDIUM use the outline
          // planner
          // in full-plan mode; LARGE/LEGACY use skeleton expansion; SINGLE_SHOT is synthesized
          // locally. Cost flows through RunCostTracker so it auto-aggregates into the run total.
          PlanStrategy planStrategy =
              planningCoordinator.runClassifierShadow(
                  runId, runRow, userRequest, projectId, selectedRepoIds, provider, rootCtx);
          afterClassifier = costTracker.snapshot(runId); // RR-1561 pre-planner boundary

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
              prePlannerArchitectureRefreshService.refreshIfStale(
                  runId,
                  projectId,
                  runOwnerId,
                  runUserPat,
                  LlmProvider.valueOf(provider.toUpperCase(Locale.ROOT)));
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
          String multiRepoSummary =
              contextComposer.buildMultiRepoSummary(projectId, selectedRepoIds);
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
                    "V2 run={} injected architecture into planner ({} chars)",
                    runId,
                    arch.length());
              }
            } catch (Exception e) {
              log.warn(
                  "V2 run={} architecture planner injection skipped: {}", runId, e.getMessage());
            }
          }
          // Enterprise-knowledge injection — feed cross-repo contracts + accepted ADRs into the
          // planner so it plans changes that respect them. Flag-gated (`enterprise.inject.enabled`,
          // default OFF), bounded (~8000 chars each), best-effort per block. No project → both
          // empty
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
            if (adrInjectionService.injectionEnabled()) {
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
          }
          plannerReq =
              PlannerRequest.builder()
                  .spec(userRequest)
                  .classification(autoClassification)
                  .planStrategy(planStrategy)
                  .provider(provider)
                  .model(model)
                  .context(rootCtx)
                  .workspaceSummary(summary)
                  .projectMemory(projectMemory)
                  .projectBrief(projectBrief)
                  .referenceDocs(referenceDocs)
                  .contracts(contractsBlock)
                  .projectAdrs(adrsBlock)
                  .engineeringStandards(standardsBlock)
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
              planStrategyBreaker.recordFailure(effective, outlineEx);
              // A multi-task request cannot safely degrade to one LOW synthetic task. That path
              // discards structured requirements and can execute a materially different product
              // after a provider/auth failure. Fail before the plan gate and preserve the raw
              // exception only in operator logs.
              log.error(
                  "V2 run={} outline planner ({}) failed — refusing scope collapse",
                  runId,
                  effective,
                  outlineEx);
              failPlannerUnavailable(runId, startedAt, "produce a safe executable plan", outlineEx);
              return;
            }
          } else if (effective == PlanStrategy.LARGE) {
            try {
              plan =
                  planningCoordinator.runLargePipeline(runId, plannerReq, rootCtx, legacyRecursion);
              planStrategyBreaker.recordSuccess(PlanStrategy.LARGE);
            } catch (Exception largeEx) {
              planStrategyBreaker.recordFailure(PlanStrategy.LARGE, largeEx);
              log.error(
                  "V2 run={} LARGE planner pipeline failed — refusing scope collapse",
                  runId,
                  largeEx);
              failPlannerUnavailable(runId, startedAt, "produce a safe executable plan", largeEx);
              return;
            }
          } else if (effective == PlanStrategy.SINGLE_SHOT) {
            // v2.6.2 slice 5b — heuristic-fast-path classification.
            // No LLM planner call; synthesise a one-task plan from the
            // spec verbatim.
            plan = outlinePlanner.synthesizeSingleShot(plannerReq);
          } else {
            log.error(
                "V2 run={} no usable planning strategy — refusing synthetic execution", runId);
            failPlannerUnavailable(runId, startedAt, "select a safe executable strategy", null);
            return;
          }
        } catch (Exception e) {
          log.error("RunOrchestrator run={} planner failed", runId, e);
          terminate(runId, "FAILED", INTERNAL_ERROR_REASON, INTERNAL_ERROR_REASON, startedAt);
          return;
        }
        var stabilizedPlan =
            planningCoordinator.stabilizeExecutablePlanOutcome(
                runId,
                plan,
                plannerReq,
                rootCtx,
                PlannerRecoveryRouteSelector.Policy.runAuthorized(
                    routePinned,
                    requireContinuationConfirmation,
                    requireContinuationBenchmarkPrior));
        if (stabilizedPlan.failure() != null) {
          terminate(
              runId,
              "FAILED",
              stabilizedPlan.statusReason(),
              stabilizedPlan.failureReason(),
              startedAt);
          return;
        }
        plan = stabilizedPlan.plan();
        boolean needsApproval = !skipApproval && "PRODUCTION".equalsIgnoreCase(executionMode);
        var gatePreparation =
            planGateCoordinator.prepare(needsApproval, runId, runRow.getUserRequest(), plan);
        contextComposer.writeAgentContext(workspace, plan);
        persistEstimate(runId, provider, model, plan, lowComplexityWorkerStartTierOverride);
        designBlock = contextComposer.buildDesignBlock(userRequest, plan);
        // Phase 1: book the planner's own delta, not the whole-run snapshot (RR-1561).
        afterPlan = costTracker.snapshot(runId);
        PlannerPhaseAttribution.apply(
            runRepo, tokenAttributor, runId, afterPlan, afterClassifier, upperProvider, model);
        persistenceWriter.raiseUsageFloor(runId, afterPlan);
        // PRODUCTION persists continuation and parks without consuming a pool worker; prototype
        // and integration-triggered runs bypass this gate.
        if (needsApproval) {
          perky.stop(runId);
          // RR-819 — compose the contract gate BEFORE the run parks at the gate, so the approver is
          // shown (and the server durably records) which registered contracts this plan intends to
          // modify and which repositories declare themselves consumers. The post-run classifier
          // below cannot do this job: it runs after the files are already written.
          String contractGateReportDigest =
              planContractGate
                  .composeAndPersist(runId, projectId, plan, selectedRepoIds)
                  .map(
                      com.navneet.executespec.contracts.gate.PlanContractGateService.GateReport
                          ::digest)
                  .orElse(null);
          PlanGateContinuation continuation =
              new PlanGateContinuation(
                  PlanGateContinuation.SCHEMA_VERSION,
                  localRun,
                  requireContinuationConfirmation,
                  requireContinuationBenchmarkPrior,
                  pinnedModel,
                  lowComplexityWorkerStartTierOverride);
          String continuationJson = objectMapper.writeValueAsString(continuation);
          planGateCoordinator.open(
              runId,
              gatePreparation,
              continuationJson,
              continuation.schemaVersion(),
              contractGateReportDigest);
          op = runOperationLifecycle.completeAndClear(op);
          log.info("RunOrchestrator run={} suspended at durable plan gate", runId);
          return;
        }
        op = runOperationLifecycle.completeAndClear(op);
      } else {
        PipelineRun resumed =
            runRepo
                .findById(runId)
                .orElseThrow(() -> new IllegalStateException("Approved run disappeared: " + runId));
        if (resumed.getPlanJson() == null || resumed.getPlanJson().isBlank()) {
          throw new IllegalStateException("Approved run has no persisted plan: " + runId);
        }
        plan = objectMapper.readValue(resumed.getPlanJson(), Plan.class);
        if (plan.tasks() == null || plan.tasks().isEmpty()) {
          throw new IllegalStateException("Approved run has an empty persisted plan: " + runId);
        }
        designBlock = contextComposer.buildDesignBlock(userRequest, plan);
        afterPlan = costTracker.snapshot(runId);
      }
      if (!standardsBlock.isBlank()) {
        designBlock = designBlock + "\n\n" + standardsBlock;
      }

      op = runOperationLifecycle.claimExecution(baseRootCtx, runId, provider, model);
      ExecutionContext executionRootCtx = op.context();
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
                    .runContext(executionRootCtx)
                    .maxIterationsPerTask(null) // per-complexity caps applied in SequentialExecutor
                    .enableSmokeTests(!localRun)
                    .localHandsMode(localRun)
                    .requireConfirmationBeforeExpensiveContinuation(requireContinuationConfirmation)
                    .requireBenchmarkPriorForAutomaticContinuation(
                        requireContinuationBenchmarkPrior)
                    .routePinned(routePinned)
                    .lowComplexityWorkerStartTier(lowComplexityWorkerStartTierOverride)
                    .originalGoal(userRequest)
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
        if (Thread.currentThread().isInterrupted() || pool.isShuttingDown()) {
          log.info("RunOrchestrator run={} shutdown interrupted; preserving WORKING", runId);
          return;
        }
        log.error("RunOrchestrator run={} executor failed", runId, e);
        terminate(runId, "FAILED", INTERNAL_ERROR_REASON, INTERNAL_ERROR_REASON, startedAt);
        return;
      }
      persistenceWriter.applyExecutionResultToTasks(runId, execResult);
      op = runOperationLifecycle.completeAndClear(op);
      // Phase 2 delta: tokens spent by the executor (all worker tasks).
      RunCostTracker.Snapshot afterExec = costTracker.snapshot(runId);
      long workerIn = afterExec.inputTokens() - afterPlan.inputTokens();
      long workerOut = afterExec.outputTokens() - afterPlan.outputTokens();
      tokenAttributor.attributeWorker(runId, execResult, workerIn, workerOut, upperProvider, model);

      if (execResult.overall() == ExecutionResult.Overall.ABORTED) {
        StatusAndReason aborted = resolveTerminalStatus(execResult, null);
        terminate(
            runId,
            aborted.status(),
            aborted.reason(),
            "execution stopped: " + aborted.reason(),
            startedAt);
        return;
      }

      brownfieldHttpContractGuard.assertCompatibleIfPresent(runId);

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
        StatusAndReason localTerminal = localExecutionTerminalStatus();
        broadcaster.emit(
            runId,
            RunEvent.log(
                runId,
                null,
                "Changes are ready in your local working tree — server verification was not"
                    + " performed"));
        terminate(
            runId,
            localTerminal.status(),
            localTerminal.reason(),
            "local changes ready; server-side verification and scoring were not performed",
            startedAt);
        return;
      }

      op = runOperationLifecycle.claimReview(baseRootCtx, runId, provider, model);
      ExecutionContext reviewRootCtx = op.context();
      // Run deterministic formatters against the workspace before the LLM reviewer sees it.
      // Cheap (no LLM tokens), deterministic, and absorbs trailing-comma / indent / import-order
      // drift the worker introduces — so the reviewer's input no longer carries 3K tokens of
      // "fix this whitespace" noise per task. Gated by feature flag so ops can disable in
      // environments without prettier / black / gofmt on PATH (the per-tool timeout already
      // makes failure non-fatal, but the flag short-circuits the whole pass when known broken).
      if (featureFlagService.isEnabled("lint.autofix.enabled")) {
        try {
          com.navneet.executespec.lint.LintPass.LintReport lintReport =
              lintPass.run(runId, reviewRootCtx.workspaceRoot());
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
        if (!ReviewerRoutePolicy.sameRoute(provider, model, reviewerRoute)) {
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
                InitialReviewerRequestPolicy.builder()
                    .provider(reviewerRoute.provider())
                    .model(reviewerRoute.model())
                    .context(reviewRootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(skipWiring)
                    .maxIterationsPerPass(reviewerMaxIter)
                    .brownfield(linkedRepoCount > 0)
                    .allowedRepoRoots(
                        RunReviewerScope.repoRoots(projectRepoService, projectId, selectedRepoIds))
                    .approvedNewFiles(RunReviewerScope.approvedNewFiles(plan))
                    .allowBuildManifestCreation(false)
                    .originalGoal(userRequest)
                    .requirements(plan.requirements())
                    .planAdvisories(
                        com.navneet.executespec.agent.TaskExpanderAgent
                            .advisoryPlanContractViolations(plan))
                    .build());
        persistenceWriter.persistReview(runId, reviewResult);
        // Phase 3 delta: tokens spent by reviewer (general + wiring passes).
        RunCostTracker.Snapshot afterReview = costTracker.snapshot(runId);
        long reviewerIn = afterReview.inputTokens() - afterExec.inputTokens();
        long reviewerOut = afterReview.outputTokens() - afterExec.outputTokens();
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
              plan,
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
              reviewRootCtx,
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
      if (brownfieldHttpContractGuard.hasBaseline(runId)) {
        brownfieldHttpContractGuard.assertCompatible(runId);
      }

      // 3b. Enterprise enforcement (project runs only) — make the formerly
      // "decorative" engines real (RL-2026-05-037). StandardsRunner persists
      // standards violations so /standards/report is populated; the breaking-
      // change classifier surfaces touched-contract breaks as report-only run
      // issues. Both project-gated + flag-gated (default OFF) + non-fatal.
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> contractBreakingIssues =
          new java.util.ArrayList<>();
      if (projectId != null) {
        if (featureFlagService.isEnabled("standards.enforcement.enabled")) {
          try {
            var standardsReport =
                standardsEnforcer.runStandardsEnforcement(
                    runId, projectId, reviewRootCtx.workspaceRoot(), selectedRepoIds);
            contractBreakingIssues.addAll(RunStandardsEnforcer.toReviewIssues(standardsReport));
          } catch (Exception e) {
            log.warn(
                "RunOrchestrator run={} standards enforcement unavailable: {}",
                runId,
                e.getClass().getSimpleName());
            contractBreakingIssues.add(
                com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                    .id("SYNTH-STANDARD-UNAVAILABLE-RUN")
                    .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                    .category(com.navneet.executespec.reviewer.model.IssueCategory.CODE_QUALITY)
                    .description("Configured engineering standards could not be verified.")
                    .fixInstruction("Restore standards enforcement and rerun.")
                    .autoFixed(false)
                    .fixSucceeded(false)
                    .build());
          }
        }
        try {
          contractBreakingIssues.addAll(
              invariantEnforcer.enforce(runId, projectId, reviewRootCtx.workspaceRoot()));
        } catch (Exception e) {
          log.warn(
              "RunOrchestrator run={} architecture invariant enforcement unavailable: {}",
              runId,
              e.getClass().getSimpleName());
          contractBreakingIssues.add(
              com.navneet.executespec.reviewer.model.ReviewIssue.builder()
                  .id("SYNTH-INVARIANT-UNAVAILABLE-RUN")
                  .severity(com.navneet.executespec.reviewer.model.IssueSeverity.CRITICAL)
                  .category(
                      com.navneet.executespec.reviewer.model.IssueCategory.CROSS_TASK_MISMATCH)
                  .description("Configured architecture invariants could not be verified.")
                  .fixInstruction("Restore invariant enforcement and rerun.")
                  .autoFixed(false)
                  .fixSucceeded(false)
                  .build());
        }
        if (featureFlagService.isEnabled("contracts.breaking-change.enabled")) {
          try {
            contractBreakingIssues.addAll(
                runBreakingChangeDetector.detect(
                    runId, projectId, reviewRootCtx.workspaceRoot(), selectedRepoIds));
          } catch (Exception e) {
            log.warn(
                "RunOrchestrator run={} breaking-change detection skipped: {}",
                runId,
                e.getMessage());
          }
        }
      }

      // 4. Pre-resolve terminal status BEFORE the validation report.
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
      srDerived = applyStandardsGateStatus(srDerived, contractBreakingIssues);
      srDerived = applyInvariantGateStatus(srDerived, contractBreakingIssues);
      StatusAndReason sr =
          featureFlagService.isEnabled("scoring.v2-verified")
              ? srDerived
              : new StatusAndReason(mapOverallToV1Status(execResult.overall()), srDerived.reason());
      stampRunStatusReason(runId, sr);

      // 5. Generate validation report + kick off test generation
      op = runOperationLifecycle.finishReviewAndClear(op, reviewerErrored);
      Optional<ValidationReport> validationReport =
          validationKickoff.generateValidationReport(
              runId, execResult, reviewResult, plan, contractBreakingIssues, "validation");
      Optional<PostReviewContinuation> postValidationContinuation =
          maybeEmitPostValidationContinuationDecision(
              runId,
              plan,
              execResult,
              validationReport.orElse(null),
              requireContinuationConfirmation,
              requireContinuationBenchmarkPrior,
              routePinned);
      Optional<PostReviewContinuationResult> postValidationContinuationResult =
          maybeActOnPostReviewContinuation(
              runId,
              plan,
              baseRootCtx,
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
        srDerived = applyStandardsGateStatus(srDerived, contractBreakingIssues);
        srDerived = applyInvariantGateStatus(srDerived, contractBreakingIssues);
        sr =
            featureFlagService.isEnabled("scoring.v2-verified")
                ? srDerived
                : new StatusAndReason(
                    mapOverallToV1Status(execResult.overall()), srDerived.reason());
        stampRunStatusReason(runId, sr);
        Optional<ValidationReport> rerunValidationReport =
            validationKickoff.generateValidationReport(
                runId,
                execResult,
                reviewResult,
                plan,
                contractBreakingIssues,
                "post-validation-revalidation");
        ValidationReport effectiveValidationReport =
            rerunValidationReport.orElse(validationReport.orElse(null));
        if (validationReportProvenanceFailed(effectiveValidationReport)) {
          sr = new StatusAndReason("FAILED", "VALIDATION_EVIDENCE_FAILED");
          stampRunStatusReason(runId, sr);
        } else {
          sr = applyFinalVerificationStatus(runId, effectiveValidationReport, sr);
        }
      } else if (validationReportProvenanceFailed(validationReport.orElse(null))) {
        sr = new StatusAndReason("FAILED", "VALIDATION_EVIDENCE_FAILED");
        stampRunStatusReason(runId, sr);
      } else {
        sr = applyFinalVerificationStatus(runId, validationReport.orElse(null), sr);
      }
      // V1's test generation assumes HTTP endpoints — it runs ApiProber against a live preview and
      // synthesizes REST tests. Skip CLI / LIBRARY / STATIC_WEB until a dedicated stdin/stdout
      // harness exists (see docs/runtime-surface-plan).
      if (IndependentTestGenAdmissionPolicy.shouldKickoff(
          sr.status(), plan.runtimeSurface(), reviewResult)) {
        try {
          String planJson = objectMapper.writeValueAsString(plan);
          boundedTestGenKickoff.kickoff(
              runId,
              () -> testGenOrchestrator.runTestGenPipeline(runId, userRequest, planJson),
              () -> keyContextStore.evict(runId));
          asyncTestGenOwnsKeyContext = true;
        } catch (Exception e) {
          log.warn("RunOrchestrator run={} testGen kickoff failed: {}", runId, e.getMessage());
        }
      } else if (plan.runtimeSurface() == Plan.RuntimeSurface.CLI) {
        // Baseline CLI smoke: invoke the inferred entry point with no stdin,
        // record exit code + head of stdout. Full stdin/stdout fixture harness
        // (planner-emitted cliTestCases with expected stdout) is follow-up work.
        var cliResult = cliSmokeRunner.run(runId, workspace);
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
        // RR-826 — no publication without verification. The COMPLETE/PARTIAL status above is not
        // enough: a run can terminalize PARTIAL because the deterministic verification never
        // happened or failed hard (e.g. VALIDATION_REPORT_UNAVAILABLE, FINAL_BUILD_FAILED,
        // REVIEWER_FAILED, *_ENFORCEMENT_UNAVAILABLE). Auto-publishing those means opening a PR
        // while saying "we could not verify this run". AutoPushEligibilityPolicy is a DENYLIST of
        // exactly those unverifiable terminal reasons — measured-but-partial runs (warnings, tests
        // that ran and partially failed, below-bar-but-measured quality) keep auto-pushing. Only
        // the automatic push is gated; the workspace, result, and manual push are untouched.
        if (shouldPush && !AutoPushEligibilityPolicy.allowsAutoPush(finalReason)) {
          shouldPush = false;
          log.info(
              "V2 run={} auto-push skipped — terminal reason {} means the run was not verified"
                  + " (RR-826); manual push remains available",
              runId,
              finalReason);
        }
        // RR-234 — staged-PR-before-push consent, now DURABLE and FAIL-CLOSED.
        //
        // This gate used to read a Redis-only key whose own contract said a JVM crash loses the
        // intent and the next attempt resumes default auto-push. That failure mode silently
        // converts a customer's "do not publish this" into publishing it — the one direction the
        // gate must never fail in. The consent is now a column stamped atomically with run
        // creation (V323); the Redis key is still consumed for runs submitted before this change
        // and for a one-shot manual override.
        //
        // If the run row cannot be read at all we do NOT push: without the row we cannot prove
        // consent was given, and publication is irreversible.
        if (shouldPush) {
          PipelineRun policyRow = null;
          try {
            policyRow = runRepo.findById(runId).orElse(null);
          } catch (Exception policyEx) {
            log.error(
                "V2 run={} could not load publication policy; refusing auto-push: {}",
                runId,
                policyEx.getMessage());
          }
          boolean legacyRedisConsent = runSkipPushFlag.consume(runId);
          if (policyRow == null) {
            shouldPush = false;
            log.warn(
                "V2 run={} auto-push skipped — publication policy unreadable (fail-closed)", runId);
          } else if (policyRow.isSkipAutoPush() || legacyRedisConsent) {
            shouldPush = false;
            log.info("V2 run={} auto-push skipped (customer asked not to publish)", runId);
          }
        }
        if (shouldPush) {
          try {
            PipelineRun fresh = runRepo.findById(runId).orElse(null);
            // RR-1689 — publication authority is bound to the still-current solution attempt.
            if (fresh != null && publicationFence.mayPublish(fresh)) {
              com.navneet.executespec.integration.scm.ScmPushService.PushResult pushResult =
                  scmPushService.push(fresh);
              if (pushResult.pushed()) {
                log.info(
                    "V2 run={} auto-pushed to SCM (projectScoped={})", runId, projectId != null);
              } else {
                log.warn(
                    "V2 run={} auto-push did not publish status={} code={} gate={}",
                    runId,
                    pushResult.status(),
                    pushResult.code(),
                    pushResult.blockingGate());
              }
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
      if (Thread.currentThread().isInterrupted() || pool.isShuttingDown()) {
        log.info("RunOrchestrator run={} shutdown unwound; preserving resumable state", runId);
        return;
      }
      log.error("RunOrchestrator run={} unexpected failure", runId, unexpected);
      var failure = RunFailureDiagnostic.from(unexpected, INTERNAL_ERROR_REASON);
      terminate(runId, "FAILED", failure.code(), failure.reason(), startedAt);
    } finally {
      runOperationLifecycle.closeQuietly(runId, op);
      perky.stop(runId);
      // F3 — drop the run-scoped model-override binding so it never leaks to the
      // next run that reuses this pooled pipeline thread.
      RunModelOverrides.clear();
      // V165 BYOK fix — drop the cached key context. Caffeine's TTL
      // would clean up eventually, but explicit eviction keeps memory
      // usage tight on long-lived JVMs and prevents a stale entry
      // from outlasting a re-submission of the same runId.
      if (!asyncTestGenOwnsKeyContext) {
        try {
          keyContextStore.evict(runId);
        } catch (Exception ignore) {
          /* best-effort */
        }
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

    // OWN_KEYS runs pay the provider directly; HYBRID and SYSTEM remain platform-capped.
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
                  // RR-1566 — bound the CUSTOMER PATCH path with a wall clock + absolute deadline.
                  .budget(RunPipelineSupport.patchEngineBudget(run.getSourceType()))
                  .runId(runId)
                  .taskId(rootCtx.taskId())
                  .context(rootCtx)
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
    String terminalStatus;
    String terminalReason;
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

    // RR-1567 — a finding PATCH that ended STOPPED_WITHOUT_COMPLETE never reached the
    // HARD_GATE test-verification check, so it must not wear a COMPLETE label. Demote it to PARTIAL
    // with an explicit unverified reason; the auto-push guard below independently refuses to
    // publish
    // it via RunPipelineSupport.patchResultPublishable.
    if (RunPipelineSupport.patchStoppedUnverified(result.outcome(), run.getSourceType())) {
      terminalStatus = "PARTIAL";
      terminalReason = RunPipelineSupport.PATCH_STOPPED_UNVERIFIED_REASON;
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
        && run.getSourceType().startsWith("finding:")
        // RR-1567 — publication reads the verification gate's own evidence: only a proven
        // completion
        // may be pushed to a customer PR head branch. A STOPPED (never-verified) worker is refused
        // here, so the gate can no longer be bypassed by simply never calling completeTask.
        && RunPipelineSupport.patchResultPublishable(result.outcome(), run.getSourceType())) {
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
        // RR-1689 — same fence on the PATCH path.
        if (fresh != null && publicationFence.mayPublish(fresh)) {
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
  /** RR-034 — bounded, kill-on-timeout final commit delegated out of this orchestrator. */
  private void commitWorkspaceOutputs(String runId, Path workspace) {
    workspaceGitCommitService.commitWorkspaceOutputs(runId, workspace);
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
    PlanGateContinuation continuation = req.planGateContinuation();
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
        req.checkoutBranch(),
        null,
        continuation == null ? null : continuation.pinnedModel(),
        continuation == null ? null : continuation.lowComplexityWorkerStartTierOverride(),
        continuation);
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
  /** Versioned state that crosses the durable plan-approval suspension point. */
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
      String checkoutBranch,
      PlanGateContinuation planGateContinuation) {

    /** Compatibility constructor for pause/crash resume callers that predate plan suspension. */
    public RunPipelineRequest(
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
        String checkoutBranch) {
      this(
          runId,
          userRequest,
          provider,
          model,
          executionMode,
          skipApproval,
          projectId,
          selectedRepoIds,
          githubPat,
          tddMode,
          checkoutBranch,
          null);
    }

    public static RunPipelineRequest fromPausedRow(
        com.navneet.executespec.state.entity.PipelineRun run) {
      return fromPausedRow(run, null);
    }

    public static RunPipelineRequest fromPausedRow(
        com.navneet.executespec.state.entity.PipelineRun run, PlanGateContinuation continuation) {
      return RunBranchPublicationIntent.pausedRequest(run, continuation);
    }

    public static RunPipelineRequest fromApprovedPlanRow(
        PipelineRun run, PlanGateContinuation continuation) {
      return RunBranchPublicationIntent.approvedPlanRequest(run, continuation);
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

  private void failPlannerUnavailable(
      String runId, Instant startedAt, String failure, Throwable cause) {
    String genericReason = "planning could not " + failure;
    terminate(
        runId,
        "FAILED",
        "PLANNER_UNAVAILABLE",
        cause == null
            ? genericReason
            : PlannerFailureDiagnostic.describe(cause, genericReason, contextSanitizer),
        startedAt);
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
    // A phase interrupted before its normal attribution boundary still has immutable physical
    // attempt rows. Reconcile those facts before publishing terminal totals; the operation only
    // raises persisted usage and is safe on ordinary uninterrupted runs too.
    tokenAttributor.reconcileRunTotalsFromLedger(runId);
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
    // Claim the terminal transition before loading and enriching the entity. Cancellation may race
    // this reviewer/validation thread; a read-then-save below is not a compare-and-set and can
    // overwrite a user CANCELLED state after the cancel endpoint has already emitted its terminal
    // event. Once this claim succeeds, cancel() observes a terminal status and returns false;
    // when cancellation wins, the claim returns zero and this method must preserve that status.
    String persistedValidationFailure =
        validationKickoff.persistedFailureReason(runId, statusReason);
    if (persistedValidationFailure != null) note = persistedValidationFailure;
    int terminalClaim =
        statusReason == null && !"FAILED".equals(finalStatus)
            ? runRepo.claimTerminalTransitionPreservingReasons(runId, finalStatus, Instant.now())
            : runRepo.claimTerminalTransitionWithReasons(
                runId,
                finalStatus,
                statusReason,
                "FAILED".equals(finalStatus) ? note : persistedValidationFailure,
                Instant.now());
    runRepo
        .findById(runId)
        .ifPresent(
            r -> {
              // Preserve CANCELLED if the user cancelled mid-flight. The pipeline thread
              // may call terminate(...) after cancel() has already written CANCELLED;
              // without this guard the late write clobbers the user-initiated status
              // (e.g. clinic run 6d23d5b5 showed PARTIAL despite a successful cancel).
              String currentStatus = r.getStatus();
              if (terminalClaim > 0) {
                persistedStatus[0] = finalStatus;
              } else {
                persistedStatus[0] = currentStatus;
              }
              persistedReason[0] = r.getStatusReason();
              // A lost terminal claim means cancellation or another terminal writer won. Do not
              // save the entity at all: even cost/token enrichment must not reintroduce the stale
              // status from this thread's persistence context.
              if (terminalClaim == 0) {
                persistenceWriter.raiseUsageFloor(runId, cost);
                return;
              }
              // Keep the larger of the live tracker and the persisted attempt-ledger floor. An
              // interrupted/recovered tracker may contain only the post-restart tail and must not
              // shrink physical spend already reconciled above.
              double persistedCost = r.getActualCostUsd() == null ? 0d : r.getActualCostUsd();
              if (cost.costUsd() > persistedCost) {
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
              if (cost.inputTokens() > persistedIn) {
                r.setTotalInputTokens(RunCostTracker.saturatedInt(cost.inputTokens()));
              }
              if (cost.outputTokens() > persistedOut) {
                r.setTotalOutputTokens(RunCostTracker.saturatedInt(cost.outputTokens()));
              }
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
    if (terminalClaim > 0 && !"PAUSED".equalsIgnoreCase(actualStatus)) {
      validationKickoff.refreshRunGoalDecisionAfterTerminal(runId);
    }
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
    if (("COMPLETE".equalsIgnoreCase(actualStatus) || "PARTIAL".equalsIgnoreCase(actualStatus))
        && !"LOCAL_EXECUTION".equalsIgnoreCase(actualReason)) {
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
  private void persistEstimate(
      String runId, String provider, String model, Plan plan, String lowComplexityWorkerStartTier) {
    costEstimatePersister.persistEstimate(
        runId, provider, model, plan, lowComplexityWorkerStartTier);
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
      Plan plan,
      ExecutionResult execResult,
      ReviewerResult reviewResult,
      boolean reviewerErrored,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned) {
    if (!postReviewContinuationEnabled || !reviewerQualityFailed(reviewResult, reviewerErrored)) {
      return Optional.empty();
    }
    Optional<TaskOutcome> target = reviewerContinuationTarget(execResult, plan);
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
                .runId(runId)
                .taskId(task.taskId())
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
    decision = PostReviewContinuationDecisionMapper.withSource(decision, "POST_REVIEW");

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

  private StatusAndReason applyFinalVerificationStatus(
      String runId, ValidationReport report, StatusAndReason current) {
    var verification =
        PostReviewQualityPolicy.finalVerification(report, postReviewRequiredQualityThreshold);
    StatusAndReason next =
        switch (verification) {
          case PASSED -> current;
          case LOW_QUALITY -> new StatusAndReason("PARTIAL", "VALIDATION_REPORT_LOW_QUALITY");
          case UNAVAILABLE -> {
            String deterministicFailure =
                validationKickoff.persistedDeterministicFailureCode(runId);
            yield new StatusAndReason(
                RunValidationKickoff.unavailableValidationTerminalStatus(deterministicFailure),
                deterministicFailure == null
                    ? "VALIDATION_REPORT_UNAVAILABLE"
                    : deterministicFailure);
          }
        };
    if (next != current) {
      stampRunStatusReason(runId, next);
    }
    return next;
  }

  private Optional<PostReviewContinuation> maybeEmitPostValidationContinuationDecision(
      String runId,
      Plan plan,
      ExecutionResult execResult,
      ValidationReport report,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned) {
    // RR-826 remainder — escalate only on MEASURED low quality. A null report is UNAVAILABLE, not
    // failed output: re-running on a stronger model cannot fix a report generator that threw, and
    // doing so fleet-wide converts one persistent bug into recurring spend. Stamp the reason (see
    // applyFinalVerificationStatus) and stop.
    if (!postReviewContinuationEnabled
        || PostReviewQualityPolicy.reviewerProtocolUnavailable(report)
        || PostReviewQualityPolicy.finalVerification(report, postReviewRequiredQualityThreshold)
            != PostReviewQualityPolicy.FinalVerification.LOW_QUALITY) {
      return Optional.empty();
    }
    Optional<TaskOutcome> target = reviewerContinuationTarget(execResult, plan);
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
                .runId(runId)
                .taskId(task.taskId())
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
    decision = PostReviewContinuationDecisionMapper.withSource(decision, "POST_VALIDATION_REPORT");

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
    // RR-1693 (sibling of RR-479) — execution locality is run authority, not a per-dispatch
    // default. Post-review and post-validation continuation build a plain server-side
    // ExecutionRequest: no localHandsMode, no remote dispatcher. On a run the customer submitted as
    // local-hands that would execute commands and mutate files on ExecuteSpec's machine, and unlike
    // retry it needs no user action to fire. The main pipeline currently terminalises a local run
    // before the reviewer, so this is a latent path rather than a live one — which is exactly why
    // it must be fenced here rather than left to depend on an upstream early return. Refuse
    // visibly; never fall back to server execution.
    if (runRepo.findById(runId).map(PipelineRun::isLocalExecutionRequested).orElse(false)) {
      log.warn(
          "RunOrchestrator run={} continuation refused: this run executes on the customer's"
              + " machine (local hands) and continuation has no local transport",
          runId);
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
        || AutomaticCorrectionLifecycle.sameRoute(
            candidate,
            action.targetTask().dispatchedProvider(),
            action.targetTask().dispatchedModel())) {
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
    final CorrectionWorkspaceTransaction.Checkpoint correctionCheckpoint;
    final AutomaticCorrectionLifecycle.EvidenceCheckpoint correctionEvidenceCheckpoint;
    try {
      correctionCheckpoint =
          automaticCorrectionLifecycle.begin(runId, targetTaskKey, rootCtx.workspaceRoot());
      correctionEvidenceCheckpoint = automaticCorrectionLifecycle.invalidateEvidence(runId);
    } catch (RuntimeException checkpointFailure) {
      log.error(
          "RunOrchestrator run={} automatic correction refused — workspace checkpoint failed: {}",
          runId,
          checkpointFailure.getMessage(),
          checkpointFailure);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId,
              targetTaskId,
              "Automatic correction refused: the current workspace could not be checkpointed"
                  + " safely."));
      return Optional.empty();
    }

    RunLlmOperationLifecycleService.PhaseLease postValidationOperation = null;
    if ("POST_VALIDATION_REPORT".equals(continuationActionSource(decision))) {
      postValidationOperation =
          runOperationLifecycle.claimPostValidationContinuation(
              rootCtx,
              runId,
              targetTaskId
                  + "|"
                  + decision.decision().name()
                  + "|"
                  + candidate.provider()
                  + "|"
                  + candidate.model());
      rootCtx = postValidationOperation.context();
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
    automaticCorrectionLifecycle.recordExecuted(
        taskRow.get(),
        decision.decision().name(),
        candidate.provider(),
        candidate.model(),
        reason,
        limitData.limitName(),
        limitData.used(),
        limitData.max(),
        continuationActionSource(decision));

    var task = taskRow.get();
    var previousTaskState = automaticCorrectionLifecycle.snapshotTask(task);
    task.setStatus("PENDING");
    task.setStartedAt(null);
    task.setCompletedAt(null);
    taskRepo.save(task);
    broadcaster.emit(runId, RunEvent.taskStatus(runId, targetTaskId, "PENDING"));

    CorrectionWorkspaceTransaction.DiffEvidence correctionDiff = null;
    try {
      ExecutionRequest correctionRequest =
          AutomaticCorrectionExecutionRequestFactory.create(
              plan,
              candidate,
              rootCtx,
              confirmationRequired,
              benchmarkPriorRequired,
              seededUsage.providerSwitches(),
              seededUsage.modelStepUps(),
              seededUsage.modelStepDowns(),
              runRepo.findById(runId).map(PipelineRun::getUserRequest).orElse(null),
              designBlock,
              projectId,
              contextComposer.buildMultiRepoSummary(projectId, selectedRepoIds));
      ExecutionResult rerunResult =
          automaticCorrectionLifecycle.executeWithToolFence(
              correctionCheckpoint,
              executor,
              correctionRequest,
              new SequentialExecutor.ResumeRequest(
                  targetTaskKey,
                  candidate.provider(),
                  candidate.model(),
                  decision.decision(),
                  DecisionEvidenceV1.sha256(objectMapper.valueToTree(decision).toString())));
      if (brownfieldHttpContractGuard.hasBaseline(runId)) {
        brownfieldHttpContractGuard.assertCompatible(runId);
      }
      RunCostTracker.Snapshot afterPostReviewExec = costTracker.snapshot(runId);
      long workerIn = afterPostReviewExec.inputTokens() - beforePostReviewAction.inputTokens();
      long workerOut = afterPostReviewExec.outputTokens() - beforePostReviewAction.outputTokens();
      tokenAttributor.attributeWorkerAdditionalTask(
          runId,
          targetTaskId,
          workerIn,
          workerOut,
          candidate.provider().toUpperCase(Locale.ROOT),
          candidate.model());

      automaticCorrectionLifecycle.assertAuthority(correctionCheckpoint);
      automaticCorrectionLifecycle.runLintAutofix(runId, rootCtx.workspaceRoot());

      String preReviewerGeneration =
          automaticCorrectionLifecycle.currentGeneration(correctionCheckpoint);
      ProviderModel reviewerRoute = new ProviderModel(candidate.provider(), candidate.model());
      ReviewerResult rerunReviewResult = null;
      try {
        rerunReviewResult =
            reviewer.review(
                InitialReviewerRequestPolicy.builder()
                    .provider(reviewerRoute.provider())
                    .model(reviewerRoute.model())
                    .strictProvider(true)
                    .context(rootCtx)
                    .buildCommand(plan.buildCommand())
                    .skipWiringPass(skipWiring)
                    .maxIterationsPerPass(reviewerMaxIter)
                    .brownfield(selectedRepoIds != null && !selectedRepoIds.isEmpty())
                    .allowedRepoRoots(
                        RunReviewerScope.repoRoots(projectRepoService, projectId, selectedRepoIds))
                    .approvedNewFiles(RunReviewerScope.approvedNewFiles(plan))
                    .allowBuildManifestCreation(false)
                    .readOnly(true)
                    .originalGoal(
                        runRepo.findById(runId).map(PipelineRun::getUserRequest).orElse(null))
                    .requirements(plan.requirements())
                    .planAdvisories(
                        com.navneet.executespec.agent.TaskExpanderAgent
                            .advisoryPlanContractViolations(plan))
                    .build());
        RunCostTracker.Snapshot afterRerunReview = costTracker.snapshot(runId);
        long reviewerIn = afterRerunReview.inputTokens() - afterPostReviewExec.inputTokens();
        long reviewerOut = afterRerunReview.outputTokens() - afterPostReviewExec.outputTokens();
        tokenAttributor.attributePhaseAdditional(
            runId,
            com.navneet.executespec.cost.RunTokenAttributor.Phase.REVIEWER,
            reviewerIn,
            reviewerOut,
            reviewerRoute.provider().toUpperCase(Locale.ROOT),
            reviewerRoute.model());
      } catch (Exception ex) {
        log.warn(
            "RunOrchestrator run={} post-review continuation reviewer failed — recording partial"
                + " review",
            runId,
            ex);
        rerunReviewResult = reviewerFailedResult(ex);
      }
      AutomaticCorrectionLifecycle.ReviewAssessment reviewAssessment =
          automaticCorrectionLifecycle.assessReadOnlyReview(
              runId,
              targetTaskId,
              rootCtx.workspaceRoot(),
              correctionCheckpoint,
              preReviewerGeneration,
              rerunResult,
              rerunReviewResult);
      correctionDiff = reviewAssessment.diff();
      CorrectionAcceptancePolicy.Decision acceptance = reviewAssessment.decision();
      if (!acceptance.accepted()) {
        automaticCorrectionLifecycle.rollbackRejectedAttempt(
            targetTaskKey,
            correctionCheckpoint,
            correctionEvidenceCheckpoint,
            correctionDiff,
            previousTaskState,
            acceptance,
            reviewAssessment.attemptValidation());
        broadcaster.emit(
            runId,
            RunEvent.log(
                runId,
                targetTaskId,
                "Automatic correction rolled back: "
                    + acceptance.code()
                    + " — "
                    + acceptance.reason()));
        if (postValidationOperation != null) {
          runOperationLifecycle.terminateOneShot(
              postValidationOperation, "POST_VALIDATION_CORRECTION_ROLLED_BACK");
          postValidationOperation = null;
        }
        return Optional.empty();
      }
      automaticCorrectionLifecycle.assertAuthority(correctionCheckpoint);
      correctionValidationAdmission.regenerateAndAssert(
          runId,
          rerunResult,
          rerunReviewResult,
          plan,
          correctionCheckpoint,
          reviewAssessment.workspaceGeneration());
      if (automaticCorrectionLifecycle.rollbackIfEvidenceRegressed(
          runId,
          targetTaskId,
          targetTaskKey,
          correctionCheckpoint,
          correctionEvidenceCheckpoint,
          correctionDiff,
          previousTaskState)) {
        return Optional.empty();
      }
      var delta = automaticCorrectionLifecycle.captureValidationDelta(correctionEvidenceCheckpoint);
      CorrectionWorkspaceTransaction.Accepted generations =
          automaticCorrectionLifecycle.previewAcceptance(correctionCheckpoint);
      persistenceWriter.applyExecutionResultToTasks(runId, rerunResult);
      persistenceWriter.persistReview(runId, rerunReviewResult);
      automaticCorrectionLifecycle.recordResolution(
          targetTaskKey,
          "ACCEPTED",
          acceptance.code(),
          acceptance.reason(),
          generations.beforeGeneration(),
          generations.afterGeneration(),
          correctionDiff,
          delta,
          true);
      automaticCorrectionLifecycle.accept(correctionCheckpoint);
      if (postValidationOperation != null) {
        runOperationLifecycle.complete(postValidationOperation);
        postValidationOperation = null;
      }
      return Optional.of(new PostReviewContinuationResult(rerunResult, rerunReviewResult));
    } catch (Exception ex) {
      automaticCorrectionLifecycle.rollbackFailedAttempt(
          targetTaskKey, correctionCheckpoint, correctionEvidenceCheckpoint, correctionDiff, ex);
      if (postValidationOperation != null) {
        try {
          runOperationLifecycle.terminateOneShot(
              postValidationOperation, "POST_VALIDATION_CONTINUATION_FAILED");
        } catch (RuntimeException terminalFailure) {
          ex.addSuppressed(terminalFailure);
        }
      }
      log.warn(
          "RunOrchestrator run={} post-review continuation action failed for task={}: {}",
          runId,
          targetTaskId,
          ex.getMessage(),
          ex);
      broadcaster.emit(
          runId,
          RunEvent.log(
              runId, targetTaskId, "Post-review continuation action failed: " + ex.getMessage()));
      automaticCorrectionLifecycle.restoreTask(targetTaskKey, previousTaskState);
      return Optional.empty();
    }
  }

  private record PostReviewContinuationResult(
      ExecutionResult execResult, ReviewerResult reviewResult) {}

  static ReviewerResult reviewerFailedResult(Exception ex) {
    return PostReviewQualityPolicy.reviewerFailedResult(ex);
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

  private static String continuationActionSource(GoalContinuationPolicyDecision decision) {
    Object source =
        decision == null || decision.policy() == null ? null : decision.policy().get("source");
    return source == null || source.toString().isBlank() ? "POST_REVIEW" : source.toString();
  }

  record ContinuationUsage(int providerSwitches, int modelStepUps, int modelStepDowns) {}

  private record ContinuationLimitData(String limitName, int used, int max) {}

  static boolean reviewerQualityFailed(ReviewerResult reviewResult, boolean reviewerErrored) {
    return PostReviewQualityPolicy.reviewerQualityFailed(reviewResult, reviewerErrored);
  }

  static boolean validationReportQualityFailed(
      ValidationReport report, double requiredQualityThreshold) {
    return PostReviewQualityPolicy.validationReportQualityFailed(report, requiredQualityThreshold);
  }

  static boolean validationReportProvenanceFailed(ValidationReport report) {
    return report != null
        && report.getPipelineResult() == com.navneet.executespec.report.entity.PipelineResult.FAILED
        && Integer.valueOf(0).equals(report.getVerifiedScore())
        && report.getIssuesJson() != null
        && report.getIssuesJson().contains("\"stage\":\"DETERMINISTIC_PROVENANCE\"");
  }

  static GoalDonenessDecision validationReportLowQualityGoalDecision(
      ValidationReport report, double requiredQualityThreshold) {
    return PostReviewQualityPolicy.validationReportLowQualityGoalDecision(
        report, requiredQualityThreshold);
  }

  static GoalDonenessDecision reviewerLowQualityGoalDecision(
      ReviewerResult reviewResult, boolean reviewerErrored) {
    return PostReviewQualityPolicy.reviewerLowQualityGoalDecision(reviewResult, reviewerErrored);
  }

  static Optional<TaskOutcome> reviewerContinuationTarget(ExecutionResult execResult) {
    return PostReviewContinuationTargetPolicy.select(execResult, null);
  }

  static Optional<TaskOutcome> reviewerContinuationTarget(ExecutionResult execResult, Plan plan) {
    return PostReviewContinuationTargetPolicy.select(execResult, plan);
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
    return new PostReviewContinuationDecisionMapper(
            postReviewRequiredQualityThreshold,
            postReviewMaxProviderSwitches,
            postReviewMaxModelStepUps,
            postReviewMaxModelStepDowns)
        .data(decision, confirmationRequired, benchmarkPriorRequired, routePinned, source);
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

  private static String firstReason(GoalContinuationPolicyDecision decision) {
    if (decision == null || decision.reasons().isEmpty()) {
      return "no continuation reason supplied";
    }
    return decision.reasons().get(0);
  }

  record ProviderModel(String provider, String model) {}

  static ProviderModel reviewerRouteFor(
      String defaultProvider, String defaultModel, ExecutionResult exec) {
    return ReviewerRoutePolicy.select(defaultProvider, defaultModel, exec);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  /** v2.5.10 — pair of run-level terminal status + explicit reason string. */
  record StatusAndReason(String status, String reason) {}

  static StatusAndReason localExecutionTerminalStatus() {
    return new StatusAndReason("PARTIAL", "LOCAL_EXECUTION");
  }

  /**
   * v2.5.10 — decide run-level status from the authoritative signals available at terminate time:
   * reviewer verdict, final build result, and execution summary (including final-task vs
   * intermediate-warning separation from {@link ExecutionResult#anyIntermediateWarning()}).
   *
   * <p>Tests run ASYNC after terminate (see {@code generateValidationReport} → {@code
   * boundedTestGenKickoff.kickoff, so test counts are NOT a gating signal here. They're
   * captured on ValidationReport via {@code retryValidation} after tests complete and feed
   * verifiedScore.
   */
  static StatusAndReason resolveTerminalStatus(
      ExecutionResult exec, com.navneet.executespec.reviewer.ReviewerResult review) {
    TerminalStatusResolver.Result result = TerminalStatusResolver.resolve(exec, review);
    return new StatusAndReason(result.status(), result.reason());
  }

  /** A configured BLOCKING standard or unavailable verifier cannot end as a clean run. */
  static StatusAndReason applyStandardsGateStatus(
      StatusAndReason base,
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> issues) {
    return RunTerminalStatusPolicy.applyStandards(base, issues);
  }

  /** Configured architecture invariants are hard evidence, not a decorative report. */
  static StatusAndReason applyInvariantGateStatus(
      StatusAndReason base,
      java.util.List<com.navneet.executespec.reviewer.model.ReviewIssue> issues) {
    return RunTerminalStatusPolicy.applyInvariants(base, issues);
  }

  /** Multi-repo context moved to {@link RunContextComposer} and {@link RunPipelineSupport}. */

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
