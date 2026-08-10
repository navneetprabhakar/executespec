package com.navneet.executespec.harness;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.deployment.GcsTaskCheckpointService;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunLlmOperationLifecycleService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContractResolver;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the reversible mutation, deterministic verification, checkpoint and acceptance transaction
 * for one initial server-owned BUILD task. Keeping this protocol outside {@link SequentialExecutor}
 * prevents its execution policy from becoming the durability implementation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuildTaskGenerationCoordinator {

  private final WorkspaceGenerationLifecycle generations;
  private final TaskAcceptanceContractResolver contracts;
  private final TaskAcceptanceVerifier verifier;
  private final RunLlmOperationLifecycleService operations;
  private final GcsTaskCheckpointService checkpoints;
  private final SseBroadcaster broadcaster;

  private final ThreadLocal<Boolean> acceptancePending = new ThreadLocal<>();
  private final ThreadLocal<ExecutionContext> activeTaskContext = new ThreadLocal<>();

  public TaskOutcome execute(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      Supplier<TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    requireServerOwnedWorkspace(request.localHandsMode());
    String runId = request.runContext().runId();
    TaskAcceptanceContract contract = contracts.resolve(task, taskIndex);
    WorkspaceGenerationLifecycle.Prepared prepared =
        generations.prepareBuild(
            runId,
            task.id(),
            taskIndex,
            request.runContext().workspaceRoot(),
            contracts.authorityDigest(plan, request.originalGoal(), request.workspaceSummary()),
            contract.digest());
    generations.markMutating(prepared);
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
      acceptancePending.set(true);
      TaskOutcome outcome =
          RunWorkspaceAuthorityService.withWorkspaceGenerationToolLease(
              prepared.lease(), () -> generations.assertDispatch(prepared), dispatch);
      clearThreadState();

      if (acceptanceFailed(outcome)) {
        generations.rollback(
            prepared,
            "TASK_OUTCOME_UNACCEPTED",
            "Task outcome " + outcome.status() + " did not satisfy acceptance");
        operations.fail(operation, "BUILD_TASK_UNACCEPTED");
        operationClosed = true;
        return outcome;
      }

      String candidate = generations.beginVerification(prepared);
      List<String> changedFiles = generations.changedFiles(prepared);
      WorkspaceGenerationLifecycle.Verification verification =
          RunWorkspaceAuthorityService.withWorkspaceGenerationToolLease(
              prepared.lease(),
              () -> generations.assertDispatch(prepared),
              () -> verifier.verify(request.runContext().child(task.id()), contract, changedFiles));
      generations.recordVerification(prepared, verification);
      if (!"PASSED".equals(verification.outcome()) || !verification.buildPassed()) {
        String reason =
            "Server-resolved task acceptance "
                + verification.outcome().toLowerCase(Locale.ROOT)
                + "; rejected generation "
                + candidate;
        generations.rollback(prepared, "TASK_VERIFICATION_REJECTED", reason);
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
      taskStatus.persist("COMPLETE", null);
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      operations.complete(operation);
      operationClosed = true;
      return outcome;
    } catch (RuntimeException failure) {
      clearThreadState();
      if (!acceptStarted) {
        try {
          generations.rollback(prepared, "TASK_GENERATION_EXCEPTION", failure.getMessage());
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
