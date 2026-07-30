package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.junit.jupiter.api.Test;

class ValidationReportEvidenceFailureTest {

  @Test
  void noProductDiffFailsEvenWhenOtherSignalsCouldLookHealthy() {
    assertThat(ValidationReportService.scoreEvidenceFailure(0, null))
        .contains("No approved product-code diff");
  }

  @Test
  void llmZeroAdherenceCannotBecomeDeterministicProvenanceFailure() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(0)
            .missingRequirements(List.of("Persist calculator history"))
            .build();
    assertThat(ValidationReportService.scoreEvidenceFailure(1, accuracy)).isNull();
  }

  @Test
  void llmEndpointCoverageCannotBecomeDeterministicProvenanceFailure() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .endpointCoverage(
                AccuracyReport.EndpointCoverage.builder()
                    .required(List.of("POST /orders"))
                    .missing(List.of("POST /orders"))
                    .coveragePercent(0)
                    .build())
            .build();

    assertThat(ValidationReportService.scoreEvidenceFailure(1, accuracy)).isNull();
  }

  @Test
  void buildProvenanceDistinguishesUnavailableFromExplicitFailure() {
    assertThat(ValidationReportService.finalBuildPassed(null)).isNull();
    assertThat(ValidationReportService.finalBuildPassed(CompileResult.skip())).isNull();
    assertThat(ValidationReportService.finalBuildPassed(CompileResult.failed(1, "compile error")))
        .isFalse();
    assertThat(ValidationReportService.finalBuildPassed(CompileResult.passed(1))).isTrue();
  }
}
