package com.navneet.executespec.harness;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.GcsTaskCheckpointService;
import com.navneet.executespec.engine.DeterministicFailureDelta;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunLlmOperationLifecycleService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselinePlan;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselineService;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContractResolver;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationExecutionEvidenceRecorder;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationInitialFailureRecorder;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationTelemetry;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/** Owns one reversible, verified server-owned BUILD task generation transaction. */
@Service
@Slf4j
public class BuildTaskGenerationCoordinator {

  static final String SHARED_LOOP_FLAG = "build.shared-code-change-loop.enabled";
  private final WorkspaceGenerationLifecycle generations;
  private final WorkspaceGenerationExecutionEvidenceRecorder executionEvidence;
  private final WorkspaceGenerationInitialFailureRecorder initialFailures;
  private final TaskAcceptanceContractResolver contracts;
  private final TaskAcceptanceVerifier verifier;
  private final RunLlmOperationLifecycleService operations;
  private final GcsTaskCheckpointService checkpoints;
  private final SseBroadcaster broadcaster;
  private final GreenfieldBaselineService greenfieldBaselines;
  private final FeatureFlagService featureFlags;
  private final BuildTaskUsageEvidence usageEvidence;
  private final WorkspaceGenerationTelemetry telemetry;
  private final ThreadLocal<Boolean> acceptancePending = new ThreadLocal<>();
  private final ThreadLocal<ExecutionContext> activeTaskContext = new ThreadLocal<>();
  private final ThreadLocal<TaskAcceptanceContract> activeContract = new ThreadLocal<>();
  private final ThreadLocal<GreenfieldBaselinePlan> activeBaseline = new ThreadLocal<>();
  private final ThreadLocal<TaskAcceptanceVerifier.InitialFailure> activeInitialFailure =
      new ThreadLocal<>();

  public BuildTaskGenerationCoordinator(
      WorkspaceGenerationLifecycle generations,
      WorkspaceGenerationExecutionEvidenceRecorder executionEvidence,
      WorkspaceGenerationInitialFailureRecorder initialFailures,
      TaskAcceptanceContractResolver contracts,
      TaskAcceptanceVerifier verifier,
      RunLlmOperationLifecycleService operations,
      GcsTaskCheckpointService checkpoints,
      SseBroadcaster broadcaster,
      GreenfieldBaselineService greenfieldBaselines,
      FeatureFlagService featureFlags,
      BuildTaskUsageEvidence usageEvidence,
      WorkspaceGenerationTelemetry telemetry) {
    this.generations = generations;
    this.executionEvidence = executionEvidence;
    this.initialFailures = initialFailures;
    this.contracts = contracts;
    this.verifier = verifier;
    this.operations = operations;
    this.checkpoints = checkpoints;
    this.broadcaster = broadcaster;
    this.greenfieldBaselines = greenfieldBaselines;
    this.featureFlags = featureFlags;
    this.usageEvidence = usageEvidence;
    this.telemetry = telemetry;
  }

  public TaskOutcome execute(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      Supplier<TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    return execute(
        taskIndex, task, plan, request, request.provider(), request.model(), dispatch, taskStatus);
  }

