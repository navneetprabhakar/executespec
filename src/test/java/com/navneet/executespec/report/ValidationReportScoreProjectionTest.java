package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

class ValidationReportScoreProjectionTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void admittedUnknownRequirementCapsAStaleOptimisticSnapshot() {
    ValidationReport report =
        ValidationReport.builder()
            .verifiedScore(100)
            .accuracyJson(
                """
                {
                  "specAdherenceScore": 100,
                  "specEvidenceStatus": "CITED",
                  "specEvidenceLocations": ["src/App.java:1-5"],
                  "unknownRequirements": ["Runtime acceptance was not verified"]
                }
                """)
            .build();

    assertThat(ValidationReportScoreProjection.effectiveVerifiedScore(report, objectMapper))
        .isEqualTo(84);
  }

  @Test
  void malformedLegacyAccuracyEvidenceDoesNotInventANewScore() {
    assertThat(ValidationReportScoreProjection.effectiveScore(73, "{broken", objectMapper))
        .isEqualTo(73);
  }

  @Test
  void llmOnlyZeroAssessmentCannotTurnAPositiveHeadlineIntoZero() {
    String advisoryZero =
        """
        {
          "specAdherenceScore": 0,
          "specEvidenceStatus": "CITED",
          "specEvidenceLocations": ["src/App.java:1-5"],
          "missingRequirements": ["The bounded LLM sample did not find the requested behavior"]
        }
        """;

    assertThat(ValidationReportScoreProjection.effectiveScore(92, advisoryZero, objectMapper))
        .isEqualTo(1);
  }

  @Test
  void projectionPreservesAnExistingDeterministicZero() {
    String advisoryGap =
        """
        {
          "specAdherenceScore": 88,
          "partialRequirements": ["A requirement needs review"]
        }
        """;

    assertThat(ValidationReportScoreProjection.effectiveScore(0, advisoryGap, objectMapper))
        .isZero();
  }
}
