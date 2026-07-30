package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

class ValidationEndpointCoverageEvidenceTest {

  @Test
  void nonHttpSurfaceIsExplicitlyNotApplicable() {
    var report =
        ValidationReport.builder()
            .runtimeSurface("CLI")
            .workspaceGeneration("a".repeat(64))
            .build();

    var evidence = ValidationEndpointCoverageEvidence.build(report, 100);

    assertThat(evidence.status()).isEqualTo("N/A");
    assertThat(evidence.source()).isEqualTo("NOT_APPLICABLE");
    assertThat(evidence.confidence()).isEqualTo("HIGH");
    assertThat(evidence.observed()).isNull();
    assertThat(evidence.artifact()).isEqualTo("validation_report.runtime_surface");
  }

  @Test
  void nullRuntimeSurfaceRemainsHistoricalUnknownRatherThanNotApplicable() {
    var evidence =
        ValidationEndpointCoverageEvidence.build(ValidationReport.builder().build(), null);

    assertThat(evidence.status()).isEqualTo("UNKNOWN");
    assertThat(evidence.source()).isEqualTo("LLM_ASSISTED_COUNT");
    assertThat(evidence.confidence()).isEqualTo("LOW");
  }

  @Test
  void httpCoverageRetainsPassWarnFailThresholds() {
    var report = ValidationReport.builder().runtimeSurface("HTTP").build();

    assertThat(ValidationEndpointCoverageEvidence.build(report, 95).status()).isEqualTo("PASS");
    assertThat(ValidationEndpointCoverageEvidence.build(report, 70).status()).isEqualTo("WARN");
    assertThat(ValidationEndpointCoverageEvidence.build(report, 69).status()).isEqualTo("FAIL");
  }
}
