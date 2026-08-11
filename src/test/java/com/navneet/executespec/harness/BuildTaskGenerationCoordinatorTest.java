package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.GcsTaskCheckpointService;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunLlmOperationLifecycleService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselinePlan;
import com.navneet.executespec.workspacegeneration.GreenfieldBaselineService;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContractResolver;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationTelemetry;
import com.navneet.executespec.workspacegeneration.WorkspaceOrigin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

class BuildTaskGenerationCoordinatorTest {

  @TempDir Path workspace;

  private WorkspaceGenerationLifecycle generations;
  private TaskAcceptanceContractResolver contracts;
  private TaskAcceptanceVerifier verifier;
  private RunLlmOperationLifecycleService operations;
  private GcsTaskCheckpointService checkpoints;
  private SseBroadcaster broadcaster;
  private GreenfieldBaselineService greenfieldBaselines;
  private FeatureFlagService featureFlags;
  private BuildTaskUsageEvidence usageEvidence;
  private WorkspaceGenerationTelemetry telemetry;
  private BuildTaskGenerationCoordinator coordinator;
  private PlannedTask task;
  private Plan plan;
  private ExecutionRequest request;
  private WorkspaceGenerationLifecycle.Prepared prepared;
  private com.navneet.executespec.workspacegeneration.WorkspaceGenerationExecutionEvidenceRecorder
      executionEvidence;
  private com.navneet.executespec.workspacegeneration.WorkspaceGenerationInitialFailureRecorder
      initialFailures;
  private RunLlmOperationLifecycleService.PhaseLease operation;
  private TaskAcceptanceContract contract;

  @BeforeEach
  void setUp() {
    generations = mock(WorkspaceGenerationLifecycle.class);
    executionEvidence =
        mock(
            com.navneet.executespec.workspacegeneration.WorkspaceGenerationExecutionEvidenceRecorder
                .class);
    initialFailures =
        mock(
            com.navneet.executespec.workspacegeneration.WorkspaceGenerationInitialFailureRecorder
                .class);
    contracts = mock(TaskAcceptanceContractResolver.class);
    verifier = mock(TaskAcceptanceVerifier.class);
    operations = mock(RunLlmOperationLifecycleService.class);
    checkpoints = mock(GcsTaskCheckpointService.class);
    broadcaster = mock(SseBroadcaster.class);
    greenfieldBaselines = mock(GreenfieldBaselineService.class);
    featureFlags = mock(FeatureFlagService.class);
    usageEvidence = mock(BuildTaskUsageEvidence.class);
    com.navneet.executespec.cost.RunCostTracker.Snapshot zeroUsage =
        new com.navneet.executespec.cost.RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0, 0);
    when(usageEvidence.snapshot(anyString(), anyString())).thenReturn(zeroUsage);
    when(usageEvidence.delta(any(), anyString(), anyString())).thenReturn(zeroUsage);
    telemetry = mock(WorkspaceGenerationTelemetry.class);
    coordinator =
        new BuildTaskGenerationCoordinator(
            generations,
            executionEvidence,
            initialFailures,
            contracts,
            verifier,
            operations,
            checkpoints,
            broadcaster,
            greenfieldBaselines,
            featureFlags,
            usageEvidence,
            telemetry);

    task = PlannedTask.builder().id("T1").title("Build API").description("Implement API").build();
    plan = Plan.builder().planTitle("Build").tasks(List.of(task)).build();
    ExecutionContext base = new ExecutionContext("run-1", "__run__", workspace.toAbsolutePath());
    request =
        ExecutionRequest.builder()
            .plan(plan)
            .provider("anthropic")
            .model("claude-sonnet")
            .runContext(base)
            .originalGoal("Build the API")
            .workspaceOrigin(WorkspaceOrigin.GREENFIELD)
            .workspaceSummary("single repository")
            .build();