  /**
   * Starts one physical BUILD generation attempt on the exact requested route. Continuation callers
   * use this overload so a provider/model change is persisted before any replacement model or
   * mutating tool is dispatched.
   */
  public TaskOutcome execute(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      String requestedProvider,
      String requestedModel,
      Supplier<TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    requireServerOwnedWorkspace(request.localHandsMode());
    String runId = request.runContext().runId();
    boolean sharedLoopEnabled = featureFlags != null && featureFlags.isEnabled(SHARED_LOOP_FLAG);
    GreenfieldBaselinePlan baseline =
        sharedLoopEnabled
            ? greenfieldBaselines.plan(
                request.runContext().workspaceRoot(), task, plan, request.workspaceOrigin())
            : null;
    if (baseline != null && !baseline.supported()) {
      throw new IllegalStateException(
          "GREENFIELD_RUNTIME_UNSUPPORTED: deterministic BUILD baseline could not be resolved");
    }
    TaskAcceptanceContract contract =
        sharedLoopEnabled
            ? contracts.resolveFailureFirst(
                task, plan, taskIndex, request.runContext().workspaceRoot(), baseline)
            : contracts.resolve(task, taskIndex);
    com.navneet.executespec.cost.RunCostTracker.Snapshot usageBefore =
        usageEvidence.snapshot(runId, task.id());
    WorkspaceGenerationLifecycle.Prepared prepared =
        sharedLoopEnabled
            ? generations.prepareBuild(
                runId,
                task.id(),
                taskIndex,
                request.runContext().workspaceRoot(),
                contracts.authorityDigest(plan, request.originalGoal(), request.workspaceSummary()),
                contract.digest(),
                contracts.canonicalContract(contract),
                contracts.canonicalInitialFailureEvidence(baseline),
                requestedProvider,
                requestedModel)
            : generations.prepareBuild(
                runId,
                task.id(),
                taskIndex,
                request.runContext().workspaceRoot(),
                contracts.authorityDigest(plan, request.originalGoal(), request.workspaceSummary()),
                contract.digest(),
                null,
                null,
                null,
                null);
    telemetry.state(
        runId,
        task.id(),
        prepared,
        "PREPARED",
        contract,
        requestedProvider,
        requestedModel,
        null,
        null);
    generations.markMutating(prepared);
    telemetry.state(
        runId,
        task.id(),
        prepared,
        "MUTATING",
        contract,
        requestedProvider,
        requestedModel,
        null,
        null);
    boolean acceptStarted = false;
    boolean rollbackStarted = false;
    boolean operationClosed = false;
    RunLlmOperationLifecycleService.PhaseLease operation = null;
    try {
      operation =
          operations.claimBuildTask(
              request.runContext(), runId, task.id(), taskIndex, contract.digest());
      LlmExecutionOperationCoordinator.LeaseIdentity identity = operations.identity(operation);
      generations.bindLlmAttempt(
          prepared, identity.operationId(), identity.attempt(), identity.leaseToken());
      activeTaskContext.set(operation.context());
      activeContract.set(contract);
      if (baseline != null) activeBaseline.set(baseline);
      acceptancePending.set(true);
      ExecutionContext operationContext = operation.context();
      TaskOutcome outcome =
          RunWorkspaceAuthorityService.withWorkspaceGenerationToolLease(
              prepared.lease(),
              () -> generations.assertDispatch(prepared),
              () -> {
                if (baseline != null) {
                  greenfieldBaselines.materialize(request.runContext().workspaceRoot(), baseline);
                }
                if (sharedLoopEnabled) {
                  TaskAcceptanceVerifier.InitialFailure initialFailure =
                      verifier.probeInitial(operationContext, contract);
                  initialFailures.record(prepared, initialFailure);
                  activeInitialFailure.set(initialFailure);
                  telemetry.state(
                      runId,
                      task.id(),
                      prepared,
                      "INITIAL_FAILURE_" + initialFailure.outcome(),
                      contract,
                      requestedProvider,
                      requestedModel,
                      null,
                      initialFailure.code());
                  if (!initialFailure.observed()) {
                    throw new InitialFailureUnavailableException(initialFailure);
                  }
                }
                return dispatch.get();
              });
      if (sharedLoopEnabled) {
        WorkerResult primary = outcome == null ? null : outcome.workerOutcome();
        WorkerResult retry = outcome == null ? null : outcome.retryWorkerOutcome();
        String effectiveProvider =
            firstNonBlank(outcome == null ? null : outcome.dispatchedProvider(), requestedProvider);
        String effectiveModel =
            firstNonBlank(outcome == null ? null : outcome.dispatchedModel(), requestedModel);
        int iterations =
            (primary == null ? 0 : primary.iterations()) + (retry == null ? 0 : retry.iterations());
        com.navneet.executespec.cost.RunCostTracker.Snapshot usage =
            usageEvidence.delta(usageBefore, runId, task.id());
        executionEvidence.record(
            prepared,
            effectiveProvider,
            effectiveModel,
            iterations,
            outcome == null ? 0 : Math.max(0, outcome.toolCallsDispatched()),
            usage);
        telemetry.execution(
            effectiveProvider,
            effectiveModel,
            outcome == null ? "NULL" : outcome.status().name(),
            iterations,
            usage.costUsd());
      }
      clearThreadState();

      // Worker termination is advisory after mutation. The frozen verifier alone accepts/rejects,
      // including warning/failure outcomes where the model forgot to call completeTask.
      String candidate = generations.beginVerification(prepared);
      telemetry.state(
          runId,
          task.id(),
          prepared,
          "VERIFYING",
          contract,
          requestedProvider,
          requestedModel,
          candidate,
          null);
      List<String> changedFiles = generations.changedFiles(prepared);
      long verificationStarted = System.nanoTime();
      WorkspaceGenerationLifecycle.Verification verification =
          RunWorkspaceAuthorityService.withWorkspaceGenerationToolLease(
              prepared.lease(),
              () -> generations.assertDispatch(prepared),
              () -> verifier.verify(request.runContext().child(task.id()), contract, changedFiles));
      generations.recordVerification(prepared, verification);
      if (sharedLoopContract(contract)) {
        telemetry.verification(
            verification.outcome(),
            contract.runtimeSurface(),
            "BROAD_FINAL",
            java.time.Duration.ofNanos(System.nanoTime() - verificationStarted));
      }
      telemetry.state(
          runId,
          task.id(),
          prepared,
          "VERIFICATION_" + verification.outcome(),
          contract,
          requestedProvider,
          requestedModel,
          candidate,
          verification.outcome());
      if (!"PASSED".equals(verification.outcome()) || !verification.buildPassed()) {
        String reason =
            "Server-resolved task acceptance "
                + verification.outcome().toLowerCase(Locale.ROOT)
                + "; rejected generation "
                + candidate;
        rollbackStarted = true;
        rollback(
            prepared,
            runId,
            task.id(),
            contract,
            requestedProvider,
            requestedModel,
            candidate,
            "TASK_VERIFICATION_REJECTED",
            reason);
        taskStatus.persist("PARTIAL", reason);
        broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
        operations.fail(operation, "BUILD_TASK_VERIFICATION_REJECTED");
        operationClosed = true;
        return rejectedOutcome(outcome, reason);
      }

      generations.beginCheckpoint(prepared);
      CorrectionWorkspaceTransaction.Checkpoint candidateCheckpoint =
          generations.snapshotCandidate(prepared);
      GcsTaskCheckpointService.CheckpointReceipt checkpoint =
          checkpoints.checkpointGeneration(runId, task.id(), candidateCheckpoint);
      generations.completeCheckpoint(
          prepared,
          checkpoint.kind(),
          checkpoint.key(),
          checkpoint.generation(),
          checkpoint.artifactDigest());
      acceptStarted = true;
      generations.accept(prepared);
      telemetry.state(
          runId,
          task.id(),
          prepared,
          "ACCEPTED",
          contract,
          firstNonBlank(outcome.dispatchedProvider(), requestedProvider),
          firstNonBlank(outcome.dispatchedModel(), requestedModel),
          candidate,
          "PASSED");
      taskStatus.persist("COMPLETE", null);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      operations.complete(operation);
      operationClosed = true;
      return acceptedOutcome(task, outcome);
    } catch (RuntimeException failure) {
      clearThreadState();
      if (failure.getMessage() != null
          && failure.getMessage().contains("WORKSPACE_GENERATION_FRONTIER_CAS_FAILED")) {
        telemetry.control(
            runId, task.id(), prepared.attemptId(), "ACCEPTED_FRONTIER_CAS", "CONFLICT", "ACCEPT");
      }
      if (!acceptStarted && !rollbackStarted) {
        try {
          String code =
              failure instanceof InitialFailureUnavailableException unavailable
                  ? unavailable.evidence().code()
                  : "TASK_GENERATION_EXCEPTION";
          rollback(
              prepared,
              runId,
              task.id(),
              contract,
              requestedProvider,
              requestedModel,
              null,
              code,
              failure.getMessage());
        } catch (RuntimeException rollbackFailure) {
          failure.addSuppressed(rollbackFailure);
        }
      }
      if (operation != null && !operationClosed) {
        try {
          operations.fail(operation, "BUILD_TASK_GENERATION_FAILED");
        } catch (RuntimeException operationFailure) {
          failure.addSuppressed(operationFailure);
        }
      }
      throw failure;
    } finally {
      clearThreadState();
    }
  }

