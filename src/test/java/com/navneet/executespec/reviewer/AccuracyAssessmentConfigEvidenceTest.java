package com.navneet.executespec.reviewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.agent.RequirementSpec;
import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.junit.jupiter.api.Test;

class AccuracyAssessmentConfigEvidenceTest {

  @Test
  void acceptsOrdinaryGithubActionsYamlThroughSafeParser() {
    String workflow =
        """
        name: build
        on:
          pull_request:
        jobs:
          test:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - run: ./mvnw test
        """;

    assertThat(AccuracyAssessmentService.safeYamlProblem(workflow)).isNull();
  }

  @Test
  void rejectsUnsafeYamlTagsAndBoundsTheDiagnostic() {
    String unsafe = "value: !!javax.script.ScriptEngineManager []";

    assertThat(AccuracyAssessmentService.safeYamlProblem(unsafe))
        .isNotNull()
        .hasSizeLessThanOrEqualTo(183);
  }

  @Test
  void workflowOnlySpecCannotInventEndpointCoverage() {
    AccuracyReport report =
        AccuracyReport.builder()
            .endpointCoverage(
                AccuracyReport.EndpointCoverage.builder()
                    .required(List.of("POST /invented"))
                    .missing(List.of("POST /invented"))
                    .coveragePercent(0)
                    .build())
            .build();

    AccuracyAssessmentService.removeInventedEndpointCoverage(
        "Add a GitHub Actions workflow that builds and tests pull requests", List.of(), report);

    assertThat(report.getEndpointCoverage()).isNull();
  }

  @Test
  void explicitEndpointInRequirementLedgerRetainsCoverage() {
    AccuracyReport.EndpointCoverage coverage =
        AccuracyReport.EndpointCoverage.builder()
            .required(List.of("POST /orders"))
            .missing(List.of("POST /orders"))
            .coveragePercent(0)
            .build();
    AccuracyReport report = AccuracyReport.builder().endpointCoverage(coverage).build();

    AccuracyAssessmentService.removeInventedEndpointCoverage(
        "Implement the accepted plan",
        List.of(new RequirementSpec("REQ-1", "POST /orders", null)),
        report);

    assertThat(report.getEndpointCoverage()).isSameAs(coverage);
  }
}
