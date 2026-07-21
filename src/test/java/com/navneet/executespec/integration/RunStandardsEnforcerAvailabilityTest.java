package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.standards.StandardsRunner;
import com.navneet.executespec.standards.enforcement.EvaluationStatus;

import org.junit.jupiter.api.Test;

/**
 * RR-1611 — a degraded AST / file-pattern check (non-EVALUATED) must produce the same {@code
 * SYNTH-STANDARD-UNAVAILABLE-*} evidence the lint channel does, so the gate cannot read it as a
 * clean pass.
 */
class RunStandardsEnforcerAvailabilityTest {

  @Test
  void invalidRuleStatusEmitsUnavailableIssue() {
    StandardsRunner.RunReport report =
        new StandardsRunner.RunReport(
            List.of(), Map.of(), Map.of("bad-naming", EvaluationStatus.INVALID_RULE));

    List<ReviewIssue> issues = RunStandardsEnforcer.toReviewIssues(report);

    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).getId()).startsWith("SYNTH-STANDARD-UNAVAILABLE");
    assertThat(issues.get(0).getDescription()).contains("bad-naming").contains("INVALID_RULE");
  }

  @Test
  void unavailableStatusEmitsUnavailableIssue() {
    StandardsRunner.RunReport report =
        new StandardsRunner.RunReport(
            List.of(), Map.of(), Map.of("no-inventory", EvaluationStatus.UNAVAILABLE));

    List<ReviewIssue> issues = RunStandardsEnforcer.toReviewIssues(report);

    assertThat(issues).hasSize(1);
    assertThat(issues.get(0).getId()).startsWith("SYNTH-STANDARD-UNAVAILABLE");
  }

  @Test
  void evaluatedStatusEmitsNoIssue() {
    StandardsRunner.RunReport report =
        new StandardsRunner.RunReport(
            List.of(), Map.of(), Map.of("clean", EvaluationStatus.EVALUATED));

    assertThat(RunStandardsEnforcer.toReviewIssues(report)).isEmpty();
  }
}