  public ExecutionContext activeContextOr(ExecutionContext fallback) {
    ExecutionContext active = activeTaskContext.get();
    return active == null ? fallback : active;
  }

  public ExecutionContext childContext(ExecutionContext fallback, String taskId) {
    return activeContextOr(fallback).child(taskId);
  }

  public String pendingStatus(String terminalStatus) {
    return "COMPLETE".equals(terminalStatus) && Boolean.TRUE.equals(acceptancePending.get())
        ? "VERIFYING"
        : terminalStatus;
  }

  /** Exact server-owned failure-first evidence injected into BUILD's worker system contract. */
  public String acceptanceSystemDirective() {
    TaskAcceptanceContract contract = activeContract.get();
    GreenfieldBaselinePlan baseline = activeBaseline.get();
    TaskAcceptanceVerifier.InitialFailure initialFailure = activeInitialFailure.get();
    if (contract == null
        || contract.schemaVersion() < 2
        || baseline == null
        || initialFailure == null) return "";
    return """

SERVER-OWNED BUILD ACCEPTANCE CONTRACT (immutable for this task)
Contract digest: %s
Runtime surface: %s
Initial failing evidence: %s
Required paths: %s
Frozen build command: %s
Frozen test command: %s
Files under .executespec/bootstrap are server-owned and immutable. For an HTTP task, publish the
required method/path inventory in openapi.yaml as deterministic contract evidence; the hidden
bootstrap test independently checks route declarations in supported source layouts.
Work in an inspect -> edit -> test -> diagnose loop. Do not claim completion until the
required paths exist, the exact frozen build succeeds, and a required non-empty test suite
succeeds. Planner text cannot replace or weaken these checks.
"""
        .formatted(
            contract.digest(),
            contract.runtimeSurface(),
            initialFailure.code() + ": " + initialFailure.detail(),
            contract.requiredPaths(),
            contract.buildCommand() == null ? "UNAVAILABLE" : contract.buildCommand().command(),
            contract.testCommand() == null ? "UNAVAILABLE" : contract.testCommand().command());
  }

