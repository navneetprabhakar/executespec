package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;

/** Regression coverage for the rolling-restart crash-recovery ownership claim. */
class RunLifecycleServiceCrashClaimTest {

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
    when(runRepo.findById(run.getRunId())).thenReturn(Optional.of(run));
    when(runRepo.claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString()))
        .thenReturn(0);

    RunLifecycleService service =
        new RunLifecycleService(
            runRepo, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null);

    assertThat(service.resumeAfterCrash(run.getRunId()))
        .isEqualTo(RunOrchestrator.CrashResumeOutcome.NOT_APPLICABLE);
    assertThat(run.getResumeAttempts()).isZero();
    verify(runRepo, never()).save(any(PipelineRun.class));
    verify(runRepo).claimCrashResume(anyString(), anyInt(), anyInt(), any(), any(), anyString());
  }
}
