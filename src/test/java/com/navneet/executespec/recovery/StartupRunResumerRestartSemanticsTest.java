package com.navneet.executespec.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Characterization tests for what a JVM restart does and does not recover.
 *
 * <p>The boot resumer must preserve a user-facing plan gate, while the durable local-hands event
 * stream is the only restart-independent evidence for a pending client call. These tests are
 * intentionally narrow: they do not claim that a blocked worker future can resume after a JVM
 * restart.
 */
class StartupRunResumerRestartSemanticsTest {

  private static final String RUN_ID = "restart-run";

  @Test
  void awaitingApproval_isLeftUntouchedWhenItsPendingGateIsReconciled() {
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("AWAITING_APPROVAL")
            .harnessVersion("v2")
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<RunLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
    RunLifecycleService lifecycle = mock(RunLifecycleService.class);

    when(runRepo.findByStatusIn(any())).thenReturn(List.of(run));
    when(lifecycleProvider.getObject()).thenReturn(lifecycle);
    when(lifecycle.recoverPlanGate(RUN_ID))
        .thenReturn(RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED);

    StartupRunResumer resumer =
        new StartupRunResumer(runRepo, taskRepo, broadcaster, lifecycleProvider);
    resumer.scanAndRecover();

    assertThat(run.getStatus()).isEqualTo("AWAITING_APPROVAL");
    verify(lifecycle).recoverPlanGate(RUN_ID);
    verify(runRepo, never()).save(any(PipelineRun.class));
    verify(taskRepo, never()).findByRunId(RUN_ID);
    verify(broadcaster, never()).emit(eq(RUN_ID), any());
  }

  @Test
  void startupDoesNotTreatAwaitingApprovalAsAnInterruptedWorker() {
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("AWAITING_APPROVAL")
            .harnessVersion("v2")
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<RunLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
    RunLifecycleService lifecycle = mock(RunLifecycleService.class);

    when(runRepo.findByStatusIn(any())).thenReturn(List.of(run));
    when(lifecycleProvider.getObject()).thenReturn(lifecycle);
    when(lifecycle.recoverPlanGate(RUN_ID))
        .thenReturn(RunLifecycleService.PlanApprovalOutcome.ALREADY_RESOLVED);

    new StartupRunResumer(runRepo, taskRepo, broadcaster, lifecycleProvider).scanAndRecover();

    verify(runRepo, never()).save(any(PipelineRun.class));
    verify(taskRepo, never()).findByRunId(RUN_ID);
  }

  @Test
  void interruptedPostExecutorPhase_isSalvagedWithoutProviderDispatch() throws Exception {
    Path workspaceRoot = Files.createTempDirectory("executespec-restart-");
    Files.createDirectories(workspaceRoot.resolve(RUN_ID));
    PipelineRun run =
        PipelineRun.builder()
            .runId(RUN_ID)
            .status("WORKING")
            .harnessVersion("v2")
            .workspacePath(workspaceRoot.resolve(RUN_ID).toString())
            .build();
    AgentTask task =
        AgentTask.builder().taskId(RUN_ID + "-task").runId(RUN_ID).status("COMPLETE").build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    SseBroadcaster broadcaster = mock(SseBroadcaster.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<RunLifecycleService> lifecycleProvider = mock(ObjectProvider.class);
    when(runRepo.findByStatusIn(any())).thenReturn(List.of(run));
    when(taskRepo.findByRunId(RUN_ID)).thenReturn(List.of(task));

    StartupRunResumer resumer =
        new StartupRunResumer(runRepo, taskRepo, broadcaster, lifecycleProvider);
    ReflectionTestUtils.setField(resumer, "workspaceRoot", workspaceRoot.toString());
    resumer.scanAndRecover();

    assertThat(run.getStatus()).isEqualTo("COMPLETE");
    assertThat(run.getStatusReason()).isEqualTo("REVIEWER_INTERRUPTED_CODE_PRESERVED");
    verify(runRepo).save(run);
    verify(lifecycleProvider, never()).getObject();
    verify(broadcaster).complete(RUN_ID);
  }
}
