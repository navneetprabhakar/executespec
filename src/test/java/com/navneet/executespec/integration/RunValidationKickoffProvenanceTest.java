package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.report.model.CompileResult;
import com.navneet.executespec.reviewer.model.AccuracyReport;
import com.navneet.executespec.reviewer.model.IssueSeverity;

import org.junit.jupiter.api.Test;

class RunValidationKickoffProvenanceTest {

  @Test
  void llmAccuracyGapsRemainAdvisory() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .missingRequirements(List.of("Add the requested workflow"))
            .specEvidenceStatus("CITED")
            .specEvidenceLocations(List.of("src/App.java:1-5"))
            .endpointEvidenceStatus("CITED")
            .endpointEvidenceLocations(List.of("src/App.java:6-10"))
            .endpointCoverage(
                AccuracyReport.EndpointCoverage.builder()
                    .required(List.of("POST /invented"))
                    .missing(List.of("POST /invented"))
                    .coveragePercent(0)
                    .build())
            .build();

    var issues = RunValidationKickoff.synthesiseReviewIssues(null, CompileResult.skip(), accuracy);

    assertThat(issues)
        .filteredOn(issue -> issue.getId().startsWith("SYNTH-ACC-"))
        .allMatch(issue -> issue.getSeverity() == IssueSeverity.MINOR);
  }

  @Test
  void uncitedAccuracyGapsRemainUnknownAndDoNotCreateIssues() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .missingRequirements(List.of("Unverified missing requirement"))
            .endpointCoverage(
                AccuracyReport.EndpointCoverage.builder()
                    .missing(List.of("POST /unverified"))
                    .build())
            .specEvidenceStatus("UNCITED")
            .endpointEvidenceStatus("UNCITED")
            .build();

    assertThat(RunValidationKickoff.synthesiseReviewIssues(null, CompileResult.skip(), accuracy))
        .isEmpty();
  }

  @Test
  void explicitBuildFailureRemainsDeterministicCriticalEvidence() {
    var issues =
        RunValidationKickoff.synthesiseReviewIssues(
            null, CompileResult.failed(1, "compiler error"), null);

    assertThat(issues)
        .filteredOn(issue -> issue.getId().startsWith("SYNTH-BUILD-"))
        .hasSize(2)
        .allMatch(issue -> issue.getSeverity() == IssueSeverity.CRITICAL);
  }

  @Test
  void unavailableBuildDoesNotCreateFailureIssue() {
    assertThat(RunValidationKickoff.synthesiseReviewIssues(null, CompileResult.skip(), null))
        .isEmpty();
  }
}