    contract =
        new TaskAcceptanceContract(
            1, "T1", 0, "BUILD_AND_TEST", "REQUIRED", "REQUIRED", List.of(), "c".repeat(64));
    RunWorkspaceAuthorityService.Lease workspaceLease =
        mock(RunWorkspaceAuthorityService.Lease.class);
    when(workspaceLease.runId()).thenReturn("run-1");
    CorrectionWorkspaceTransaction.Checkpoint beforeCandidate =
        checkpoint("1".repeat(40), "2".repeat(40));
    prepared =
        new WorkspaceGenerationLifecycle.Prepared(
            UUID.randomUUID(), beforeCandidate, workspaceLease);
    when(contracts.resolve(task, 0)).thenReturn(contract);
    when(contracts.authorityDigest(plan, "Build the API", "single repository"))
        .thenReturn("a".repeat(64));
    when(contracts.canonicalContract(any())).thenReturn("{}");
    when(contracts.canonicalInitialFailureEvidence(any())).thenReturn("[]");
    when(generations.prepareBuild(
            eq("run-1"),
            eq("T1"),
            eq(0),
            eq(workspace),
            anyString(),
            eq(contract.digest()),
            isNull(),
            isNull(),
            isNull(),
            isNull()))
        .thenReturn(prepared);
    when(generations.prepareBuild(
            eq("run-1"),
            eq("T1"),
            eq(0),
            eq(workspace),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString()))
        .thenReturn(prepared);

