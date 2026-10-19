package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.testgen.model.TestExecutionSummary;

import org.junit.jupiter.api.Test;

class ValidationAssessmentExecutionFactsTest {
  private Plan plan() {
    return Plan.builder()
        .tasks(
            List.of("T1", "T2", "T3").stream()
                .map(id -> PlannedTask.builder().id(id).title(id).build())
                .toList())
        .build();
  }

  private ExecutionResult execution(List<String> ids) {
    return ExecutionResult.builder()
        .overall(ExecutionResult.Overall.ALL_SUCCESS)
        .perTask(
            ids.stream()
                .map(
                    id ->
                        TaskOutcome.builder().taskId(id).status(TaskOutcome.Status.SUCCESS).build())
                .toList())
        .build();
  }

  private WorkspaceTestRunner.DetailedResult tests(int exit) {
    return new WorkspaceTestRunner.DetailedResult(
        TestExecutionSummary.builder().totalTests(16).passed(16).build(),
        "",
        "python3 -m pytest -q",
        exit);
  }

  @Test
  void completedSequenceAndFreshSuiteAreProvidedWithoutClaimingIndependentProductProof() {
    String facts =
        ValidationAssessmentExecutionFacts.describe(
            plan(), execution(List.of("T1", "T2", "T3")), tests(0), "a".repeat(64));
    assertThat(facts)
        .contains(
            "completed all 3 planned tasks in plan order: [T1, T2, T3]",
            "passed=16",
            "provenPassing=true",
            "not independent semantic coverage");
  }

  @Test
  void countOrOrderMismatchCannotAttestTheRequestedSequence() {
    for (var ids : List.of(List.of("T1"), List.of("T2", "T1", "T3")))
      assertThat(
              ValidationAssessmentExecutionFacts.describe(
                  plan(), execution(ids), tests(0), "a".repeat(64)))
          .contains("Task sequence completion is not attested");
  }

  @Test
  void contradictedExitOrMissingTestsCannotBeClaimedAsPassing() {
    assertThat(
            ValidationAssessmentExecutionFacts.describe(
                plan(), execution(List.of("T1", "T2", "T3")), tests(1), "a".repeat(64)))
        .contains("provenPassing=false");
    assertThat(
            ValidationAssessmentExecutionFacts.describe(
                plan(), execution(List.of("T1", "T2", "T3")), null, "a".repeat(64)))
        .contains("No fresh maintained-suite execution result");
  }

  @Test
  void noVerificationCapabilityMeansNoGenerationBoundEvidence() {
    assertThat(
            ValidationAssessmentExecutionFacts.capture(
                null, plan(), execution(List.of("T1", "T2", "T3")), tests(0), "a".repeat(64)))
        .isEmpty();
  }
}
