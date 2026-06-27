package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.RunKeyContextStore;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.harness.ExecutionRequest;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.harness.SequentialExecutor;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.reviewer.ReviewerAgent;
import com.navneet.executespec.router.ModelResolver;
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
  private final SequentialExecutor executor = mock(SequentialExecutor.class);
  private final ReviewerAgent reviewer = mock(ReviewerAgent.class);
  private final RunCostTracker costTracker = mock(RunCostTracker.class);
  private final RunKeyContextStore keyContextStore = mock(RunKeyContextStore.class);
  private final WorkspaceRestoreService workspaceRestoreService =
      mock(WorkspaceRestoreService.class);
  private final RunPersistenceWriter persistenceWriter = mock(RunPersistenceWriter.class);
  private final RunContextComposer contextComposer = mock(RunContextComposer.class);
  private final RunValidationKickoff validationKickoff = mock(RunValidationKickoff.class);
  private final ModelResolver modelResolver = mock(ModelResolver.class);
  private final ValidationReportRepository validationReportRepository =
      mock(ValidationReportRepository.class);

  private RunLifecycleService service;

  @BeforeEach
  void setUp() {
    agentProps.setWorkspace(tempDir.toString());
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
            keyContextStore,
            workspaceRestoreService,
            persistenceWriter,
            contextComposer,
            validationKickoff,
            modelResolver,
            validationReportRepository);
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
                  "policy": {"requireConfirmationBeforeExpensiveContinuation": true}
                }
                """)
            .build();

    when(taskRepo.findById(TASK_KEY)).thenReturn(Optional.of(task));
    when(runRepo.findById(RUN_ID)).thenReturn(Optional.of(run));
    ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
    when(pool.submit(runnable.capture())).thenReturn(CompletableFuture.completedFuture(null));
    when(contextComposer.buildMultiRepoSummary(any(), any())).thenReturn("workspace");
    when(executor.execute(any(ExecutionRequest.class), any(SequentialExecutor.ResumeRequest.class)))
        .thenReturn(ExecutionResult.builder().overall(ExecutionResult.Overall.ALL_SUCCESS).build());

    RunLifecycleService.ContinuationApproval approval =
        service.approveContinuation(RUN_ID, TASK_ID);
    runnable.getValue().run();

    assertThat(approval.provider()).isEqualTo("openai");
    assertThat(approval.model()).isEqualTo("gpt-5.4");
    ArgumentCaptor<ExecutionRequest> request = ArgumentCaptor.forClass(ExecutionRequest.class);
    verify(executor).execute(request.capture(), any(SequentialExecutor.ResumeRequest.class));
    assertThat(request.getValue().provider()).isEqualTo("openai");
    assertThat(request.getValue().model()).isEqualTo("gpt-5.4");
    verify(controlPort).terminate(eq(RUN_ID), eq("COMPLETE"), any(), any(), any());
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
}
