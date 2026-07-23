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
            .build();

    assertEquals(79, ValidationReportService.capScoreAtAdmittedSpecGaps(97, accuracy));
  }

  @Test
  void unknown_only_evidence_does_not_cap_the_headline_score() {
    AccuracyReport accuracy =
        AccuracyReport.builder()
            .specAdherenceScore(70)
            .unknownRequirements(List.of("Middleware was not included in the evidence"))
            .build();

    assertEquals(97, ValidationReportService.capScoreAtAdmittedSpecGaps(97, accuracy));
  }
}
