package com.navneet.executespec.integration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ContinuationApprovalRecoveryServiceTest {

  private final AgentTaskRepository tasks = mock(AgentTaskRepository.class);
  private final PipelineRunRepository runs = mock(PipelineRunRepository.class);
  private final ValidationReportRepository reports = mock(ValidationReportRepository.class);
  private final ContinuationApprovalRecoveryService recovery =
      new ContinuationApprovalRecoveryService(tasks, runs, reports);

  @BeforeEach
  void configure() {
    ReflectionTestUtils.setField(recovery, "approvalStaleAfter", Duration.ofHours(4));
  }

  @Test
  void staleClaimedReceiptIsReleasedBeforeAnyWorkerCouldStart() {
    AgentTask task = approval("CLAIMED");
    when(tasks.findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
            eq("CLAIMED"), any()))
        .thenReturn(List.of(task));
    when(tasks.findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
            eq("QUEUED"), any()))
        .thenReturn(List.of());

    recovery.recoverStaleApprovals();

    verify(tasks)
        .releaseStaleClaimedContinuationApproval(
            eq(task.getTaskId()), eq("approval-1"), eq("a".repeat(64)), any());
    verify(runs, never()).claimTerminalTransitionWithReasons(any(), any(), any(), any(), any());
  }

  @Test
  void staleQueuedReceiptFailsClosedAndInvalidatesEvidence() {
    AgentTask task = approval("QUEUED");
    ValidationReport report =
        ValidationReport.builder().id("report-1").runId(task.getRunId()).status("COMPLETE").build();
    when(tasks.findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
            eq("CLAIMED"), any()))
        .thenReturn(List.of());
    when(tasks.findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
            eq("QUEUED"), any()))
        .thenReturn(List.of(task));
    when(runs.findById(task.getRunId()))
        .thenReturn(
            Optional.of(
                PipelineRun.builder()
                    .runId(task.getRunId())
                    .status("WORKING")
                    .executionHeartbeat(Instant.now().minus(Duration.ofHours(5)))
                    .build()));
    when(tasks.failStaleQueuedContinuationApproval(
            eq(task.getTaskId()), eq("approval-1"), eq("a".repeat(64)), any(), any()))
        .thenReturn(1);
    when(reports.findByRunId(task.getRunId())).thenReturn(Optional.of(report));

    recovery.recoverStaleApprovals();

    verify(reports).save(report);
    verify(runs)
        .claimTerminalTransitionWithReasons(
            eq(task.getRunId()),
            eq("FAILED"),
            eq(ContinuationApprovalRecoveryService.FAILURE_REASON),
            eq(ContinuationApprovalRecoveryService.FAILURE_REASON),
            any());
    org.assertj.core.api.Assertions.assertThat(report.getStatus()).isEqualTo("STALE");
  }

  @Test
  void freshRunHeartbeatPreventsRecoveryFromRacingLiveWorker() {
    AgentTask task = approval("QUEUED");
    when(tasks.findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
            eq("CLAIMED"), any()))
        .thenReturn(List.of());
    when(tasks.findByContinuationApprovalStatusAndContinuationApprovalUpdatedAtBefore(
            eq("QUEUED"), any()))
        .thenReturn(List.of(task));
    when(runs.findById(task.getRunId()))
        .thenReturn(
            Optional.of(
                PipelineRun.builder()
                    .runId(task.getRunId())
                    .executionHeartbeat(Instant.now())
                    .build()));

    recovery.recoverStaleApprovals();

    verify(tasks, never()).failStaleQueuedContinuationApproval(any(), any(), any(), any(), any());
  }

  private static AgentTask approval(String status) {
    return AgentTask.builder()
        .taskId("run12345_T1")
        .runId("run12345")
        .continuationApprovalId("approval-1")
        .continuationApprovalProposalDigest("a".repeat(64))
        .continuationApprovalStatus(status)
        .continuationApprovalUpdatedAt(Instant.now().minus(Duration.ofHours(5)))
        .build();
  }
}
