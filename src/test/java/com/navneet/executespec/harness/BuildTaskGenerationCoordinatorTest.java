package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContractResolver;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceVerifier;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationLifecycle;

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
  private BuildTaskGenerationCoordinator coordinator;
  private PlannedTask task;
  private Plan plan;
  private ExecutionRequest request;
  private WorkspaceGenerationLifecycle.Prepared prepared;
  private RunLlmOperationLifecycleService.PhaseLease operation;
  private TaskAcceptanceContract contract;

  @BeforeEach
  void setUp() {
    generations = mock(WorkspaceGenerationLifecycle.class);
    contracts = mock(TaskAcceptanceContractResolver.class);
    verifier = mock(TaskAcceptanceVerifier.class);
    operations = mock(RunLlmOperationLifecycleService.class);
    checkpoints = mock(GcsTaskCheckpointService.class);
    broadcaster = mock(SseBroadcaster.class);
    coordinator =
        new BuildTaskGenerationCoordinator(
            generations, contracts, verifier, operations, checkpoints, broadcaster);

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
    when(generations.prepareBuild(
            eq("run-1"), eq("T1"), eq(0), eq(workspace), anyString(), eq(contract.digest())))
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
        .prepareBuild(anyString(), anyString(), anyInt(), any(), anyString(), anyString());
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
  void unacceptedWorkerOutcomeRollsBackBeforeAnyVerification() {
    BuildTaskGenerationCoordinator.TaskStatusSink status =
        mock(BuildTaskGenerationCoordinator.TaskStatusSink.class);
    TaskOutcome failed =
        TaskOutcome.builder()
            .taskId("T1")
            .taskTitle("Build API")
            .status(TaskOutcome.Status.WORKER_FAILED)
            .build();

    TaskOutcome outcome = coordinator.execute(0, task, plan, request, () -> failed, status);

    assertThat(outcome).isSameAs(failed);
    verify(generations)
        .rollback(
            prepared,
            "TASK_OUTCOME_UNACCEPTED",
            "Task outcome WORKER_FAILED did not satisfy acceptance");
    verify(operations).fail(operation, "BUILD_TASK_UNACCEPTED");
    verify(generations, never()).beginVerification(any());
    verify(verifier, never()).verify(any(), any(), any());
    verify(generations, never()).accept(any());
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
