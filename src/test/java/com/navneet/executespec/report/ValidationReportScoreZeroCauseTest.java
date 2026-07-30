package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;

import org.junit.jupiter.api.Test;

class ValidationReportScoreZeroCauseTest {

  @Test
  void noProductDiffIsTheOnlyCausalGate() {
    var evidence =
        ValidationReportScoreZeroCause.derive(
            0,
            false,
            0,
            false,
            0,
            5,
            true,
            new BootVerification(Verdict.BUILD_FAILED, false, "red"));

    assertThat(evidence.causes()).hasSize(1);
    assertThat(evidence.causes().getFirst().gate()).isEqualTo("CODE_DELIVERED");
  }

  @Test
  void bootEvidenceSupersedesCompileEvidence() {
    var evidence =
        ValidationReportScoreZeroCause.derive(
            0,
            false,
            2,
            false,
            1,
            1,
            true,
            new BootVerification(Verdict.BUILD_FAILED, false, "red"));

    assertThat(evidence.causes())
        .extracting(ValidationReportScoreZeroCause.Cause::gate)
        .containsExactly("BOOT_BUILD");
  }

  @Test
  void unverifiedFailedTestsCannotExplainZero() {
    assertThat(ValidationReportScoreZeroCause.derive(0, false, 2, true, 0, 5, false, null))
        .isNull();
  }

  @Test
  void nonzeroNeverCarriesZeroCause() {
    assertThat(ValidationReportScoreZeroCause.derive(1, false, 0, false, 0, 5, true, null))
        .isNull();
  }
}
