package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.lint.LintPass;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AutomaticCorrectionLifecycleEvidenceTest {

  @Test
  void invalidatesPreviousEvidenceAndRestoresExactSnapshotOnRollback() {
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    ValidationReport original =
        ValidationReport.builder()
            .id("report-1")
            .runId("run-1")
            .status("COMPLETE")
            .workspaceGeneration("a".repeat(64))
            .verifiedScore(91)
            .finalBuildPassed(true)
            .testsPassed(4)
            .testsTotal(4)
            .testExecutionVerified(true)
            .reviewerVerdict("CLEAN")
            .build();
    when(reports.findByRunId("run-1")).thenReturn(Optional.of(original));
    AutomaticCorrectionLifecycle lifecycle = lifecycle(reports);

    AutomaticCorrectionLifecycle.EvidenceCheckpoint checkpoint =
        lifecycle.invalidateEvidence("run-1");
    lifecycle.restoreEvidence(checkpoint);

    ArgumentCaptor<ValidationReport> saved = ArgumentCaptor.forClass(ValidationReport.class);
    verify(reports, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
    assertThat(saved.getAllValues().get(0).getStatus()).isEqualTo("STALE");
    assertThat(saved.getAllValues().get(0).getWorkspaceGeneration()).isNull();
    assertThat(saved.getAllValues().get(0).getFinalBuildPassed()).isNull();
    assertThat(saved.getAllValues().get(0).getTestsTotal()).isNull();
    assertThat(saved.getAllValues().get(0).getTestExecutionVerified()).isNull();
    assertThat(saved.getAllValues().get(0).getReviewerVerdict()).isNull();
    assertThat(saved.getAllValues().get(0).getVerifiedScore()).isNull();
    assertThat(saved.getAllValues().get(0).getFilesGenerated()).isEqualTo(-1);
    assertThat(saved.getAllValues().get(0).getAccuracyJson()).isNull();
    assertThat(saved.getAllValues().get(1).getStatus()).isEqualTo("COMPLETE");
    assertThat(saved.getAllValues().get(1).getWorkspaceGeneration()).isEqualTo("a".repeat(64));
    assertThat(saved.getAllValues().get(1).getVerifiedScore()).isEqualTo(91);
    assertThat(saved.getAllValues().get(1).getFinalBuildPassed()).isTrue();
    assertThat(saved.getAllValues().get(1).getTestsTotal()).isEqualTo(4);
    assertThat(saved.getAllValues().get(1).getReviewerVerdict()).isEqualTo("CLEAN");
    assertThat(checkpoint.previous().getStatus()).isEqualTo("COMPLETE");
  }

  @Test
  void rollbackDeletesEvidenceCreatedWhenNoPreviousReportExisted() {
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    ValidationReport generated =
        ValidationReport.builder().id("report-new").runId("run-2").status("COMPLETE").build();
    when(reports.findByRunId("run-2"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(generated));
    AutomaticCorrectionLifecycle lifecycle = lifecycle(reports);

    lifecycle.restoreEvidence(lifecycle.invalidateEvidence("run-2"));

    verify(reports).delete(generated);
    verify(reports).flush();
  }

  @Test
  void capturesRejectedRegeneratedReportBeforePreviousEvidenceIsRestored() {
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    ValidationReport original =
        ValidationReport.builder()
            .id("report-3")
            .runId("run-3")
            .status("COMPLETE")
            .workspaceGeneration("a".repeat(64))
            .finalBuildPassed(true)
            .testsPassed(4)
            .testsTotal(4)
            .reviewerVerdict("CLEAN")
            .verifiedScore(88)
            .rubricScore(86)
            .build();
    ValidationReport rejected =
        ValidationReport.builder()
            .id("report-3")
            .runId("run-3")
            .status("COMPLETE")
            .workspaceGeneration("b".repeat(64))
            .pipelineResult(PipelineResult.FAILED)
            .finalBuildPassed(false)
            .testsPassed(3)
            .testsTotal(4)
            .reviewerVerdict("UNRESOLVED_ISSUES")
            .verifiedScore(61)
            .rubricScore(65)
            .build();
    when(reports.findByRunId("run-3"))
        .thenReturn(Optional.of(original))
        .thenReturn(Optional.of(rejected));
    AutomaticCorrectionLifecycle lifecycle = lifecycle(reports);

    var checkpoint = lifecycle.invalidateEvidence("run-3");
    var delta = lifecycle.captureValidationDelta(checkpoint);
    lifecycle.restoreEvidence(checkpoint);

    assertThat(delta.before().workspaceGeneration()).isEqualTo("a".repeat(64));
    assertThat(delta.after().workspaceGeneration()).isEqualTo("b".repeat(64));
    assertThat(delta.after().build().verdict()).isEqualTo("FAILED");
    assertThat(delta.after().tests().verdict()).isEqualTo("FAILED");
    assertThat(delta.after().reviewer().verdict()).isEqualTo("UNRESOLVED_ISSUES");
    assertThat(delta.verifiedScoreDelta()).isEqualTo(-27);
    assertThat(delta.rubricScoreDelta()).isEqualTo(-21);
    verify(reports, org.mockito.Mockito.times(2))
        .saveAndFlush(org.mockito.ArgumentMatchers.any(ValidationReport.class));
  }

  @Test
  void rejectedCorrectionRestoresPrimaryEvidenceButRetainsSpendAndActionHistory() {
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    AgentTask primary =
        AgentTask.builder()
            .taskId("run-4:task-1")
            .status("COMPLETE")
            .workerReportJson("primary-report")
            .completionNotes("primary notes")
            .provider("openrouter")
            .model("deepseek")
            .effectiveProvider("openrouter")
            .effectiveModel("deepseek")
            .effectiveRouteAttribution("LEDGER")
            .routingTier("WORKHORSE")
            .routingTaskCategory("FEATURE")
            .routingSource("MATRIX")
            .currentProvider("openrouter")
            .currentModel("deepseek")
            .goalDonenessJson("primary-goal")
            .continuationDecisionJson("primary-decision")
            .inputTokens(100)
            .continuationActionsJson("[\"primary\"]")
            .build();
    AutomaticCorrectionLifecycle.TaskState snapshot =
        AutomaticCorrectionLifecycle.snapshotTask(primary);
    AgentTask rejected =
        AgentTask.builder()
            .taskId(primary.getTaskId())
            .status("FAILED")
            .workerReportJson("rejected-report")
            .provider("anthropic")
            .model("sonnet")
            .effectiveProvider("anthropic")
            .effectiveModel("sonnet")
            .inputTokens(350)
            .continuationActionsJson("[\"primary\",\"rejected-attempt\"]")
            .build();
    when(tasks.findById(primary.getTaskId())).thenReturn(Optional.of(rejected));
    AutomaticCorrectionLifecycle lifecycle =
        lifecycle(mock(ValidationReportRepository.class), tasks);

    lifecycle.restoreTask(primary.getTaskId(), snapshot);

    assertThat(rejected.getStatus()).isEqualTo("COMPLETE");
    assertThat(rejected.getWorkerReportJson()).isEqualTo("primary-report");
    assertThat(rejected.getProvider()).isEqualTo("openrouter");
    assertThat(rejected.getEffectiveModel()).isEqualTo("deepseek");
    assertThat(rejected.getGoalDonenessJson()).isEqualTo("primary-goal");
    assertThat(rejected.getInputTokens()).isEqualTo(350);
    assertThat(rejected.getContinuationActionsJson())
        .isEqualTo("[\"primary\",\"rejected-attempt\"]");
    verify(tasks).save(rejected);
  }

  @Test
  void rollbackEvidenceFailureReleasesDurableAuthorityForRecovery() {
    CorrectionWorkspaceTransaction workspace = mock(CorrectionWorkspaceTransaction.class);
    var checkpoint =
        new CorrectionWorkspaceTransaction.Checkpoint(
            List.of(
                new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                    Path.of("/tmp/correction-test"), "head", "tree")));
    when(workspace.begin(any(Path.class))).thenReturn(checkpoint);
    ValidationReport previous =
        ValidationReport.builder()
            .id("report-recovery")
            .runId("run-recovery")
            .status("COMPLETE")
            .workspaceGeneration(checkpoint.generation())
            .build();
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    when(reports.findByRunId("run-recovery")).thenReturn(Optional.of(previous));
    doThrow(new IllegalStateException("restore unavailable"))
        .when(reports)
        .saveAndFlush(any(ValidationReport.class));
    DurableCorrectionLifecycle durable = mock(DurableCorrectionLifecycle.class);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(durable.prepare("run-recovery", "task-recovery", Path.of("/tmp/correction-test")))
        .thenReturn(
            new DurableCorrectionLifecycle.Prepared(
                UUID.fromString("44444444-4444-4444-4444-444444444444"), checkpoint, lease));
    AutomaticCorrectionLifecycle lifecycle =
        new AutomaticCorrectionLifecycle(
            workspace,
            mock(AgentTaskRepository.class),
            new ObjectMapper(),
            mock(FeatureFlagService.class),
            mock(LintPass.class),
            mock(SseBroadcaster.class),
            mock(WorkflowConfigEvidenceInspector.class),
            reports,
            mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
            durable);
    lifecycle.begin("run-recovery", "task-recovery", Path.of("/tmp/correction-test"));

    assertThatThrownBy(
            () ->
                lifecycle.rollbackRejectedAttempt(
                    "task-recovery",
                    checkpoint,
                    new AutomaticCorrectionLifecycle.EvidenceCheckpoint("run-recovery", previous),
                    null,
                    null,
                    CorrectionAcceptancePolicy.Decision.reject("REJECTED", "test"),
                    null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restore unavailable");

    verify(lease).close();
  }

  private static AutomaticCorrectionLifecycle lifecycle(ValidationReportRepository reports) {
    return lifecycle(reports, mock(AgentTaskRepository.class));
  }

  private static AutomaticCorrectionLifecycle lifecycle(
      ValidationReportRepository reports, AgentTaskRepository tasks) {
    return new AutomaticCorrectionLifecycle(
        mock(CorrectionWorkspaceTransaction.class),
        tasks,
        new ObjectMapper(),
        mock(FeatureFlagService.class),
        mock(LintPass.class),
        mock(SseBroadcaster.class),
        mock(WorkflowConfigEvidenceInspector.class),
        reports,
        mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class),
        mock(DurableCorrectionLifecycle.class));
  }
}
