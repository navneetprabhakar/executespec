package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.lint.LintPass;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.sse.SseBroadcaster;
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
            .build();
    when(reports.findByRunId("run-1")).thenReturn(Optional.of(original));
    AutomaticCorrectionLifecycle lifecycle = lifecycle(reports);

    AutomaticCorrectionLifecycle.EvidenceCheckpoint checkpoint =
        lifecycle.invalidateEvidence("run-1");
    lifecycle.restoreEvidence(checkpoint);

    ArgumentCaptor<ValidationReport> saved = ArgumentCaptor.forClass(ValidationReport.class);
    verify(reports, org.mockito.Mockito.times(2)).saveAndFlush(saved.capture());
    assertThat(saved.getAllValues().get(0).getStatus()).isEqualTo("STALE");
    assertThat(saved.getAllValues().get(1).getStatus()).isEqualTo("COMPLETE");
    assertThat(saved.getAllValues().get(1).getWorkspaceGeneration()).isEqualTo("a".repeat(64));
    assertThat(saved.getAllValues().get(1).getVerifiedScore()).isEqualTo(91);
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

  private static AutomaticCorrectionLifecycle lifecycle(ValidationReportRepository reports) {
    return new AutomaticCorrectionLifecycle(
        mock(CorrectionWorkspaceTransaction.class),
        mock(AgentTaskRepository.class),
        new ObjectMapper(),
        mock(FeatureFlagService.class),
        mock(LintPass.class),
        mock(SseBroadcaster.class),
        mock(WorkflowConfigEvidenceInspector.class),
        reports,
        mock(com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard.class));
  }
}
