package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.integration.RunLlmOperationLifecycleService;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BuildContinuationAdmissionProtocolTest {

  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final AgentTaskRepository tasks = mock(AgentTaskRepository.class);
  private final WorkspaceGenerationAttemptRepository attempts =
      mock(WorkspaceGenerationAttemptRepository.class);
  private final BuildContinuationAdmissionService service =
      new BuildContinuationAdmissionService(
          runs, tasks, attempts, mock(RunLlmOperationLifecycleService.class));

  @Test
  void physicalAttemptCopiesTheExactPinnedRunProtocol() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("12345678-run")
            .status("WORKING")
            .buildProtocolVersion("SHARED_KERNEL_V2")
            .build();
    AgentTask task =
        AgentTask.builder()
            .taskId("12345678_T1")
            .runId(run.getRunId())
            .status("PENDING")
            .recoveryAttempt(0)
            .build();
    when(runs.findByIdForTaskContinuation(run.getRunId())).thenReturn(Optional.of(run));
    when(tasks.findByIdForUpdate(task.getTaskId())).thenReturn(Optional.of(task));
    when(attempts.maximumAttemptNumber(run.getRunId(), "T1")).thenReturn(0);
    when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    BuildContinuationAdmissionService.Admission admission =
        service.admitAttempt(request(run.getRunId()));

    assertThat(admission.admitted()).isTrue();
    ArgumentCaptor<WorkspaceGenerationAttempt> saved =
        ArgumentCaptor.forClass(WorkspaceGenerationAttempt.class);
    verify(attempts).saveAndFlush(saved.capture());
    assertThat(saved.getValue().getBuildProtocolVersion()).isEqualTo("SHARED_KERNEL_V2");
    assertThat(saved.getValue().getVerificationCommandProtocolVersion()).isEqualTo((short) 2);
  }

  @Test
  void unpinnedHistoricalRunCannotStartANewCandidateAttempt() {
    PipelineRun run = PipelineRun.builder().runId("12345678-run").status("WORKING").build();
    when(runs.findByIdForTaskContinuation(run.getRunId())).thenReturn(Optional.of(run));

    assertThatThrownBy(() -> service.admitAttempt(request(run.getRunId())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BUILD_PROTOCOL_VERSION_UNPINNED");
  }

  private static BuildContinuationAdmissionService.AttemptRequest request(String runId) {
    return new BuildContinuationAdmissionService.AttemptRequest(
        runId,
        "T1",
        0,
        Path.of("/tmp/protocol-test"),
        "a".repeat(64),
        "b".repeat(64),
        null,
        null,
        "anthropic",
        "claude-sonnet",
        null,
        BuildContinuationAdmissionService.AdmissionKind.INITIAL);
  }
}
