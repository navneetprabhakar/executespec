package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.ReviewerResult;

import org.junit.jupiter.api.Test;

class CorrectionValidationAdmissionTest {

  private static final String RUN_ID = "run-correction";
  private static final String GENERATION = "a".repeat(64);

  private final RunValidationKickoff validationKickoff = mock(RunValidationKickoff.class);
  private final CorrectionWorkspaceTransaction workspace =
      mock(CorrectionWorkspaceTransaction.class);
  private final BrownfieldHttpContractGuard brownfieldGuard =
      mock(BrownfieldHttpContractGuard.class);
  private final DeterministicHttpRequirementVerifier httpRequirementVerifier =
      mock(DeterministicHttpRequirementVerifier.class);
  private final CorrectionValidationAdmission admission =
      new CorrectionValidationAdmission(
          validationKickoff, workspace, brownfieldGuard, httpRequirementVerifier);

  @Test
  void completedReportWithFailedDeterministicValidationRejectsCorrection() {
    ExecutionResult execution =
        ExecutionResult.builder().overall(ExecutionResult.Overall.ALL_SUCCESS).build();
    ReviewerResult review = ReviewerResult.builder().overall(ReviewerResult.Overall.CLEAN).build();
    Plan plan = Plan.builder().planTitle("Correction validation").build();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = checkpoint();
    ValidationReport report = report(PipelineResult.FAILED);
    when(validationKickoff.generateCorrectionValidationReport(
            RUN_ID, execution, review, plan, GENERATION))
        .thenReturn(Optional.of(report));
    when(workspace.currentGeneration(checkpoint)).thenReturn(GENERATION);

    assertThatThrownBy(
            () ->
                admission.regenerateAndAssert(
                    RUN_ID, execution, review, plan, checkpoint, GENERATION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CORRECTION_VALIDATION_FAILED");
    verify(brownfieldGuard, never()).assertCompatible(RUN_ID);
  }

  @Test
  void passedDeterministicValidationBoundToCurrentGenerationIsAdmitted() {
    ExecutionResult execution =
        ExecutionResult.builder().overall(ExecutionResult.Overall.ALL_SUCCESS).build();
    ReviewerResult review = ReviewerResult.builder().overall(ReviewerResult.Overall.CLEAN).build();
    Plan plan = Plan.builder().planTitle("Correction validation").build();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = checkpoint();
    ValidationReport report = report(PipelineResult.PASSED);
    when(validationKickoff.generateCorrectionValidationReport(
            RUN_ID, execution, review, plan, GENERATION))
        .thenReturn(Optional.of(report));
    when(workspace.currentGeneration(checkpoint)).thenReturn(GENERATION);
    when(brownfieldGuard.hasBaseline(RUN_ID)).thenReturn(true);

    assertThat(
            admission.regenerateAndAssert(RUN_ID, execution, review, plan, checkpoint, GENERATION))
        .isSameAs(report);
    verify(httpRequirementVerifier).assertSatisfied(plan, null, checkpoint);
    verify(brownfieldGuard).assertCompatible(RUN_ID);
  }

  @Test
  void completedReportWithoutPersistedCorrectionTestProvenanceIsRejected() {
    ExecutionResult execution =
        ExecutionResult.builder().overall(ExecutionResult.Overall.ALL_SUCCESS).build();
    ReviewerResult review = ReviewerResult.builder().overall(ReviewerResult.Overall.CLEAN).build();
    Plan plan = Plan.builder().planTitle("Correction validation").build();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = checkpoint();
    ValidationReport report = report(PipelineResult.PASSED);
    report.setTestExecutionVerified(false);
    report.setTestCommand(null);
    when(validationKickoff.generateCorrectionValidationReport(
            RUN_ID, execution, review, plan, GENERATION))
        .thenReturn(Optional.of(report));
    when(workspace.currentGeneration(checkpoint)).thenReturn(GENERATION);

    assertThatThrownBy(
            () ->
                admission.regenerateAndAssert(
                    RUN_ID, execution, review, plan, checkpoint, GENERATION))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CORRECTION_TEST_EVIDENCE_UNPROVEN");
    verify(httpRequirementVerifier, never()).assertSatisfied(plan, null, checkpoint);
  }

  private static ValidationReport report(PipelineResult pipelineResult) {
    return ValidationReport.builder()
        .id("report-1")
        .runId(RUN_ID)
        .status("COMPLETE")
        .pipelineResult(pipelineResult)
        .workspaceGeneration(GENERATION)
        .finalBuildPassed(true)
        .testsPassed(4)
        .testsTotal(4)
        .testExecutionVerified(true)
        .testEvidenceScope("WORKSPACE_TEST_SUITE")
        .testCommand("./mvnw test")
        .testWorkingDirectory("/tmp/correction-validation-test")
        .testExitCode(0)
        .build();
  }

  private static CorrectionWorkspaceTransaction.Checkpoint checkpoint() {
    return new CorrectionWorkspaceTransaction.Checkpoint(
        Path.of("/tmp/correction-validation-test"),
        List.of(
            new CorrectionWorkspaceTransaction.RepositoryCheckpoint(
                Path.of("/tmp/correction-validation-test"), "head", "tree")));
  }
}
