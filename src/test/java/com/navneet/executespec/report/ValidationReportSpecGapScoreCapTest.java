package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.junit.jupiter.api.Test;

class ValidationReportSpecGapScoreCapTest {

  @Test
  void admitted_partial_requirement_caps_the_headline_score() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(79)
            .partialRequirements(List.of("Release idempotency is not implemented"))
            .specEvidenceStatus("CITED")
            .specEvidenceLocations(List.of("src/App.java:1-5"))
            .build();

    assertEquals(79, ValidationReportService.capScoreAtAdmittedSpecGaps(97, accuracy));
  }

  @Test
  void unknown_only_evidence_caps_the_headline_at_the_assessors_confidence() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(70)
            .unknownRequirements(List.of("Middleware was not included in the evidence"))
            .specEvidenceStatus("CITED")
            .specEvidenceLocations(List.of("src/App.java:1-5"))
            .build();

    assertEquals(70, ValidationReportService.capScoreAtAdmittedSpecGaps(97, accuracy));
  }

  @Test
  void unknown_requirements_cannot_keep_a_perfect_passing_score() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(100)
            .unknownRequirements(List.of("Authentication boundary was not verified"))
            .specEvidenceStatus("CITED")
            .specEvidenceLocations(List.of("src/App.java:1-5"))
            .build();

    assertEquals(84, ValidationReportService.capScoreAtAdmittedSpecGaps(97, accuracy));
  }

  @Test
  void uncitedSpecClaimsCannotChangeTheHeadlineScore() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(5)
            .missingRequirements(List.of("Unverified model claim"))
            .specEvidenceStatus("UNCITED")
            .build();

    assertEquals(97, ValidationReportService.capScoreAtAdmittedSpecGaps(97, accuracy));
  }
}
