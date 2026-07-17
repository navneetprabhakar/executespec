package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

class ValidationReportValidityServiceTest {

  private final ValidationReportValidityService service =
      new ValidationReportValidityService(new ObjectMapper());

  @Test
  void aggregateExcludesInvalidSeventyFiveAndKeepsValidNinety() {
    ValidationReport invalid = report(75, 0, PipelineResult.PASSED, true, "CLEAN");
    ValidationReport valid = report(90, 2, PipelineResult.PASSED, true, "CLEAN");

    var aggregate = service.aggregate(List.of(invalid, valid));

    assertThat(aggregate.averageScore()).isEqualTo(90.0);
    assertThat(aggregate.excludedCount()).isEqualTo(1);
    assertThat(aggregate.canonicalReports()).containsExactly(valid);
  }

  @Test
  void rejectsOutOfRangeAndUnprovenBuildScores() {
    assertThat(service.isCanonical(report(101, 2, PipelineResult.PASSED, true, "CLEAN"))).isFalse();
    assertThat(service.isCanonical(report(90, 2, PipelineResult.PASSED, false, "CLEAN"))).isFalse();
  }

  private static ValidationReport report(
      int score, int files, PipelineResult result, boolean build, String verdict) {
    return ValidationReport.builder()
        .id(java.util.UUID.randomUUID().toString())
        .runId(java.util.UUID.randomUUID().toString())
        .status("COMPLETE")
        .verifiedScore(score)
        .filesGenerated(files)
        .pipelineResult(result)
        .finalBuildPassed(build)
        .reviewerVerdict(verdict)
        .accuracyJson(
            "{\"specAdherenceScore\":90,\"implementedRequirements\":[\"requested change\"],"
                + "\"missingRequirements\":[],\"partialRequirements\":[]}")
        .build();
  }
}
