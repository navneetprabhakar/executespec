package com.navneet.executespec.harness;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.deployment.GcsTaskCheckpointService;
import com.navneet.executespec.engine.DeterministicFailureDelta;
import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunLlmOperationLifecycleService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.BuildAcceptanceExecutionContext;
import com.navneet.executespec.workspacegeneration.BuildEnvironmentMaterializer;
import com.navneet.executespec.workspacegeneration.BuildProtocolVersionService;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselinePlan;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselineService;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContractResolver;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationConvergenceService;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;
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

  private final WorkspaceGenerationLifecycle generations;
  private final WorkspaceGenerationExecutionEvidenceRecorder executionEvidence;
  private final WorkspaceGenerationInitialFailureRecorder initialFailures;
  private final TaskAcceptanceContractResolver contracts;
  private final TaskAcceptanceVerifier verifier;
  private final RunLlmOperationLifecycleService operations;
  private final GcsTaskCheckpointService checkpoints;
  private final SseBroadcaster broadcaster;
  private final GreenfieldBaselineService greenfieldBaselines;
  private final BuildProtocolVersionService protocolVersions;
  private final BuildTaskUsageEvidence usageEvidence;
  private final WorkspaceGenerationTelemetry telemetry;
  private final WorkspaceGenerationConvergenceService convergence;
  private final BuildContinuationAdmissionService admissions;
  private final BuildEnvironmentMaterializer environments;

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
      BuildProtocolVersionService protocolVersions,
      BuildTaskUsageEvidence usageEvidence,
      WorkspaceGenerationTelemetry telemetry,
      WorkspaceGenerationConvergenceService convergence,
      BuildContinuationAdmissionService admissions,
      BuildEnvironmentMaterializer environments) {
    this.generations = generations;
    this.executionEvidence = executionEvidence;
    this.initialFailures = initialFailures;
    this.contracts = contracts;
    this.verifier = verifier;
    this.operations = operations;
    this.checkpoints = checkpoints;
    this.broadcaster = broadcaster;
    this.greenfieldBaselines = greenfieldBaselines;
    this.protocolVersions = protocolVersions;
    this.usageEvidence = usageEvidence;
    this.telemetry = telemetry;
    this.convergence = convergence;
    this.admissions = admissions;
    this.environments = environments;
  }

  public TaskOutcome execute(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      Function<ExecutionContext, TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    return executeAttempt(
        taskIndex,
        task,
        plan,
        request,
        request.provider(),
        request.model(),
        false,
        null,
        false,
        dispatch,
        taskStatus);
  }

  /** Starts one physical BUILD continuation on the exact requested route. */
  public TaskOutcome executeContinuation(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      String requestedProvider,
      String requestedModel,
      Integer expectedRecoveryAttempt,
      boolean authorizedTerminal,
      Function<ExecutionContext, TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    return executeAttempt(
        taskIndex,
        task,
        plan,
        request,
        requestedProvider,
        requestedModel,
        true,
        expectedRecoveryAttempt,
        authorizedTerminal,
        dispatch,
        taskStatus);
  }

  private TaskOutcome executeAttempt(
      int taskIndex,
      PlannedTask task,
      Plan plan,
      ExecutionRequest request,
      String requestedProvider,
      String requestedModel,
      boolean continuation,
      Integer expectedRecoveryAttempt,
      boolean authorizedTerminal,
      Function<ExecutionContext, TaskOutcome> dispatch,
      TaskStatusSink taskStatus) {
    requireServerOwnedWorkspace(request.localHandsMode());
    String runId = request.runContext().runId();
    boolean sharedLoopEnabled = protocolVersions.requireExecutable(runId).sharedKernel();
    var correctionFrontier = continuation ? generations.acceptedFrontier(runId, taskIndex) : null;
    GreenfieldBaselinePlan baseline =
        sharedLoopEnabled
            ? greenfieldBaselines.plan(
                request.runContext().workspaceRoot(), task, plan, request.workspaceOrigin())
            : null;
    if (baseline != null && correctionFrontier != null) {
      baseline =
          com.navneet.executespec.workspacegeneration.BuildCorrectionScope.baseline(
              baseline, plan, correctionFrontier.taskIndex());
    }
    if (baseline != null && !baseline.supported()) {
      throw new IllegalStateException(
          "GREENFIELD_RUNTIME_UNSUPPORTED: deterministic BUILD baseline could not be resolved");
    }
    TaskAcceptanceContract contract =
        sharedLoopEnabled
            ? (correctionFrontier == null
                ? contracts.resolveFailureFirst(
                    task, plan, taskIndex, request.runContext().workspaceRoot(), baseline)
                : contracts.resolveCorrection(
                    task,
                    plan,
                    taskIndex,
                    request.runContext().workspaceRoot(),
                    baseline,
                    correctionFrontier.taskIndex()))
            : (correctionFrontier == null
                ? contracts.resolve(task, taskIndex)
                : contracts.resolveLegacyCorrection(
                    task, plan, taskIndex, correctionFrontier.taskIndex()));
    String authorityDigest =
        contracts.authorityDigest(plan, request.originalGoal(), request.workspaceSummary());
    String contractJson = sharedLoopEnabled ? contracts.canonicalContract(contract) : null;
    String initialFailureJson =
        sharedLoopEnabled ? contracts.canonicalInitialFailureEvidence(baseline) : null;
    if (continuation) {
      generations.assertContinuationReady(runId, request.runContext().workspaceRoot());
    }
    BuildContinuationAdmissionService.ClaimedAdmission claimedAdmission =
        admissions.admitAndClaim(
            new BuildContinuationAdmissionService.AttemptRequest(
                runId,
                task.id(),
                taskIndex,
                request.runContext().workspaceRoot(),
                authorityDigest,
                contract.digest(),
                contractJson,
                initialFailureJson,
                requestedProvider,
                requestedModel,
                expectedRecoveryAttempt,
                continuation
                    ? (authorizedTerminal
                        ? BuildContinuationAdmissionService.AdmissionKind.AUTHORIZED_TERMINAL
                        : BuildContinuationAdmissionService.AdmissionKind.AUTOMATIC_CONTINUATION)
                    : BuildContinuationAdmissionService.AdmissionKind.INITIAL,
                correctionFrontier),
            request.runContext());
    BuildContinuationAdmissionService.Admission admission = claimedAdmission.admission();
    if (!admission.admitted()) {
      throw new IllegalStateException("BUILD_ATTEMPT_ADMISSION_REFUSED:" + admission.refusal());
    }
    if (continuation) {
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PENDING"));
    }
    com.navneet.executespec.cost.RunCostTracker.Snapshot usageBefore =
        usageEvidence.snapshot(runId, task.id());
    RunLlmOperationLifecycleService.PhaseLease operation = claimedAdmission.operation();
    LlmExecutionOperationCoordinator.LeaseIdentity identity = claimedAdmission.identity();
    WorkspaceGenerationLifecycle.Prepared prepared;
    try {
      prepared =
          generations.prepareBuild(
              admission.attemptId(),
              request.runContext().workspaceRoot(),
              identity.operationId(),
              identity.attempt(),
              identity.leaseToken());
    } catch (RuntimeException preparationFailure) {
      try {
        operations.failBuildTask(operation, "BUILD_TASK_PREPARATION_FAILED");
      } catch (RuntimeException operationFailure) {
        preparationFailure.addSuppressed(operationFailure);
      }
      try {
        generations.recoverRunOrThrow(runId);
      } catch (RuntimeException recoveryFailure) {
        preparationFailure.addSuppressed(recoveryFailure);
      }
      throw preparationFailure;
    }
    try {
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
      WorkspaceGenerationDispatchAuthority mutationAuthority =
          new WorkspaceGenerationDispatchAuthority(
              runId,
              task.id(),
              prepared,
              WorkspaceGenerationDispatchAuthority.Phase.MUTATION,
              generations);
      ExecutionContext operationContext =
          operation.context().withWorkspaceGenerationAuthority(mutationAuthority);
      if (baseline != null) {
        mutationAuthority.assertDispatch(
            runId, task.id(), WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
        greenfieldBaselines.materialize(request.runContext().workspaceRoot(), baseline);
      }
      if (sharedLoopEnabled) {
        TaskAcceptanceVerifier.InitialFailure initialFailure =
            verifier.probeInitial(operationContext, contract);
        initialFailures.record(prepared, initialFailure);
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
        if (!initialFailure.admits(contract)) {
          throw new InitialFailureUnavailableException(initialFailure);
        }
        operationContext =
            operationContext.withBuildAcceptance(
                buildAcceptance(contract, baseline, initialFailure, true));
        // The initial probe above rematerializes only inside its disposable sandbox while the
        // canonical tree is still free of ignored dependency bytes. Materialize the separate
        // worker environment only after that proof, then freeze/clean it before final verification.
        environments.materialize(
            operationContext,
            prepared.attemptId(),
            BuildEnvironmentMaterializer.Phase.WORKER,
            () ->
                mutationAuthority.assertDispatch(
                    runId, task.id(), WorkspaceGenerationDispatchAuthority.Phase.MUTATION));
      } else {
        operationContext =
            operationContext.withBuildAcceptance(
                new BuildAcceptanceExecutionContext("", null, false, true));
      }
      if (continuation) {
        mutationAuthority.assertDispatch(
            runId, task.id(), WorkspaceGenerationDispatchAuthority.Phase.MUTATION);
        var action = admissions.recordDispatch(prepared.attemptId());
        if (action != null) {
          broadcaster.emit(
              runId,
              RunEvent.continuationAction(
                  runId,
                  task.id(),
                  "Continuation worker dispatched",
                  action.path("decision").asText(),
                  requestedProvider,
                  requestedModel,
                  action.path("reason").asText(),
                  action.path("attempt").asInt(),
                  1,
                  action.path("limitName").asText(),
                  action.path("usedLimit").asInt(),
                  action.path("maxLimit").asInt(),
                  "EXECUTED"));
        }
      }
      TaskOutcome outcome = dispatch.apply(operationContext);
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
            outcome == null || outcome.status() == null ? "MALFORMED" : outcome.status().name(),
            iterations,
            usage.costUsd());
      }
      if (outcome == null || outcome.status() == null) {
        throw new IllegalStateException("BUILD_TASK_DISPATCH_OUTCOME_INVALID");
      }
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
          verifier.verify(
              request
                  .runContext()
                  .child(task.id())
                  .withWorkspaceGenerationAuthority(
                      new WorkspaceGenerationDispatchAuthority(
                          runId,
                          task.id(),
                          prepared,
                          WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
                          generations)),
              contract,
              changedFiles);
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
        broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "PARTIAL"));
        return BuildTaskAcceptanceOutcomes.rejected(outcome, reason, verification);
      }

      generations.beginCheckpoint(prepared);
      CorrectionWorkspaceTransaction.Checkpoint candidateCheckpoint =
          generations.snapshotCandidate(prepared);
      GcsTaskCheckpointService.CheckpointReceipt checkpoint =
          generations.checkpointCandidate(prepared, task.id(), candidateCheckpoint);
      generations.completeCheckpoint(
          prepared,
          checkpoint.kind(),
          checkpoint.key(),
          checkpoint.generation(),
          checkpoint.artifactDigest(),
          checkpoint.storageGeneration(),
          checkpoint.sizeBytes(),
          checkpoint.crc32c(),
          checkpoint.etag());
      generations.accept(prepared);
      convergence.reconcileTerminal(prepared);
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
      broadcaster.emit(runId, RunEvent.taskStatus(runId, task.id(), "COMPLETE"));
      return BuildTaskAcceptanceOutcomes.accepted(task, outcome);
    } catch (RuntimeException failure) {
      if (failure.getMessage() != null
          && failure.getMessage().contains("WORKSPACE_GENERATION_FRONTIER_CAS_FAILED")) {
        telemetry.control(
            runId, task.id(), prepared.attemptId(), "ACCEPTED_FRONTIER_CAS", "CONFLICT", "ACCEPT");
      }
      try {
        String code =
            failure instanceof InitialFailureUnavailableException unavailable
                ? unavailable.evidence().code()
                : "TASK_GENERATION_EXCEPTION";
        convergence.convergeAfterFailure(prepared, code, failure.getMessage());
      } catch (RuntimeException convergenceFailure) {
        failure.addSuppressed(convergenceFailure);
      }
      throw failure;
    } finally {
      generations.release(prepared);
    }
  }

  /** Exact server-owned failure-first evidence carried explicitly with the worker dispatch. */
  private static BuildAcceptanceExecutionContext buildAcceptance(
      TaskAcceptanceContract contract,
      GreenfieldBaselinePlan baseline,
      TaskAcceptanceVerifier.InitialFailure initialFailure,
      boolean sharedWorkerControls) {
    if (contract == null
        || contract.schemaVersion() < 2
        || baseline == null
        || initialFailure == null) {
      return new BuildAcceptanceExecutionContext("", null, sharedWorkerControls, true);
    }
    String directive =
        """

SERVER-OWNED BUILD ACCEPTANCE CONTRACT (immutable for this task)
Contract digest: %s
Runtime surface: %s
Required proof strength: %s
Initial baseline evidence: %s
Required paths: %s
Frozen build command: %s
Frozen test command: %s
Files under .executespec/bootstrap are server-owned and immutable. Static route declarations or a
hand-written OpenAPI file do not prove an HTTP service; the server must verify the running
application before accepting it.
For a Python HTTP task, requirements.txt is executed offline and may declare only fastapi,
uvicorn, pydantic, and typing-extensions. The qualified image currently provides FastAPI
0.115.12, Uvicorn 0.34.3, and Pydantic 2.11.5. Use the Python standard library for persistence;
do not add a dependency that the frozen runtime kit cannot install.
Work in an inspect -> edit -> test -> diagnose loop. Do not claim completion until the
required paths exist, the exact frozen build succeeds, and a required non-empty test suite
succeeds. Planner text cannot replace or weaken these checks.
"""
            .formatted(
                contract.digest(),
                contract.runtimeSurface(),
                contract.requiredProofStrength(),
                initialFailure.code() + ": " + initialFailure.detail(),
                contract.requiredPaths(),
                contract.buildCommand() == null ? "UNAVAILABLE" : contract.buildCommand().command(),
                contract.testCommand() == null ? "UNAVAILABLE" : contract.testCommand().command());
    DeterministicFailureDelta.Snapshot snapshot =
        initialFailure.observed() || initialFailure.total() > 0
            ? DeterministicFailureDelta.snapshot(
                initialFailure.passed(),
                initialFailure.failed(),
                initialFailure.errors(),
                initialFailure.total(),
                initialFailure.detail())
            : null;
    return new BuildAcceptanceExecutionContext(directive, snapshot, sharedWorkerControls, true);
  }

  public static boolean acceptanceFailed(TaskOutcome outcome) {
    return BuildTaskAcceptanceOutcomes.failed(outcome);
  }

  public static boolean emitStopIfUnaccepted(
      TaskOutcome outcome,
      String runId,
      PlannedTask task,
      int remaining,
      SseBroadcaster broadcaster) {
    return BuildTaskAcceptanceOutcomes.emitStop(outcome, runId, task, remaining, broadcaster, log);
  }

  static void requireServerOwnedWorkspace(Boolean localHandsMode) {
    if (Boolean.TRUE.equals(localHandsMode)) {
      throw new IllegalStateException("LOCAL_HANDS_BUILD_GENERATION_PROTOCOL_REQUIRED");
    }
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
      convergence.convergeAfterFailure(prepared, code, reason);
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

  @FunctionalInterface
  public interface TaskStatusSink {
    TaskStatusPersistenceService.Result persist(String status, String reason);
  }
}
