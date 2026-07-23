package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.junit.jupiter.api.Test;

class ValidationReportEvidenceFailureTest {

  @Test
  void noProductDiffFailsEvenWhenOtherSignalsCouldLookHealthy() {
    assertThat(ValidationReportService.scoreEvidenceFailure(0, null))
        .contains("No approved product-code diff");
  }

  @Test
  void deterministicZeroAdherenceFailsClosed() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(0)
            .missingRequirements(List.of("Persist calculator history"))
            .build();
    assertThat(ValidationReportService.scoreEvidenceFailure(1, accuracy))
        .contains("Spec adherence is zero");
  }
}
