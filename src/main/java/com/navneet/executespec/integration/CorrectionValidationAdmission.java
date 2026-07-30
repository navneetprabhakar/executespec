package com.navneet.executespec.integration;

import java.nio.file.Path;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.ReviewerResult;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Regenerates and admits validation evidence before a correction workspace can be promoted.
 *
 * <p>{@link ValidationReport#getStatus()} describes report-generation completion; it is not the
 * validation verdict. A completed report with deterministic {@link PipelineResult#FAILED} evidence
 * must therefore roll the correction back.
 */
@Component
@RequiredArgsConstructor
class CorrectionValidationAdmission {

  private final RunValidationKickoff validationKickoff;
  private final CorrectionWorkspaceTransaction workspaceTransaction;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  private final DeterministicHttpRequirementVerifier deterministicHttpRequirementVerifier;

  void assertPreReview(String runId, Plan plan, String originalRequirement, Path workspace) {
    deterministicHttpRequirementVerifier.assertSatisfied(plan, originalRequirement, workspace);
    if (brownfieldHttpContractGuard.hasBaseline(runId)) {
      brownfieldHttpContractGuard.assertCompatible(runId);
    }
  }

  ValidationReport regenerateAndAssert(
      String runId,
      ExecutionResult execution,
      ReviewerResult review,
      Plan plan,
      CorrectionWorkspaceTransaction.Checkpoint checkpoint,
      String reviewerWorkspaceGeneration) {
    ValidationReport regenerated =
        validationKickoff
            .generateCorrectionValidationReport(
                runId, execution, review, plan, reviewerWorkspaceGeneration)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "CORRECTION_VALIDATION_MISSING: correction validation did not persist"));
    String currentGeneration = workspaceTransaction.currentGeneration(checkpoint);
    if (!reviewerWorkspaceGeneration.equals(currentGeneration)
        || !currentGeneration.equals(regenerated.getWorkspaceGeneration())) {
      throw new IllegalStateException(
          "CORRECTION_VALIDATION_GENERATION_MISMATCH: reviewer, validation and workspace"
              + " generations differ");
    }
    if (!"COMPLETE".equalsIgnoreCase(regenerated.getStatus())) {
      throw new IllegalStateException(
          "CORRECTION_VALIDATION_INCOMPLETE: regenerated validation is not complete");
    }
    if (regenerated.getPipelineResult() == PipelineResult.FAILED) {
      throw new IllegalStateException(
          "CORRECTION_VALIDATION_FAILED: regenerated deterministic validation failed");
    }
    assertCorrectionTestsPersisted(regenerated);
    deterministicHttpRequirementVerifier.assertSatisfied(plan, null, checkpoint);
    if (brownfieldHttpContractGuard.hasBaseline(runId)) {
      brownfieldHttpContractGuard.assertCompatible(runId);
    }
    return regenerated;
  }

  private static void assertCorrectionTestsPersisted(ValidationReport report) {
    if (!Boolean.TRUE.equals(report.getTestExecutionVerified())
        || report.getTestsTotal() == null
        || report.getTestsTotal() <= 0
        || report.getTestsPassed() == null
        || !report.getTestsPassed().equals(report.getTestsTotal())
        || !"WORKSPACE_TEST_SUITE".equals(report.getTestEvidenceScope())
        || report.getTestCommand() == null
        || report.getTestCommand().isBlank()
        || report.getTestExitCode() == null
        || report.getTestExitCode() != 0) {
      throw new IllegalStateException(
          "CORRECTION_TEST_EVIDENCE_UNPROVEN: regenerated correction validation did not persist"
              + " a complete green workspace-suite command and provenance");
    }
  }
}
