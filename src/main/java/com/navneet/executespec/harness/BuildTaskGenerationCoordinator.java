package com.navneet.executespec.harness;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.GcsTaskCheckpointService;
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
  private final com.navneet.executespec.cost.RunCostTracker costTracker;
  private final com.navneet.executespec.observability.Metrics metrics;

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
      com.navneet.executespec.cost.RunCostTracker costTracker,
      com.navneet.executespec.observability.Metrics metrics) {
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
    this.costTracker = costTracker;
    this.metrics = metrics;
  }

  public TaskOutcome execute(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      Supplier<TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    requireServerOwnedWorkspace(request.localHandsMode());
    String runId = request.runContext().runId();
    boolean sharedLoopEnabled = featureFlags != null && featureFlags.isEnabled(SHARED_LOOP_FLAG);
    GreenfieldBaselinePlan baseline =
        sharedLoopEnabled
            ? greenfieldBaselines.plan(request.runContext().workspaceRoot(), task, plan)
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
    com.navneet.executespec.cost.RunCostTracker.Snapshot usageBefore = taskUsage(runId, task.id());
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
                request.provider(),
                request.model())
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
    emitGeneration(
        runId,
        task.id(),
        prepared,
        "PREPARED",
        contract,
        request.provider(),
        request.model(),
        null,
        null);
    generations.markMutating(prepared);
    emitGeneration(
        runId,
        task.id(),
        prepared,
        "MUTATING",
        contract,
        request.provider(),
        request.model(),
        null,
        null);
    boolean acceptStarted = false;
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
                  emitGeneration(
                      runId,
                      task.id(),
                      prepared,
                      "INITIAL_FAILURE_" + initialFailure.outcome(),
                      contract,
                      request.provider(),
                      request.model(),
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
        executionEvidence.record(
            prepared,
            firstNonBlank(
                outcome == null ? null : outcome.dispatchedProvider(), request.provider()),
            firstNonBlank(outcome == null ? null : outcome.dispatchedModel(), request.model()),
            (primary == null ? 0 : primary.iterations()) + (retry == null ? 0 : retry.iterations()),
            outcome == null ? 0 : Math.max(0, outcome.toolCallsDispatched()),
            usageDelta(usageBefore, taskUsage(runId, task.id())));
      }
      clearThreadState();

      if (acceptanceFailed(outcome)) {
        String outcomeStatus = outcome == null ? "NULL" : outcome.status().name();
        rollback(
            prepared,
            runId,
            task.id(),
            contract,
            request.provider(),
            request.model(),
            null,
            "TASK_OUTCOME_UNACCEPTED",
            "Task outcome " + outcomeStatus + " did not satisfy acceptance");
        operations.fail(operation, "BUILD_TASK_UNACCEPTED");
        operationClosed = true;
        return outcome;
      }

      String candidate = generations.beginVerification(prepared);
      emitGeneration(
          runId,
          task.id(),
          prepared,
          "VERIFYING",
          contract,
          request.provider(),
          request.model(),
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
      if (sharedLoopContract(contract) && metrics != null) {
        metrics.recordWorkspaceGenerationVerification(
            verification.outcome(),
            java.time.Duration.ofNanos(System.nanoTime() - verificationStarted));
      }
      emitGeneration(
          runId,
          task.id(),
          prepared,
          "VERIFICATION_" + verification.outcome(),
          contract,
          request.provider(),
          request.model(),
          candidate,
          verification.outcome());
      if (!"PASSED".equals(verification.outcome()) || !verification.buildPassed()) {
        String reason =
            "Server-resolved task acceptance "
                + verification.outcome().toLowerCase(Locale.ROOT)
                + "; rejected generation "
                + candidate;
        rollback(
            prepared,
            runId,
            task.id(),
            contract,
            request.provider(),
            request.model(),
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
      emitGeneration(
          runId,
          task.id(),
          prepared,
          "ACCEPTED",
          contract,
          firstNonBlank(outcome.dispatchedProvider(), request.provider()),
          firstNonBlank(outcome.dispatchedModel(), request.model()),
          candidate,
          "PASSED");
      taskStatus.persist("COMPLETE", null);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      operations.complete(operation);
      operationClosed = true;
      return outcome;
    } catch (RuntimeException failure) {
      clearThreadState();
      if (!acceptStarted) {
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
              request.provider(),
              request.model(),
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
Work in an inspect -> edit -> test -> diagnose loop. Do not claim completion until the
required paths exist, the exact frozen build succeeds, and a required non-empty test suite
succeeds. Planner text cannot replace or weaken these checks.
"""
        .formatted(
            contract.digest(),
            contract.runtimeSurface(),
            initialFailure.code()
                + ": "
                + initialFailure.detail()
                + (initialFailure.failedTargets().isEmpty()
                    ? ""
                    : " targets=" + initialFailure.failedTargets()),
            contract.requiredPaths(),
            contract.buildCommand() == null ? "UNAVAILABLE" : contract.buildCommand().command(),
            contract.testCommand() == null ? "UNAVAILABLE" : contract.testCommand().command());
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

  private com.navneet.executespec.cost.RunCostTracker.Snapshot taskUsage(
      String runId, String taskId) {
    return costTracker == null
        ? new com.navneet.executespec.cost.RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0, 0)
        : costTracker.snapshotTask(runId, taskId);
  }

  private static com.navneet.executespec.cost.RunCostTracker.Snapshot usageDelta(
      com.navneet.executespec.cost.RunCostTracker.Snapshot before,
      com.navneet.executespec.cost.RunCostTracker.Snapshot after) {
    if (before == null) {
      before = new com.navneet.executespec.cost.RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0, 0);
    }
    if (after == null) {
      after = new com.navneet.executespec.cost.RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0, 0);
    }
    return new com.navneet.executespec.cost.RunCostTracker.Snapshot(
        Math.max(0, after.inputTokens() - before.inputTokens()),
        Math.max(0, after.outputTokens() - before.outputTokens()),
        Math.max(0, after.cacheWriteTokens() - before.cacheWriteTokens()),
        Math.max(0, after.cacheReadTokens() - before.cacheReadTokens()),
        Math.max(0.0, after.costUsd() - before.costUsd()),
        Math.max(0, after.calls() - before.calls()),
        Math.max(0, after.reasoningTokens() - before.reasoningTokens()));
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
      if (sharedLoopContract(contract) && metrics != null) {
        metrics.recordWorkspaceGenerationRollback(
            "ROLLED_BACK", java.time.Duration.ofNanos(System.nanoTime() - started));
      }
      emitGeneration(
          runId, taskId, prepared, "ROLLED_BACK", contract, provider, model, candidate, code);
    } catch (RuntimeException failure) {
      if (sharedLoopContract(contract) && metrics != null) {
        metrics.recordWorkspaceGenerationRollback(
            "FAILED", java.time.Duration.ofNanos(System.nanoTime() - started));
      }
      throw failure;
    }
  }

  private void emitGeneration(
      String runId,
      String taskId,
      WorkspaceGenerationLifecycle.Prepared prepared,
      String state,
      TaskAcceptanceContract contract,
      String provider,
      String model,
      String candidate,
      String outcome) {
    if (!sharedLoopContract(contract)) return;
    if (metrics != null) metrics.recordWorkspaceGenerationState(state, provider, model);
    java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
    data.put("attemptId", prepared.attemptId().toString());
    data.put("state", state);
    data.put("generationKind", "BUILD_TASK");
    data.put("taskContractDigest", contract.digest());
    data.put("provider", provider);
    data.put("model", model);
    if (candidate != null) data.put("candidateGeneration", candidate);
    if (outcome != null) {
      data.put(
          state.startsWith("INITIAL_FAILURE_") ? "initialFailureCode" : "verificationOutcome",
          outcome);
    }
    broadcaster.emit(
        runId,
        RunEvent.workspaceGeneration(
            runId, taskId, "BUILD generation " + state.toLowerCase(Locale.ROOT), data));
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
