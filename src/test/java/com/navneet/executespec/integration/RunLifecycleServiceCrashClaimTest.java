package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Regression coverage for the rolling-restart crash-recovery ownership claim. */
class RunLifecycleServiceCrashClaimTest {

  @Test
  void freshTaskActivityFencesRecoveryBeforeRunLevelClaim() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("fresh-task-owner")
            .status("WORKING")
            .harnessVersion("v2")
            .resumeAttempts(0)
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId("fresh-task-owner_T1")
            .runId(run.getRunId())
            .status("WORKING")
            .lastActivityAt(Instant.now())
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(taskRepo.findByRunIdAndStatusIn(run.getRunId(), List.of("WORKING", "LOCKED")))
        .thenReturn(List.of(task));

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo, taskRepo, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE);
    verify(runRepo, never())
        .claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString());
    verify(taskRepo, never()).claimStaleForRecovery(anyString(), any(), any());
  }

  @Test
  void staleTaskIsAtomicallyMarkedForRecoveryBeforePlanValidation() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("stale-task-owner")
            .status("WORKING")
            .harnessVersion("v2")
            .resumeAttempts(0)
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId("stale-task-owner_T1")
            .runId(run.getRunId())
            .status("WORKING")
            .lastActivityAt(Instant.now().minus(Duration.ofMinutes(10)))
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    RunControlPort controlPort = mock(RunControlPort.class);
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(runRepo.claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString()))
        .thenReturn(1);
    when(taskRepo.findByRunIdAndStatusIn(run.getRunId(), List.of("WORKING", "LOCKED")))
        .thenReturn(List.of(task));
    when(taskRepo.claimStaleForRecovery(anyString(), any(), any())).thenReturn(1);
    com.navneet.executespec.config.AgentProperties agentProperties =
        mock(com.navneet.executespec.config.AgentProperties.class);
    try {
      Path workspace = Files.createTempDirectory("executespec-stale-task-");
      Files.createDirectories(workspace.resolve(run.getRunId()));
      when(agentProperties.getWorkspace()).thenReturn(workspace.toString());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo,
            taskRepo,
            null,
            null,
            null,
            controlPort,
            agentProperties,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.PLAN_MISSING);
    verify(taskRepo).claimStaleForRecovery(anyString(), any(), any());
    verify(controlPort).terminate(run.getRunId(), "FAILED", "plan missing on resume", null);
  }

  @Test
  void aLostRecoveryClaimDoesNotTouchWorkspaceOrQueueAnotherPipeline() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("crash-claim-race")
            .status("WORKING")
            .harnessVersion("v2")
            .resumeAttempts(0)
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    com.navneet.executespec.sse.SseBroadcaster broadcaster =
        mock(com.navneet.executespec.sse.SseBroadcaster.class);
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(runRepo.claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString()))
        .thenReturn(0);

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo,
            null,
            broadcaster,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE);
    assertThat(run.getResumeAttempts()).isZero();
    verify(runRepo, never()).save(any(PipelineRun.class));
    verify(runRepo).claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString());
    org.mockito.ArgumentCaptor<RunEvent> event =
        org.mockito.ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(anyString(), event.capture());
    assertThat(event.getValue().type()).isEqualTo("RECOVERY_ATTEMPTED");
    assertThat(event.getValue().payload()).isEqualTo("CROSS_INSTANCE_LEASE_LOST|REFUSED|locks=0");
  }

  @Test
  void onlyOneRecoveryInstanceMayAdvanceTheAttemptBeforeResumeWork() throws Exception {
    Path workspaceRoot = Files.createTempDirectory("executespec-crash-claim-");
    Files.createDirectories(workspaceRoot.resolve("crash-claim-owner"));
    PipelineRun run =
        PipelineRun.builder()
            .runId("crash-claim-owner")
            .status("WORKING")
            .harnessVersion("v2")
            .resumeAttempts(0)
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    RunControlPort controlPort = mock(RunControlPort.class);
    com.navneet.executespec.config.AgentProperties agentProperties =
        mock(com.navneet.executespec.config.AgentProperties.class);
    when(agentProperties.getWorkspace()).thenReturn(workspaceRoot.toString());
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    // Simulates two JVMs racing on the same row: the first wins the CAS, the second loses it.
    when(runRepo.claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString()))
        .thenReturn(1, 0);

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo,
            null,
            null,
            null,
            null,
            controlPort,
            agentProperties,
            new com.fasterxml.jackson.databind.ObjectMapper(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.PLAN_MISSING);
    assertThat(run.getResumeAttempts()).isEqualTo(1);
    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE);
    Mockito.verify(controlPort).terminate(run.getRunId(), "FAILED", "plan missing on resume", null);
    Mockito.verify(runRepo, Mockito.times(2))
        .claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString());
  }

  @Test
  void taskLeaseLossAfterRunClaimRefusesDuplicateResume() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("task-lease-race")
            .status("WORKING")
            .harnessVersion("v2")
            .resumeAttempts(0)
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId("task-lease-race_T1")
            .runId(run.getRunId())
            .status("WORKING")
            .lastActivityAt(Instant.now().minus(Duration.ofMinutes(10)))
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    RunControlPort controlPort = mock(RunControlPort.class);
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(runRepo.claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString()))
        .thenReturn(1);
    when(taskRepo.findByRunIdAndStatusIn(run.getRunId(), List.of("WORKING", "LOCKED")))
        .thenReturn(List.of(task));
    when(taskRepo.claimStaleForRecovery(anyString(), any(), any())).thenReturn(0);

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo, taskRepo, null, null, null, controlPort, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE);
    verify(taskRepo).claimStaleForRecovery(anyString(), any(), any());
    verify(controlPort, never()).runPipelineForResume(any());
  }

  @Test
  void exhaustedRecoveryAttemptsTerminallyFailWithoutAnotherClaim() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("crash-claim-exhausted")
            .status("WORKING")
            .harnessVersion("v2")
            .resumeAttempts(com.navneet.executespec.recovery.StartupRunResumer.MAX_RESUME_ATTEMPTS)
            .build();
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    RunControlPort controlPort = mock(RunControlPort.class);
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo,
            null,
            null,
            null,
            null,
            controlPort,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.EXHAUSTED);
    verify(controlPort)
        .terminate(
            run.getRunId(),
            "FAILED",
            "resume cap reached after "
                + com.navneet.executespec.recovery.StartupRunResumer.MAX_RESUME_ATTEMPTS
                + " attempts",
            null);
    verify(runRepo, never())
        .claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString());
  }
}