    LlmExecutionOperationCoordinator.Lease delegate = operationLease();
    ExecutionContext operationContext = new ExecutionContext("run-1", "T1", workspace);
    operation =
        new RunLlmOperationLifecycleService.PhaseLease("build-task:T1", delegate, operationContext);
    when(operations.claimBuildTask(base, "run-1", "T1", 0, contract.digest()))
        .thenReturn(operation);
    when(operations.identity(operation))
        .thenReturn(
            new LlmExecutionOperationCoordinator.LeaseIdentity("llm-op-1", 1, UUID.randomUUID()));
  }

  @Test
  void acceptsOnlyAfterDispatchVerificationAndExactCheckpoint() {
    WorkspaceGenerationLifecycle.Verification passing = verification("PASSED", true, true, 2, 0);
    when(generations.beginVerification(prepared)).thenReturn("b".repeat(64));
    when(generations.changedFiles(prepared)).thenReturn(List.of("0:src/App.java"));
    when(verifier.verify(any(), eq(contract), eq(List.of("0:src/App.java"))))
        .thenAnswer(
            ignored -> {
              RunWorkspaceAuthorityService.assertCorrectionToolDispatch("run-1");
              return passing;
            });
    CorrectionWorkspaceTransaction.Checkpoint candidate =
        checkpoint("3".repeat(40), "4".repeat(40));
    when(generations.snapshotCandidate(prepared)).thenReturn(candidate);
    when(checkpoints.checkpointGeneration("run-1", "T1", candidate))
        .thenReturn(
            new GcsTaskCheckpointService.CheckpointReceipt(
                "GCS", "gs://bucket/candidate", "b".repeat(64), "d".repeat(64), Instant.now()));
    BuildTaskGenerationCoordinator.TaskStatusSink status =
        mock(BuildTaskGenerationCoordinator.TaskStatusSink.class);
    AtomicBoolean dispatchWasFenced = new AtomicBoolean();
    TaskOutcome expected = successfulOutcome();

    TaskOutcome actual =
        coordinator.execute(
            0,
            task,
            plan,
            request,
            () -> {
              RunWorkspaceAuthorityService.assertCorrectionToolDispatch("run-1");
              dispatchWasFenced.set(
                  RunWorkspaceAuthorityService.currentWorkspaceGenerationToolLease()
                      == prepared.lease());
              assertThat(coordinator.activeContextOr(request.runContext()).taskId())
                  .isEqualTo("T1");
              return expected;
            },
            status);

    assertThat(actual).isSameAs(expected);
    assertThat(dispatchWasFenced).isTrue();
    InOrder order = inOrder(generations, verifier, checkpoints, status, operations);
    order
        .verify(generations)
        .prepareBuild(
            anyString(),
            anyString(),
            anyInt(),
            any(),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
    order.verify(generations).markMutating(prepared);
    order.verify(generations).bindLlmAttempt(eq(prepared), eq("llm-op-1"), eq(1), any(UUID.class));
    order.verify(generations).assertDispatch(prepared);
    order.verify(generations).beginVerification(prepared);
    order.verify(verifier).verify(any(), eq(contract), eq(List.of("0:src/App.java")));
    order.verify(generations).recordVerification(prepared, passing);
    order.verify(generations).beginCheckpoint(prepared);
    order.verify(checkpoints).checkpointGeneration("run-1", "T1", candidate);
    order
        .verify(generations)
        .completeCheckpoint(
            prepared, "GCS", "gs://bucket/candidate", "b".repeat(64), "d".repeat(64));
    order.verify(generations).accept(prepared);
    order.verify(status).persist("COMPLETE", null);
    order.verify(operations).complete(operation);
    verify(generations, org.mockito.Mockito.times(2)).assertDispatch(prepared);
    verify(generations, never()).rollback(any(), anyString(), anyString());
    verify(telemetry, never()).verification(anyString(), anyString(), anyString(), any());
    verify(broadcaster, never())
        .emit(eq("run-1"), argThat(event -> "WORKSPACE_GENERATION".equals(event.type())));
  }

  @Test
  void failedServerVerificationRollsBackAndNeverCheckpointsOrAccepts() {
    WorkspaceGenerationLifecycle.Verification rejected = verification("FAILED", false, true, 1, 1);
    when(generations.beginVerification(prepared)).thenReturn("b".repeat(64));
    when(generations.changedFiles(prepared)).thenReturn(List.of("0:src/App.java"));
    when(verifier.verify(any(), eq(contract), any())).thenReturn(rejected);
    BuildTaskGenerationCoordinator.TaskStatusSink status =
        mock(BuildTaskGenerationCoordinator.TaskStatusSink.class);

    TaskOutcome outcome =
        coordinator.execute(0, task, plan, request, this::successfulOutcome, status);

    assertThat(outcome.status()).isEqualTo(TaskOutcome.Status.COMPLETED_WITH_WARNING);
    InOrder order = inOrder(generations, status, operations);
    order.verify(generations).recordVerification(prepared, rejected);
    order.verify(generations).rollback(eq(prepared), eq("TASK_VERIFICATION_REJECTED"), anyString());
    order.verify(status).persist(eq("PARTIAL"), anyString());
    order.verify(operations).fail(operation, "BUILD_TASK_VERIFICATION_REJECTED");
    verify(generations, never()).beginCheckpoint(any());
    verify(generations, never()).accept(any());
    verify(checkpoints, never()).checkpointGeneration(anyString(), anyString(), any());
    verify(operations, never()).complete(any());
  }

  @Test
  void workerFailureCannotOutrankPassingServerVerification() {
    BuildTaskGenerationCoordinator.TaskStatusSink status =
        mock(BuildTaskGenerationCoordinator.TaskStatusSink.class);
    WorkspaceGenerationLifecycle.Verification passing = verification("PASSED", true, true, 2, 0);
    when(generations.beginVerification(prepared)).thenReturn("b".repeat(64));
    when(generations.changedFiles(prepared)).thenReturn(List.of("0:src/App.java"));
    when(verifier.verify(any(), eq(contract), any())).thenReturn(passing);
    CorrectionWorkspaceTransaction.Checkpoint candidate =
        checkpoint("3".repeat(40), "4".repeat(40));
    when(generations.snapshotCandidate(prepared)).thenReturn(candidate);
    when(checkpoints.checkpointGeneration("run-1", "T1", candidate))
        .thenReturn(
            new GcsTaskCheckpointService.CheckpointReceipt(
                "GCS", "gs://bucket/candidate", "b".repeat(64), "d".repeat(64), Instant.now()));
    TaskOutcome failed =
        TaskOutcome.builder()
            .taskId("T1")
            .taskTitle("Build API")
            .status(TaskOutcome.Status.WORKER_FAILED)
            .build();

    TaskOutcome outcome = coordinator.execute(0, task, plan, request, () -> failed, status);

    assertThat(outcome.status()).isEqualTo(TaskOutcome.Status.SUCCESS);
    assertThat(outcome.workerOutcome()).isSameAs(failed.workerOutcome());
    verify(generations).beginVerification(prepared);
    verify(verifier).verify(any(), eq(contract), any());
    verify(generations).accept(prepared);
    verify(generations, never()).rollback(any(), anyString(), anyString());
    verify(operations).complete(operation);
  }

  @Test
  void skippedOutcomeCannotAuthorizeANewGeneration() {
    TaskOutcome skipped =
        TaskOutcome.builder()
            .taskId("T1")
            .taskTitle("Build API")
            .status(TaskOutcome.Status.SKIPPED)
            .build();

    assertThat(BuildTaskGenerationCoordinator.acceptanceFailed(skipped)).isTrue();
  }

  @Test
  void enabledFailureFirstModeMaterializesBaselineInsideLeaseBeforeDispatch() {
    when(featureFlags.isEnabled(BuildTaskGenerationCoordinator.SHARED_LOOP_FLAG)).thenReturn(true);
    GreenfieldBaselinePlan baseline =
        new GreenfieldBaselinePlan(
            GreenfieldBaselinePlan.Kind.PYTHON,
            "PYTHON_LIBRARY",
            true,
            true,
            java.util.Map.of("requirements.txt", "pytest\n"),
            List.of("src/app.py"),
            List.of("REQUIRED_PATH_MISSING:src/app.py", "FINAL_ACCEPTANCE_NOT_YET_PROVEN"));
    when(greenfieldBaselines.plan(workspace, task, plan, WorkspaceOrigin.GREENFIELD))
        .thenReturn(baseline);
    contract =
        new TaskAcceptanceContract(
            2,
            "T1",
            0,
            "SERVER_RESOLVED_FAILURE_FIRST_V2",
            "REQUIRED_NON_NOOP",
            "REQUIRED",
            List.of("src/app.py"),
            "PYTHON_LIBRARY",
            "PYTHON",
            new TaskAcceptanceContract.ResolvedCommand(
                "python3 -m compileall -q .", 120, "inferred:python", "PYTHON"),
            new TaskAcceptanceContract.ResolvedCommand(
                "python3 -m pytest -q", 600, "pytest", "PYTEST"),
            List.of("src/app.py"),
            List.of(),
            List.of(),
            true,
            List.of("."),
            List.of(),
            List.of(),
            "c".repeat(64));
    when(contracts.resolveFailureFirst(task, plan, 0, workspace, baseline)).thenReturn(contract);
    when(greenfieldBaselines.materialize(workspace, baseline))
        .thenAnswer(
            ignored -> {
              RunWorkspaceAuthorityService.assertCorrectionToolDispatch("run-1");
              return List.of("requirements.txt");
            });
    TaskAcceptanceVerifier.InitialFailure observedFailure =
        new TaskAcceptanceVerifier.InitialFailure(
            "FAILURE_OBSERVED",
            "REQUIRED_PATH_MISSING",
            "Required path assertions are red: [src/app.py]",
            null,
            List.of("src/app.py"));
    when(verifier.probeInitial(any(), eq(contract))).thenReturn(observedFailure);
    WorkspaceGenerationLifecycle.Verification passing = verification("PASSED", true, true, 2, 0);
    when(generations.beginVerification(prepared)).thenReturn("b".repeat(64));
    when(generations.changedFiles(prepared)).thenReturn(List.of("0:src/app.py"));
    when(verifier.verify(any(), eq(contract), any())).thenReturn(passing);
    CorrectionWorkspaceTransaction.Checkpoint candidate =
        checkpoint("3".repeat(40), "4".repeat(40));
    when(generations.snapshotCandidate(prepared)).thenReturn(candidate);
    when(checkpoints.checkpointGeneration("run-1", "T1", candidate))
        .thenReturn(
            new GcsTaskCheckpointService.CheckpointReceipt(
                "GCS", "gs://bucket/candidate", "b".repeat(64), "d".repeat(64), Instant.now()));

    coordinator.execute(
        0,
        task,
        plan,
        request,
        () -> {
          assertThat(coordinator.acceptanceSystemDirective())
              .contains("Contract digest: " + contract.digest())
              .contains("REQUIRED_PATH_MISSING: Required path assertions are red: [src/app.py]")
              .contains("python3 -m pytest -q")
              .doesNotContain("targets=");
          assertThat(coordinator.activeInitialFailureSnapshot().failed()).isEqualTo(1);
          return successfulOutcome();
        },
        mock(BuildTaskGenerationCoordinator.TaskStatusSink.class));

    InOrder order = inOrder(generations, operations, greenfieldBaselines);
    order.verify(generations).markMutating(prepared);
    order.verify(operations).claimBuildTask(any(), anyString(), anyString(), anyInt(), anyString());
    order.verify(generations).bindLlmAttempt(eq(prepared), anyString(), anyInt(), any(UUID.class));
    order.verify(greenfieldBaselines).materialize(workspace, baseline);
    verify(initialFailures).record(prepared, observedFailure);
    verify(executionEvidence)
        .record(eq(prepared), eq("anthropic"), eq("claude-sonnet"), eq(0), eq(0), any());
    verify(telemetry, atLeastOnce())
        .state(
            eq("run-1"),
            eq("T1"),
            eq(prepared),
            anyString(),
            eq(contract),
            eq("anthropic"),
            eq("claude-sonnet"),
            any(),
            any());
    verify(telemetry).verification(eq("PASSED"), eq("PYTHON_LIBRARY"), eq("BROAD_FINAL"), any());
    verify(telemetry)
        .state(
            "run-1",
            "T1",
            prepared,
            "INITIAL_FAILURE_FAILURE_OBSERVED",
            contract,
            "anthropic",
            "claude-sonnet",
            null,
            "REQUIRED_PATH_MISSING");
  }

  @Test
  void unsupportedGreenfieldFailsBeforeAttemptClaimOrDispatch() {
    when(featureFlags.isEnabled(BuildTaskGenerationCoordinator.SHARED_LOOP_FLAG)).thenReturn(true);
    GreenfieldBaselinePlan unsupported =
        new GreenfieldBaselinePlan(
            GreenfieldBaselinePlan.Kind.UNSUPPORTED,
            "UNRESOLVED",
            true,
            false,
            java.util.Map.of(),
            List.of(),
            List.of("FINAL_ACCEPTANCE_NOT_YET_PROVEN"));
    when(greenfieldBaselines.plan(workspace, task, plan, WorkspaceOrigin.GREENFIELD))
        .thenReturn(unsupported);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                coordinator.execute(
                    0,
                    task,
                    plan,
                    request,
                    this::successfulOutcome,
                    mock(BuildTaskGenerationCoordinator.TaskStatusSink.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GREENFIELD_RUNTIME_UNSUPPORTED");

    verify(generations, never())
        .prepareBuild(
            anyString(),
            anyString(),
            anyInt(),
            any(),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            isNull());
    verify(operations, never())
        .claimBuildTask(any(), anyString(), anyString(), anyInt(), anyString());
  }

  @Test
  void noActuallyObservedFailureRollsBackBeforeProviderDispatch() {
    when(featureFlags.isEnabled(BuildTaskGenerationCoordinator.SHARED_LOOP_FLAG)).thenReturn(true);
    GreenfieldBaselinePlan baseline =
        new GreenfieldBaselinePlan(
            GreenfieldBaselinePlan.Kind.EXISTING,
            "EXISTING_REPOSITORY",
            false,
            true,
            java.util.Map.of(),
            List.of("src/App.java"),
            List.of("FINAL_ACCEPTANCE_NOT_YET_PROVEN"));
    TaskAcceptanceContract v2 =
        new TaskAcceptanceContract(
            2,
            "T1",
            0,
            "SERVER_RESOLVED_FAILURE_FIRST_V2",
            "REQUIRED_NON_NOOP",
            "REQUIRED",
            List.of("src/App.java"),
            "EXISTING_REPOSITORY",
            "EXISTING",
            new TaskAcceptanceContract.ResolvedCommand("mvn compile", 300, "maven", "MAVEN"),
            new TaskAcceptanceContract.ResolvedCommand("mvn test", 600, "maven", "SUREFIRE"),
            List.of("src/App.java"),
            List.of(),
            List.of(),
            true,
            List.of("."),
            List.of(),
            List.of(),
            contract.digest());
    when(greenfieldBaselines.plan(workspace, task, plan, WorkspaceOrigin.GREENFIELD))
        .thenReturn(baseline);
    when(contracts.resolveFailureFirst(task, plan, 0, workspace, baseline)).thenReturn(v2);
    when(operations.claimBuildTask(any(), eq("run-1"), eq("T1"), eq(0), eq(v2.digest())))
        .thenReturn(operation);
    TaskAcceptanceVerifier.InitialFailure noFailure =
        new TaskAcceptanceVerifier.InitialFailure(
            "ALREADY_PASSED", "NO_INITIAL_FAILURE", "2 tests passed", "mvn test", List.of());
    when(verifier.probeInitial(any(), eq(v2))).thenReturn(noFailure);
    AtomicBoolean dispatched = new AtomicBoolean();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                coordinator.execute(
                    0,
                    task,
                    plan,
                    request,
                    () -> {
                      dispatched.set(true);
                      return successfulOutcome();
                    },
                    mock(BuildTaskGenerationCoordinator.TaskStatusSink.class)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NO_INITIAL_FAILURE");

    assertThat(dispatched).isFalse();
    verify(initialFailures).record(prepared, noFailure);
    verify(generations).rollback(eq(prepared), eq("NO_INITIAL_FAILURE"), anyString());
    verify(executionEvidence, never())
        .record(any(), anyString(), anyString(), anyInt(), anyInt(), any());
  }

  @Test
  void hiddenInitialVerifierTargetsNeverEnterTheWorkerDirective() {
    when(featureFlags.isEnabled(BuildTaskGenerationCoordinator.SHARED_LOOP_FLAG)).thenReturn(true);
    GreenfieldBaselinePlan baseline =
        new GreenfieldBaselinePlan(
            GreenfieldBaselinePlan.Kind.PYTHON,
            "HTTP_SERVICE",
            true,
            true,
            java.util.Map.of("requirements.txt", "pytest\n"),
            List.of("src/app.py"),
            List.of("ENDPOINT_CONTRACT_UNSATISFIED"));
    TaskAcceptanceContract v2 =
        new TaskAcceptanceContract(
            2,
            "T1",
            0,
            "SERVER_RESOLVED_FAILURE_FIRST_V2",
            "REQUIRED_NON_NOOP",
            "REQUIRED",
            List.of("src/app.py"),
            "HTTP_SERVICE",
            "PYTHON",
            new TaskAcceptanceContract.ResolvedCommand(
                "python3 -m compileall -q .", 120, "inferred:python", "PYTHON"),
            new TaskAcceptanceContract.ResolvedCommand(
                "python3 -m pytest -q", 600, "pytest", "PYTEST"),
            List.of("src/app.py"),
            List.of(),
            List.of("GET /operator-only"),
            true,
            List.of("."),
            List.of(),
            List.of(
                new TaskAcceptanceContract.AcceptanceCheck("ENDPOINT_CONTRACT", "openapi", true)),
            "d".repeat(64));
    when(greenfieldBaselines.plan(workspace, task, plan, WorkspaceOrigin.GREENFIELD))
        .thenReturn(baseline);
    when(contracts.resolveFailureFirst(task, plan, 0, workspace, baseline)).thenReturn(v2);
    when(operations.claimBuildTask(any(), eq("run-1"), eq("T1"), eq(0), eq(v2.digest())))
        .thenReturn(operation);
    when(verifier.probeInitial(any(), eq(v2)))
        .thenReturn(
            new TaskAcceptanceVerifier.InitialFailure(
                "FAILURE_OBSERVED",
                "ENDPOINT_CONTRACT_UNSATISFIED",
                "The admitted interface is not yet satisfied",
                null,
                List.of("GET /operator-only")));
    WorkspaceGenerationLifecycle.Verification rejected = verification("FAILED", true, true, 1, 1);
    when(generations.beginVerification(prepared)).thenReturn("b".repeat(64));
    when(generations.changedFiles(prepared)).thenReturn(List.of("0:src/app.py"));
    when(verifier.verify(any(), eq(v2), any())).thenReturn(rejected);

    TaskOutcome outcome =
        coordinator.execute(
            0,
            task,
            plan,
            request,
            () -> {
              assertThat(coordinator.acceptanceSystemDirective())
                  .contains("The admitted interface is not yet satisfied")
                  .doesNotContain("GET /operator-only");
              return TaskOutcome.builder()
                  .taskId("T1")
                  .taskTitle("Build API")
                  .status(TaskOutcome.Status.WORKER_FAILED)
                  .build();
            },
            mock(BuildTaskGenerationCoordinator.TaskStatusSink.class));

    assertThat(outcome.status()).isEqualTo(TaskOutcome.Status.COMPLETED_WITH_WARNING);
    verify(generations).rollback(eq(prepared), eq("TASK_VERIFICATION_REJECTED"), anyString());
  }

  private WorkspaceGenerationLifecycle.Verification verification(
      String outcome, boolean buildPassed, boolean testsExecuted, int passed, int failed) {
    Instant started = Instant.now();
    return new WorkspaceGenerationLifecycle.Verification(
        "BUILD_AND_TEST",
        "{\"build\":\"server-resolved\"}",
        outcome,
        buildPassed,
        testsExecuted,
        passed,
        failed,
        0,
        0,
        "{}",
        "TEST",
        "TEST",
        started,
        started.plusSeconds(1));
  }

  private TaskOutcome successfulOutcome() {
    return TaskOutcome.builder()
        .taskId("T1")
        .taskTitle("Build API")
        .status(TaskOutcome.Status.SUCCESS)
        .smokeTestPassed(true)
        .build();
  }

  private CorrectionWorkspaceTransaction.Checkpoint checkpoint(String head, String tree) {
    return new CorrectionWorkspaceTransaction.Checkpoint(
        workspace,
        List.of(new CorrectionWorkspaceTransaction.RepositoryCheckpoint(workspace, head, tree)));
  }

  private LlmExecutionOperationCoordinator.Lease operationLease() {
    LlmExecutionOperationService durableOperations = mock(LlmExecutionOperationService.class);
    LlmExecutionOperationCoordinator coordinator =
        new LlmExecutionOperationCoordinator(durableOperations);
    LlmExecutionReference reference =
        new LlmExecutionReference("ctx-1", LlmExecutionScope.customer("org-1"));
    LlmExecutionOperationClaim claim =
        new LlmExecutionOperationClaim(reference, "llm-op-1", 1, 1, UUID.randomUUID());
    when(durableOperations.claimOrResume(any(), any(), anyString(), any(Duration.class)))
        .thenReturn(Optional.of(claim));
    return coordinator
        .claimOrResume(
            reference,
            LlmExecutionOperationDescriptor.of("build-task:T1", "RUN_BUILD_TASK", "T1", 1),
            "test-owner",
            Duration.ofMinutes(1))
        .orElseThrow();
  }
}
