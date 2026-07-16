package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.smoketest.ExecutableVerification;
import com.navneet.executespec.smoketest.ExecutableVerification.FailureStage;
import com.navneet.executespec.smoketest.ExecutableVerification.Status;

import org.junit.jupiter.api.Test;

class ValidationReportExecutableVerificationTest {

  @Test
  void failedExecutableEvidenceZerosAnOtherwiseCleanScore() {
    ExecutableVerification failed = verification(Status.ASSERTION_FAILED, false);
    assertThat(ValidationReportService.enforceExecutableVerificationScore(100, failed)).isZero();
  }

  @Test
  void missingEvidenceZerosAnOtherwiseCleanScore() {
    ExecutableVerification missing = verification(Status.MISSING_EVIDENCE, false);
    assertThat(ValidationReportService.enforceExecutableVerificationScore(91, missing)).isZero();
  }

  @Test
  void acceptedExecutableEvidencePreservesScore() {
    ExecutableVerification passed = verification(Status.PASSED, true);
    assertThat(ValidationReportService.enforceExecutableVerificationScore(87, passed))
        .isEqualTo(87);
  }

  @Test
  void historicalReportWithoutE2EvidencePreservesScore() {
    assertThat(ValidationReportService.enforceExecutableVerificationScore(76, null)).isEqualTo(76);
  }

  private static ExecutableVerification verification(Status status, boolean passed) {
    return new ExecutableVerification(
        status,
        passed ? FailureStage.NONE : FailureStage.TEST_ASSERTION,
        true,
        passed,
        null,
        "PYTEST",
        "test",
        "python3 -m pytest -q",
        passed ? 0 : 1,
        10,
        "",
        "test");
  }
}
