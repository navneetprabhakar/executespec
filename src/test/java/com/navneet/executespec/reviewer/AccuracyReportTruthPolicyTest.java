package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.junit.jupiter.api.Test;

class AccuracyReportTruthPolicyTest {

  @Test
  void admitted_missing_behavior_cannot_remain_unknown_or_grade_a() {
    AccuracyReport report =
        AccuracyReport.builder()
            .specAdherenceScore(92)
            .overallAccuracyGrade("A")
            .unknownRequirements(
                List.of(
                    "Release idempotency — endpoint accepts no idempotency_key and repeated calls"
                        + " return 409 rather than the original response"))
            .partialRequirements(List.of())
            .build();

    AccuracyReportTruthPolicy.enforceConsistency(report);

    assertThat(report.getUnknownRequirements()).isEmpty();
    assertThat(report.getPartialRequirements()).hasSize(1);
    assertThat(report.getSpecAdherenceScore()).isEqualTo(79);
    assertThat(report.getOverallAccuracyGrade()).isEqualTo("C");
  }

  @Test
  void genuine_evidence_limitation_remains_unknown() {
    AccuracyReport report =
        AccuracyReport.builder()
            .specAdherenceScore(95)
            .overallAccuracyGrade("A")
            .unknownRequirements(
                List.of("Rate limiting not shown in the snippet; need to see middleware config"))
            .partialRequirements(List.of())
            .build();

    AccuracyReportTruthPolicy.enforceConsistency(report);

    assertThat(report.getUnknownRequirements()).hasSize(1);
    assertThat(report.getPartialRequirements()).isEmpty();
    assertThat(report.getSpecAdherenceScore()).isEqualTo(95);
    assertThat(report.getOverallAccuracyGrade()).isEqualTo("A");
  }

  @Test
  void llm_admitted_partial_requirement_cannot_keep_grade_a() {
    AccuracyReport report =
        AccuracyReport.builder()
            .specAdherenceScore(100)
            .overallAccuracyGrade("A")
            .missingRequirements(List.of())
            .partialRequirements(List.of("Documented startup fails with the default database"))
            .unknownRequirements(List.of())
            .build();

    AccuracyReportTruthPolicy.enforceConsistency(report);

    assertThat(report.getSpecAdherenceScore()).isEqualTo(79);
    assertThat(report.getOverallAccuracyGrade()).isEqualTo("C");
  }

  @Test
  void llm_admitted_missing_requirement_cannot_keep_grade_a() {
    AccuracyReport report =
        AccuracyReport.builder()
            .specAdherenceScore(94)
            .overallAccuracyGrade("A")
            .missingRequirements(List.of("Required release idempotency is absent"))
            .partialRequirements(List.of())
            .unknownRequirements(List.of())
            .build();

    AccuracyReportTruthPolicy.enforceConsistency(report);

    assertThat(report.getSpecAdherenceScore()).isEqualTo(69);
    assertThat(report.getOverallAccuracyGrade()).isEqualTo("D");
  }
}