  /** Seed for the first post-edit deterministic comparison in the shared BUILD mutation loop. */
  public DeterministicFailureDelta.Snapshot activeInitialFailureSnapshot() {
    var failure = activeInitialFailure.get();
    return failure == null || !failure.observed()
        ? null
        : DeterministicFailureDelta.snapshot(
            failure.passed(),
            failure.failed(),
            failure.errors(),
            failure.total(),
            failure.detail());
  }

  String withAcceptanceDirective(String systemPrompt) {
    return systemPrompt + acceptanceSystemDirective();
  }

  boolean isolatesRouteChanges() {
    return failureFirstActive();
  }

  boolean workerControlEnabled(String flag, boolean configured) {
    return failureFirstActive() || configured;
  }

  public boolean failureFirstActive() {
    TaskAcceptanceContract contract = activeContract.get();
    return contract != null && contract.schemaVersion() >= 2;
  }

  public static boolean acceptanceFailed(TaskOutcome outcome) {
    if (outcome == null) return true;
    return switch (outcome.status()) {
      case SUCCESS, SUCCESS_AFTER_RETRY -> false;
      case COMPLETED_WITH_WARNING, WORKER_FAILED, SKIPPED -> true;
    };
  }

  private static TaskOutcome acceptedOutcome(PlannedTask task, TaskOutcome source) {
    if (source != null
        && (source.status() == TaskOutcome.Status.SUCCESS
            || source.status() == TaskOutcome.Status.SUCCESS_AFTER_RETRY)
        && Boolean.TRUE.equals(source.smokeTestPassed())) {
      return source;
    }
    boolean retried = source != null && source.retryAttempted();
    return TaskOutcome.builder()
        .taskId(source == null ? task.id() : source.taskId())
        .taskTitle(source == null ? task.title() : source.taskTitle())
        .status(retried ? TaskOutcome.Status.SUCCESS_AFTER_RETRY : TaskOutcome.Status.SUCCESS)
        .workerOutcome(source == null ? null : source.workerOutcome())
        .retryAttempted(retried)
        .retryWorkerOutcome(source == null ? null : source.retryWorkerOutcome())
        .smokeTestPassed(true)
        .smokeTestExitCode(0)
        .smokeTestOutput(source == null ? null : source.smokeTestOutput())
        .totalLatencyMs(source == null ? 0 : source.totalLatencyMs())
        .toolCallsDispatched(source == null ? 0 : source.toolCallsDispatched())
        .dispatchedProvider(source == null ? null : source.dispatchedProvider())
        .dispatchedModel(source == null ? null : source.dispatchedModel())
        .build();
  }

