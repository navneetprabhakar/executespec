package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.RunKeyContextStore;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.cost.RunTokenAttributor;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.executioncontext.LlmExecutionOperationClaim;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.reviewer.ReviewerAgent;
import com.navneet.executespec.reviewer.ReviewerResult;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class RunLifecycleServiceContinuationApprovalTest {

  private static final String RUN_ID = "12345678-run";
  private static final String TASK_ID = "T1";
  private static final String TASK_KEY = "12345678_T1";

  @TempDir Path tempDir;

  private final PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
  private final AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
  private final SseBroadcaster broadcaster = mock(SseBroadcaster.class);
  private final Metrics metrics = mock(Metrics.class);
  private final GateStore gateStore = mock(GateStore.class);
  private final RunControlPort controlPort = mock(RunControlPort.class);
  private final AgentProperties agentProps = new AgentProperties();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PipelineExecutorPool pool = mock(PipelineExecutorPool.class);
  private final ExecutorService executorService = mock(ExecutorService.class);
  private final SequentialExecutor executor = mock(SequentialExecutor.class);
  private final ReviewerAgent reviewer = mock(ReviewerAgent.class);
  private final RunCostTracker costTracker = mock(RunCostTracker.class);
  private final RunTokenAttributor tokenAttributor = mock(RunTokenAttributor.class);
  private final RunKeyContextStore keyContextStore = mock(RunKeyContextStore.class);
  private final WorkspaceRestoreService workspaceRestoreService =
      mock(WorkspaceRestoreService.class);
  private final RunPersistenceWriter persistenceWriter = mock(RunPersistenceWriter.class);
  private final RunContextComposer contextComposer = mock(RunContextComposer.class);
  private final RunValidationKickoff validationKickoff = mock(RunValidationKickoff.class);
  private final ModelResolver modelResolver = mock(ModelResolver.class);
  private final ValidationReportRepository validationReportRepository =
      mock(ValidationReportRepository.class);
  private final LlmExecutionPolicyFactory llmExecutionPolicyFactory =
      mock(LlmExecutionPolicyFactory.class);
  private final PlanGateDecisionService planGateDecisionService =
      mock(PlanGateDecisionService.class);
  private final PlanGateResumeStore planGateResumeStore = mock(PlanGateResumeStore.class);
  private final LlmExecutionOperationService operationService =
      mock(LlmExecutionOperationService.class);
  private final RunLlmOperationLifecycleService runOperationLifecycle =
      new RunLlmOperationLifecycleService(
          runRepo,
          llmExecutionPolicyFactory,
          new LlmExecutionOperationCoordinator(operationService));
  private final com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard
      brownfieldHttpContractGuard =
          mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class);
  private final CorrectionWorkspaceTransaction correctionWorkspaceTransaction =
      mock(CorrectionWorkspaceTransaction.class);
  private final CorrectionWorkspaceTransaction.Checkpoint correctionCheckpoint =
      new CorrectionWorkspaceTransaction.Checkpoint(
          java.util.List.of(
              new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                  Path.of("/tmp/executespec-test-repository"), "head", "tree")));

  private RunLifecycleService service;

  @BeforeEach
  void setUp() {
    agentProps.setWorkspace(tempDir.toString());
    when(pool.executor()).thenReturn(executorService);
    when(costTracker.snapshot(any())).thenReturn(new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
    service =
        new RunLifecycleService(
            runRepo,
            taskRepo,
            broadcaster,
            metrics,
            gateStore,
            controlPort,
            agentProps,
            objectMapper,
            pool,
            executor,
            reviewer,
            costTracker,
            tokenAttributor,
            keyContextStore,
            workspaceRestoreService,
            persistenceWriter,
            contextComposer,
            validationKickoff,
            modelResolver,
            validationReportRepository,
            null,
            null,
            null,
            null,
            null,
            llmExecutionPolicyFactory,
            planGateDecisionService,
            planGateResumeStore,
            runOperationLifecycle,
            null,
            brownfieldHttpContractGuard,
            correctionWorkspaceTransaction);
    LlmExecutionReference reference =
        new LlmExecutionReference(
            "llmctx-continuation-test", LlmExecutionScope.customer("test-org"));
    when(llmExecutionPolicyFactory.forRun(any())).thenReturn(reference);
    when(operationService.beginNewInvocation(
            eq(reference), any(), anyString(), eq(Duration.ofHours(4))))
        .thenAnswer(
            invocation ->
                new LlmExecutionOperationClaim(
                    invocation.getArgument(0),
                    "llmop-continuation-" + UUID.randomUUID(),
                    1,
                    1,
                    UUID.randomUUID()));
    when(operationService.complete(any(), anyString())).thenReturn(true);
    when(operationService.fail(any(), anyString(), any())).thenReturn(true);
    when(correctionWorkspaceTransaction.begin(any())).thenReturn(correctionCheckpoint);
    when(correctionWorkspaceTransaction.accept(correctionCheckpoint))
        .thenReturn(
            new CorrectionWorkspaceTransaction.Accepted(
                correctionCheckpoint.generation(), "b".repeat(64)));
  }

  @Test
  void cancelPersistsLiveUsageFloorWithoutSavingStaleRunEntity() {
    PipelineRun run = PipelineRun.builder().runId(RUN_ID).status("WORKING").build();
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
    when(runRepo.claimTerminalTransitionWithReasons(
            eq(RUN_ID), eq("CANCELLED"), isNull(), any(), any()))
        .thenReturn(1);
    when(costTracker.snapshot(RUN_ID))
        .thenReturn(new RunCostTracker.Snapshot(409_127, 14_706, 0, 372_736, 0.010256, 27));

    assertThat(service.cancel(RUN_ID)).isTrue();

    verify(tokenAttributor).reconcileRunTotalsFromLedger(RUN_ID);
    verify(persistenceWriter)
        .raiseUsageFloor(
            RUN_ID, new RunCostTracker.Snapshot(409_127, 14_706, 0, 372_736, 0.010256, 27));
    verify(runRepo, never()).save(run);
  }

  @Test
  void cancelLosingTerminalClaimDoesNotInterruptOrEmitCancelledState() {
    when(runRepo.findById(RUN_ID))
        .thenReturn(Optional.of(PipelineRun.builder().runId(RUN_ID).status("WORKING").build()));
    when(runRepo.claimTerminalTransitionWithReasons(
            eq(RUN_ID), eq("CANCELLED"), isNull(), any(), any()))
        .thenReturn(0);

    assertThat(service.cancel(RUN_ID)).isFalse();

    verify(controlPort, never()).removeActiveRun(RUN_ID);
    verify(broadcaster, never()).complete(RUN_ID);
  }

  @Test
  void cancellationFallsThroughWhenConcurrentApprovalWinsTheDecisionLock() {
    PipelineRun optimistic =
        PipelineRun.builder().runId(RUN_ID).status("AWAITING_APPROVAL").build();
    PipelineRun approved = PipelineRun.builder().runId(RUN_ID).status("APPROVAL_RESUMING").build();
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(optimistic), Optional.of(approved));
    when(planGateDecisionService.cancel(eq(RUN_ID), isNull(), any(), any()))
        .thenReturn(
            new PlanGateDecisionService.DecisionResult(
                approved, PlanGateDecisionService.DecisionKind.APPROVE, null, true));
    when(planGateResumeStore.cancelApprovedRun(eq(RUN_ID), any(), any())).thenReturn(true);

    assertThat(service.cancel(RUN_ID)).isTrue();

    verify(planGateResumeStore).cancelApprovedRun(eq(RUN_ID), any(), any());
    verify(runRepo, never()).claimTerminalTransitionWithReasons(any(), any(), any(), any(), any());
    verify(broadcaster).complete(RUN_ID);
  }

  /** A local-hands run must never be retried through the server-side tool path. */
  @Test
  void localHandsRunRetryIsRefusedInsteadOfFallingBackToServerExecution() throws Exception {
    Files.createDirectories(tempDir.resolve(RUN_ID));
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("PARTIAL")
            .userRequest("implement feature")
            .planJson(
                """
                {
                  "planTitle": "Approved plan",
                  "planSummary": "",
                  "tasks": [{"id": "T1", "title": "Implement", "description": ""}]
                }
                """)
            .aiProvider("deepseek")
            .workerModel("deepseek-chat")
            .approvedExecutionMode("REALTIME")
            .localExecutionRequested(true)
            .build();
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));

    assertThatThrownBy(() -> service.retryTask(RUN_ID, TASK_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("local hands");

    verify(pool, never()).submit(any(Runnable.class));
    verify(executor, never()).execute(any(), any());
    verify(taskRepo, never()).save(any());
  }

  @Test
  void crashRecoveryRehydratesTheExactCompletedPlanGateContinuation() throws Exception {
    String payload =
        objectMapper.writeValueAsString(
            new PlanGateContinuation(1, true, true, false, "pinned-model", "WORKHORSE"));
    when(planGateResumeStore.completedContinuation(RUN_ID))
        .thenReturn(
            Optional.of(
                new PlanGateResumeStore.ContinuationPayload(
                    payload, DecisionEvidenceV1.sha256(payload), 1)));

    PlanGateContinuation restored =
        service.persistedPlanGateContinuation(PipelineRun.builder().runId(RUN_ID).build());

    assertThat(restored.localRun()).isTrue();
    assertThat(restored.requireContinuationConfirmation()).isTrue();
    assertThat(restored.requireContinuationBenchmarkPrior()).isFalse();
    assertThat(restored.pinnedModel()).isEqualTo("pinned-model");
    assertThat(restored.lowComplexityWorkerStartTierOverride()).isEqualTo("WORKHORSE");
  }

  @Test
  void crashRecoveryFailsClosedOnCorruptCompletedContinuation() {
    when(planGateResumeStore.completedContinuation(RUN_ID))
        .thenReturn(
            Optional.of(
                new PlanGateResumeStore.ContinuationPayload(
                    "{\"schemaVersion\":1}", "0".repeat(64), 1)));

    assertThatThrownBy(
            () ->
                service.persistedPlanGateContinuation(PipelineRun.builder().runId(RUN_ID).build()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("identity validation");
  }

  @Test
  void approveContinuation_rerunsTaskWithSelectedCandidate() throws Exception {
    Files.createDirectories(tempDir.resolve(RUN_ID));
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("PARTIAL")
            .planJson(
                """
                {
                  "planTitle": "Retry plan",
                  "planSummary": "",
                  "tasks": [{"id": "T1", "title": "Fix build", "description": ""}]
                }
                """)
            .aiProvider("anthropic")
            .workerModel("claude-haiku-4-5-20251001")
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
                {
                  "decision": "STOP_BLOCKED",
                  "selectedCandidate": {"provider": "openai", "model": "gpt-5.4"},
                  "policy": {
                    "requireConfirmationBeforeExpensiveContinuation": true,
                    "proposedAction": "CHANGE_PROVIDER"
                  }
                }
                """)
            .build();

    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
    ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
    org.mockito.Mockito.doNothing().when(executorService).execute(runnable.capture());
    when(contextComposer.buildMultiRepoSummary(any(), any())).thenReturn("workspace");
    when(executor.execute(any(ExecutionRequest.class), any(SequentialExecutor.ResumeRequest.class)))
        .thenReturn(approvedResult("openai", "gpt-5.4"));

    RunLifecycleService.ContinuationApproval approval =
        service.approveContinuation(RUN_ID, TASK_ID);
    runnable.getValue().run();

    assertThat(approval.provider()).isEqualTo("openai");
    assertThat(approval.model()).isEqualTo("gpt-5.4");
    assertThat(approval.proposedAction()).isEqualTo(RunContinuationDecision.CHANGE_PROVIDER);
    ArgumentCaptor<ExecutionRequest> request = ArgumentCaptor.forClass(ExecutionRequest.class);
    ArgumentCaptor<SequentialExecutor.ResumeRequest> resume =
        ArgumentCaptor.forClass(SequentialExecutor.ResumeRequest.class);
    verify(executor).execute(request.capture(), resume.capture());
    assertThat(request.getValue().provider()).isEqualTo("openai");
    assertThat(request.getValue().model()).isEqualTo("gpt-5.4");
    assertThat(request.getValue().userConfirmedExpensiveContinuation()).isTrue();
    assertThat(request.getValue().userConfirmedContinuationAction())
        .isEqualTo(RunContinuationDecision.CHANGE_PROVIDER);
    assertThat(request.getValue().userConfirmedContinuationProvider()).isEqualTo("openai");
    assertThat(request.getValue().userConfirmedContinuationModel()).isEqualTo("gpt-5.4");
    assertThat(resume.getValue().targetTaskId()).isEqualTo(TASK_KEY);
    assertThat(resume.getValue().approvedProvider()).isEqualTo("openai");
    assertThat(resume.getValue().approvedModel()).isEqualTo("gpt-5.4");
    assertThat(resume.getValue().approvedAction())
        .isEqualTo(RunContinuationDecision.CHANGE_PROVIDER);
    assertThat(resume.getValue().approvedProposalDigest()).isEqualTo(approval.proposalDigest());
    assertThat(approval.proposalVersion()).isEqualTo(1);
    assertThat(approval.proposalDigest()).hasSize(64);
    assertThat(request.getValue().runContext().llmExecutionContextId())
        .isEqualTo("llmctx-continuation-test");
    assertThat(request.getValue().runContext().llmExecutionOperationAuthority()).isNotNull();
    assertThat(request.getValue().initialProviderSwitchesUsed()).isEqualTo(1);
    assertThat(request.getValue().initialModelStepUpsUsed()).isZero();
    assertThat(request.getValue().initialModelStepDownsUsed()).isZero();
    assertApprovedContinuationAction(
        task, RunContinuationDecision.CHANGE_PROVIDER, "openai", "gpt-5.4");
    assertApprovedContinuationEvent(RunContinuationDecision.CHANGE_PROVIDER, "openai", "gpt-5.4");
    verify(controlPort).terminate(eq(RUN_ID), eq("COMPLETE"), any(), any(), any());
  }

  @Test
  void approveContinuation_seedsApprovedModelStepUpUsage() throws Exception {
    Files.createDirectories(tempDir.resolve(RUN_ID));
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("PARTIAL")
            .planJson(
                """
                {
                  "planTitle": "Retry plan",
                  "planSummary": "",
                  "tasks": [{"id": "T1", "title": "Fix build", "description": ""}]
                }
                """)
            .aiProvider("anthropic")
            .workerModel("claude-haiku-4-5-20251001")
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
{
  "decision": "STOP_BLOCKED",
  "selectedCandidate": {
    "provider": "anthropic",
    "model": "claude-sonnet-4-6"
  },
  "policy": {"requireConfirmationBeforeExpensiveContinuation": true},
  "reasons": [
    "expensive continuation requires user confirmation before more spend; proposed action STEP_UP_MODEL -> anthropic/claude-sonnet-4-6"
  ]
}
""")
            .build();

    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
    ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
    org.mockito.Mockito.doNothing().when(executorService).execute(runnable.capture());
    when(contextComposer.buildMultiRepoSummary(any(), any())).thenReturn("workspace");
    when(executor.execute(any(ExecutionRequest.class), any(SequentialExecutor.ResumeRequest.class)))
        .thenReturn(approvedResult("anthropic", "claude-sonnet-4-6"));

    RunLifecycleService.ContinuationApproval approval =
        service.approveContinuation(RUN_ID, TASK_ID);
    runnable.getValue().run();

    assertThat(approval.proposedAction()).isEqualTo(RunContinuationDecision.STEP_UP_MODEL);
    ArgumentCaptor<ExecutionRequest> request = ArgumentCaptor.forClass(ExecutionRequest.class);
    verify(executor).execute(request.capture(), any(SequentialExecutor.ResumeRequest.class));
    assertThat(request.getValue().provider()).isEqualTo("anthropic");
    assertThat(request.getValue().model()).isEqualTo("claude-sonnet-4-6");
    assertThat(request.getValue().userConfirmedExpensiveContinuation()).isTrue();
    assertThat(request.getValue().userConfirmedContinuationAction())
        .isEqualTo(RunContinuationDecision.STEP_UP_MODEL);
    assertThat(request.getValue().userConfirmedContinuationProvider()).isEqualTo("anthropic");
    assertThat(request.getValue().userConfirmedContinuationModel()).isEqualTo("claude-sonnet-4-6");
    assertThat(request.getValue().initialProviderSwitchesUsed()).isZero();
    assertThat(request.getValue().initialModelStepUpsUsed()).isEqualTo(1);
    assertThat(request.getValue().initialModelStepDownsUsed()).isZero();
    assertApprovedContinuationAction(
        task, RunContinuationDecision.STEP_UP_MODEL, "anthropic", "claude-sonnet-4-6");
    assertApprovedContinuationEvent(
        RunContinuationDecision.STEP_UP_MODEL, "anthropic", "claude-sonnet-4-6");
  }

  // RR-1564: seeded escalation counters must ACCUMULATE prior EXECUTED/APPROVED continuation-action
  // rows from the durable ledger, not restart at a constant 1 on every human approval.
  @Test
  void approveContinuation_seedsAccumulatedPriorLedgerUsage() throws Exception {
    Files.createDirectories(tempDir.resolve(RUN_ID));
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("PARTIAL")
            .planJson(
                """
                {
                  "planTitle": "Retry plan",
                  "planSummary": "",
                  "tasks": [{"id": "T1", "title": "Fix build", "description": ""}]
                }
                """)
            .aiProvider("anthropic")
            .workerModel("claude-haiku-4-5-20251001")
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            // Two prior provider switches already burned before this proposal was blocked.
            .continuationActionsJson(
                """
                [
                  {"status": "EXECUTED", "decision": "CHANGE_PROVIDER"},
                  {"status": "APPROVED", "decision": "CHANGE_PROVIDER"}
                ]
                """)
            .continuationDecisionJson(
                """
{
  "decision": "STOP_BLOCKED",
  "selectedCandidate": {
    "provider": "anthropic",
    "model": "claude-sonnet-4-6"
  },
  "policy": {"requireConfirmationBeforeExpensiveContinuation": true},
  "reasons": [
    "expensive continuation requires user confirmation before more spend; proposed action STEP_UP_MODEL -> anthropic/claude-sonnet-4-6"
  ]
}
""")
            .build();

    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
    ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
    org.mockito.Mockito.doNothing().when(executorService).execute(runnable.capture());
    when(contextComposer.buildMultiRepoSummary(any(), any())).thenReturn("workspace");
    when(executor.execute(any(ExecutionRequest.class), any(SequentialExecutor.ResumeRequest.class)))
        .thenReturn(approvedResult("anthropic", "claude-sonnet-4-6"));

    service.approveContinuation(RUN_ID, TASK_ID);
    runnable.getValue().run();

    ArgumentCaptor<ExecutionRequest> request = ArgumentCaptor.forClass(ExecutionRequest.class);
    verify(executor).execute(request.capture(), any(SequentialExecutor.ResumeRequest.class));
    // 2 prior provider switches carried forward (not reset), STEP_UP_MODEL adds 1 step-up.
    assertThat(request.getValue().initialProviderSwitchesUsed()).isEqualTo(2);
    assertThat(request.getValue().initialModelStepUpsUsed()).isEqualTo(1);
    assertThat(request.getValue().initialModelStepDownsUsed()).isZero();
  }

  @Test
  void approveContinuation_postReviewProposalRerunsReviewerAndValidation() throws Exception {
    Files.createDirectories(tempDir.resolve(RUN_ID));
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("PARTIAL")
            .planJson(
                """
                {
                  "planTitle": "Retry plan",
                  "planSummary": "",
                  "buildCommand": "mvn test",
                  "tasks": [{"id": "T1", "title": "Fix review issues", "description": ""}]
                }
                """)
            .aiProvider("deepseek")
            .workerModel("deepseek-v4-pro")
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
                {
                  "decision": "STOP_BLOCKED",
                  "source": "POST_REVIEW",
                  "selectedCandidate": {
                    "provider": "openai",
                    "model": "gpt-5.4",
                    "benchmarkQualityPriorAvailable": false
                  },
                  "policy": {
                    "source": "POST_REVIEW",
                    "requireBenchmarkPriorForAutomaticContinuation": true,
                    "proposedAction": "CHANGE_PROVIDER"
                  }
                }
                """)
            .build();
    ExecutionResult retryResult = approvedResult("openai", "gpt-5.4");
    ReviewerResult reviewResult =
        ReviewerResult.builder()
            .overall(ReviewerResult.Overall.CLEAN)
            .finalBuildPassed(true)
            .build();

    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
    ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
    org.mockito.Mockito.doNothing().when(executorService).execute(runnable.capture());
    when(contextComposer.buildMultiRepoSummary(any(), any())).thenReturn("workspace");
    when(executor.execute(any(ExecutionRequest.class), any(SequentialExecutor.ResumeRequest.class)))
        .thenReturn(retryResult);
    when(reviewer.review(any())).thenReturn(reviewResult);
    when(costTracker.snapshot(RUN_ID))
        .thenReturn(
            new RunCostTracker.Snapshot(100, 20, 0, 0, 0.10, 1),
            new RunCostTracker.Snapshot(250, 60, 0, 0, 0.30, 2),
            new RunCostTracker.Snapshot(310, 80, 0, 0, 0.45, 3));

    RunLifecycleService.ContinuationApproval approval =
        service.approveContinuation(RUN_ID, TASK_ID);
    runnable.getValue().run();

    assertThat(approval.postReview()).isTrue();
    ArgumentCaptor<ExecutionRequest> request = ArgumentCaptor.forClass(ExecutionRequest.class);
    verify(executor).execute(request.capture(), any(SequentialExecutor.ResumeRequest.class));
    assertThat(request.getValue().provider()).isEqualTo("openai");
    assertThat(request.getValue().model()).isEqualTo("gpt-5.4");
    verify(persistenceWriter).updateStatus(RUN_ID, "REVIEWING");
    verify(tokenAttributor)
        .attributeWorkerAdditionalTask(RUN_ID, TASK_KEY, 150, 40, "OPENAI", "gpt-5.4");
    verify(reviewer).review(any());
    verify(persistenceWriter).persistReview(RUN_ID, reviewResult);
    verify(tokenAttributor)
        .attributePhaseAdditional(
            RUN_ID, RunTokenAttributor.Phase.REVIEWER, 60, 20, "OPENAI", "gpt-5.4");
    verify(validationKickoff)
        .generateValidationReport(RUN_ID, retryResult, reviewResult, request.getValue().plan());
    verify(controlPort).terminate(eq(RUN_ID), eq("COMPLETE"), any(), any(), any());
  }

  @Test
  void postValidationReportProposalUsesHeavyApprovalPath() {
    assertThat(RunLifecycleService.heavyContinuationApprovalSource("POST_VALIDATION_REPORT"))
        .isTrue();
  }

  @Test
  void approveContinuation_rejectsNonConfirmationProposalBeforeRetry() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
                {
                  "decision": "STOP_BLOCKED",
                  "selectedCandidate": {"provider": "openai", "model": "gpt-5.4"},
                  "policy": {"requireConfirmationBeforeExpensiveContinuation": false}
                }
                """)
            .build();
    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));

    assertThatThrownBy(() -> service.approveContinuation(RUN_ID, TASK_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approval-blocked continuation proposal");
    verify(pool, never()).submit(any(Runnable.class));
    verify(runRepo, never()).findById(any());
  }

  @Test
  void approveContinuation_acceptsBenchmarkConfidenceBlockedProposal() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
                {
                  "decision": "STOP_BLOCKED",
                  "selectedCandidate": {
                    "provider": "openai",
                    "model": "gpt-5.4",
                    "benchmarkQualityPriorAvailable": false
                  },
                  "policy": {"requireBenchmarkPriorForAutomaticContinuation": true}
                }
                """)
            .build();
    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID))
        .thenReturn(Optional.of(PipelineRun.builder().runId(RUN_ID).status("FAILED").build()));

    assertThatThrownBy(() -> service.approveContinuation(RUN_ID, TASK_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Workspace gone");
    verify(taskRepo, atLeastOnce()).findById(TASK_KEY);
    verify(taskRepo, never()).saveAndFlush(any());
  }

  @Test
  void approveContinuation_rejectsBenchmarkBackedStopWithoutConfirmationReason() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
                {
                  "decision": "STOP_BLOCKED",
                  "selectedCandidate": {
                    "provider": "openai",
                    "model": "gpt-5.4",
                    "benchmarkQualityPriorAvailable": true
                  },
                  "policy": {"requireBenchmarkPriorForAutomaticContinuation": true}
                }
                """)
            .build();
    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));

    assertThatThrownBy(() -> service.approveContinuation(RUN_ID, TASK_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("approval-blocked continuation proposal");
    verify(pool, never()).submit(any(Runnable.class));
    verify(runRepo, never()).findById(any());
  }

  @Test
  void approveContinuation_acceptsAlreadyPrefixedTaskId() {
    AgentTask task =
        AgentTask.builder()
            .taskId(TASK_KEY)
            .runId(RUN_ID)
            .status("PARTIAL")
            .continuationDecisionJson(
                """
                {
                  "decision": "STOP_BLOCKED",
                  "selectedCandidate": {"provider": "openai", "model": "gpt-5.4"},
                  "policy": {"requireConfirmationBeforeExpensiveContinuation": true}
                }
                """)
            .build();
    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID))
        .thenReturn(Optional.of(PipelineRun.builder().runId(RUN_ID).status("FAILED").build()));

    assertThatThrownBy(() -> service.approveContinuation(RUN_ID, TASK_KEY))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Workspace gone");
    verify(taskRepo, atLeastOnce()).findById(TASK_KEY);
  }

  private void assertApprovedContinuationAction(
      AgentTask task, RunContinuationDecision decision, String provider, String model)
      throws Exception {
    JsonNode actions = objectMapper.readTree(task.getContinuationActionsJson());
    assertThat(actions.isArray()).isTrue();
    assertThat(actions).hasSize(1);
    JsonNode action = actions.get(0);
    assertThat(action.path("status").asText()).isEqualTo("APPROVED");
    assertThat(action.path("decision").asText()).isEqualTo(decision.name());
    assertThat(action.path("provider").asText()).isEqualTo(provider);
    assertThat(action.path("model").asText()).isEqualTo(model);
    assertThat(action.path("limitName").asText()).isEqualTo("userApproval");
    assertThat(action.path("attempt").asInt()).isEqualTo(1);
  }

  private void assertApprovedContinuationEvent(
      RunContinuationDecision decision, String provider, String model) {
    ArgumentCaptor<RunEvent> event = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster, atLeastOnce()).emit(eq(RUN_ID), event.capture());
    assertThat(event.getAllValues())
        .anySatisfy(
            e -> {
              assertThat(e.type()).isEqualTo("CONTINUATION_ACTION");
              assertThat(e.data()).containsEntry("status", "APPROVED");
              assertThat(e.data()).containsEntry("decision", decision.name());
              assertThat(e.data()).containsEntry("selectedProvider", provider);
              assertThat(e.data()).containsEntry("selectedModel", model);
              assertThat(e.data()).containsEntry("limitName", "userApproval");
            });
  }

  private static ExecutionResult approvedResult(String provider, String model) {
    return ExecutionResult.builder()
        .overall(ExecutionResult.Overall.ALL_SUCCESS)
        .perTask(
            java.util.List.of(
                TaskOutcome.builder()
                    .taskId(TASK_ID)
                    .status(TaskOutcome.Status.SUCCESS)
                    .smokeTestPassed(true)
                    .dispatchedProvider(provider)
                    .dispatchedModel(model)
                    .build()))
        .build();
  }
}
