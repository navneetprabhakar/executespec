package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

class RunOrchestratorExecutableVerificationTest {

  @Test
  void cleanCompletionCannotOverrideFailedExecutableEvidence() {
    ValidationReport report =
        ValidationReport.builder()
            .executableVerificationStatus("ASSERTION_FAILED")
            .executableTestsRun(true)
            .executableTestsPassed(false)
            .build();

    RunOrchestrator.StatusAndReason result =
        RunOrchestrator.applyExecutableVerification(
            new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION"), report);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.reason()).isEqualTo("EXECUTABLE_VERIFICATION_FAILED:ASSERTION_FAILED");
  }

  @Test
  void missingEvidenceFailsClosed() {
    ValidationReport report =
        ValidationReport.builder()
            .executableVerificationStatus("MISSING_EVIDENCE")
            .executableTestsRun(false)
            .executableTestsPassed(false)
            .build();

    RunOrchestrator.StatusAndReason result =
        RunOrchestrator.applyExecutableVerification(
            new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION"), report);

    assertThat(result.status()).isEqualTo("FAILED");
  }

  @Test
  void absentValidationReportFailsClosed() {
    RunOrchestrator.StatusAndReason result =
        RunOrchestrator.applyExecutableVerification(
            new RunOrchestrator.StatusAndReason("COMPLETED", "CLEAN_COMPLETION"), null);

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.reason()).isEqualTo("EXECUTABLE_VERIFICATION_FAILED:MISSING_EVIDENCE");
  }

  @Test
  void passingEvidencePreservesExistingProviderIndependentStatus() {
    ValidationReport report =
        ValidationReport.builder()
            .executableVerificationStatus("PASSED")
            .executableTestsRun(true)
            .executableTestsPassed(true)
            .build();
    RunOrchestrator.StatusAndReason clean =
        new RunOrchestrator.StatusAndReason("COMPLETE", "CLEAN_COMPLETION");

    assertThat(RunOrchestrator.applyExecutableVerification(clean, report)).isEqualTo(clean);
  }
}