  public static boolean emitStopIfUnaccepted(
      TaskOutcome outcome,
      String runId,
      PlannedTask task,
      int remaining,
      SseBroadcaster broadcaster) {
    if (!acceptanceFailed(outcome)) return false;
    log.warn(
        "BUILD acceptance stop run={} task={} status={} smokePassed={} remaining={}",
        runId,
        task.id(),
        outcome.status(),
        outcome.smokeTestPassed(),
        remaining);
    String failure =
        "Task acceptance failed: "
            + outcome.status()
            + (Boolean.FALSE.equals(outcome.smokeTestPassed())
                ? " (deterministic verification failed)"
                : " (completion was not proven)");
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            task.id(),
            remaining > 0
                ? failure
                    + "; skipping "
                    + remaining
                    + " dependent task"
                    + (remaining == 1 ? "" : "s")
                : failure + "; execution cannot enter review as accepted"));
    return true;
  }

  static void requireServerOwnedWorkspace(Boolean localHandsMode) {
    if (Boolean.TRUE.equals(localHandsMode)) {
      throw new IllegalStateException("LOCAL_HANDS_BUILD_GENERATION_PROTOCOL_REQUIRED");
    }
  }

  private void clearThreadState() {
    acceptancePending.remove();
    activeTaskContext.remove();
    activeContract.remove();
    activeBaseline.remove();
    activeInitialFailure.remove();
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }

  private void rollback(
      WorkspaceGenerationLifecycle.Prepared prepared,
      String runId,
      String taskId,
      TaskAcceptanceContract contract,
      String provider,
      String model,
      String candidate,
      String code,
      String reason) {
    long started = System.nanoTime();
    try {
      generations.rollback(prepared, code, reason);
      if (sharedLoopContract(contract)) {
        telemetry.rollback("ROLLED_BACK", java.time.Duration.ofNanos(System.nanoTime() - started));
      }
      telemetry.state(
          runId, taskId, prepared, "ROLLED_BACK", contract, provider, model, candidate, code);
    } catch (RuntimeException failure) {
      if (sharedLoopContract(contract)) {
        telemetry.rollback("FAILED", java.time.Duration.ofNanos(System.nanoTime() - started));
      }
      throw failure;
    }
  }

  private static boolean sharedLoopContract(TaskAcceptanceContract contract) {
    return contract != null && contract.schemaVersion() >= 2;
  }

  private static TaskOutcome rejectedOutcome(TaskOutcome outcome, String reason) {
    return TaskOutcome.builder()
        .taskId(outcome.taskId())
        .taskTitle(outcome.taskTitle())
        .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
        .workerOutcome(outcome.workerOutcome())
        .retryAttempted(outcome.retryAttempted())
        .retryWorkerOutcome(outcome.retryWorkerOutcome())
        .smokeTestPassed(false)
        .smokeTestExitCode(outcome.smokeTestExitCode())
        .smokeTestOutput(
            (outcome.smokeTestOutput() == null ? "" : outcome.smokeTestOutput() + "\n") + reason)
        .totalLatencyMs(outcome.totalLatencyMs())
        .toolCallsDispatched(outcome.toolCallsDispatched())
        .dispatchedProvider(outcome.dispatchedProvider())
        .dispatchedModel(outcome.dispatchedModel())
        .goalDonenessDecision(outcome.goalDonenessDecision())
        .continuationDecision(outcome.continuationDecision())
        .continuationAction(outcome.continuationAction())
        .continuationActionProvider(outcome.continuationActionProvider())
        .continuationActionModel(outcome.continuationActionModel())
        .build();
  }

  @FunctionalInterface
  public interface TaskStatusSink {
    void persist(String status, String reason);
  }
}
