package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.report.model.WorkflowConfigEvidence;
import com.navneet.executespec.reviewer.ReviewerResult;

import org.junit.jupiter.api.Test;

class CorrectionAcceptancePolicyTest {

  @Test
  void acceptsOnlySuccessfulBuiltReviewedCorrection() {
    var decision =
        CorrectionAcceptancePolicy.evaluate("T1", execution(successfulTask()), cleanReview());

    assertThat(decision.accepted()).isTrue();
    assertThat(decision.code()).isEqualTo("CORRECTION_ACCEPTED");
  }

  @Test
  void rejectsRegressionEvenWhenWorkerCompleted() {
    TaskOutcome regressive =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
            .smokeTestPassed(false)
            .build();

    var decision = CorrectionAcceptancePolicy.evaluate("T1", execution(regressive), cleanReview());

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_TASK_NOT_SUCCESSFUL");
  }

  @Test
  void rejectsMissingFinalBuildInsteadOfTreatingItAsSuccess() {
    ReviewerResult unavailable =
        ReviewerResult.builder()
            .overall(ReviewerResult.Overall.CLEAN)
            .finalBuildPassed(null)
            .workspaceQuiescent(true)
            .build();

    var decision =
        CorrectionAcceptancePolicy.evaluate(
            "runprefix_T1", execution(successfulTask()), unavailable);

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_FINAL_BUILD_UNPROVEN");
  }

  @Test
  void rejectsUnresolvedReviewAndRedConcreteTests() {
    ReviewerResult unresolved =
        ReviewerResult.builder()
            .overall(ReviewerResult.Overall.UNRESOLVED_ISSUES)
            .finalBuildPassed(true)
            .concreteTestsRed(true)
            .workspaceQuiescent(true)
            .build();

    var decision =
        CorrectionAcceptancePolicy.evaluate("T1", execution(successfulTask()), unresolved);

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_REVIEW_NOT_ACCEPTED");
  }

  @Test
  void rejectsCorrectionWithInvalidDeterministicWorkflowEvidence() {
    var decision =
        CorrectionAcceptancePolicy.evaluate(
            "T1",
            execution(successfulTask()),
            cleanReview(),
            new WorkflowConfigEvidence(
                WorkflowConfigEvidence.Status.FAILED,
                List.of(
                    new WorkflowConfigEvidence.FileEvidence(
                        ".github/workflows/build.yml",
                        "a".repeat(64),
                        WorkflowConfigEvidence.ParseStatus.INVALID,
                        "invalid YAML")),
                "One or more workflow files failed deterministic YAML validation"));

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_WORKFLOW_CONFIG_INVALID");
  }

  @Test
  void rejectsCorrectionWhenNoRealFinalTestsExecuted() {
    ReviewerResult noTests =
        ReviewerResult.builder()
            .overall(ReviewerResult.Overall.CLEAN)
            .finalBuildPassed(true)
            .finalTestsPassed(0)
            .finalTestsTotal(0)
            .workspaceQuiescent(true)
            .build();

    var decision = CorrectionAcceptancePolicy.evaluate("T1", execution(successfulTask()), noTests);

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_FINAL_TESTS_UNPROVEN");
  }

  @Test
  void rejectsNoOpCorrectionAgainstCheckpointGeneration() {
    String generation = "a".repeat(64);

    var decision =
        CorrectionAcceptancePolicy.evaluate(
            "T1",
            execution(successfulTask()),
            cleanReview(),
            WorkflowConfigEvidence.notApplicable(),
            generation,
            generation);

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_NO_CHANGE");
  }

  @Test
  void rejectsGreenCorrectionThatDropsPreviouslyPassingTests() {
    var before = validation("a", 12, 12);
    var after = validation("b", 5, 5);

    var decision =
        CorrectionAcceptancePolicy.evaluateRegression(
            CorrectionResolutionEvidence.delta(before, after));

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_PROTECTED_EVIDENCE_REGRESSION");
    assertThat(decision.reason()).contains("12/12").contains("5/5");
  }

  @Test
  void acceptsFreshEvidenceWhenNoPublishedBaselineExists() {
    var decision =
        CorrectionAcceptancePolicy.evaluateRegression(
            CorrectionResolutionEvidence.delta(null, validation("b", 5, 5)));

    assertThat(decision.accepted()).isTrue();
    assertThat(decision.code()).isEqualTo("CORRECTION_EVIDENCE_BASELINE_NOT_APPLICABLE");
  }

  @Test
  void rejectsCorrectionWhenPreviouslyPassingTestsBecomeUnproven() {
    var before = validation("a", 12, 12);
    var after =
        new CorrectionResolutionEvidence.ValidationSnapshot(
            "COMPLETE",
            "b".repeat(64),
            new CorrectionResolutionEvidence.BuildValidation("PASSED", true, 0),
            new CorrectionResolutionEvidence.TestValidation(
                "UNPROVEN", null, null, null, false, "UNAVAILABLE", null),
            new CorrectionResolutionEvidence.WorkflowValidation("PASSED", 1, null),
            new CorrectionResolutionEvidence.ReviewerValidation("CLEAN"),
            new CorrectionResolutionEvidence.ContractValidation(
                "COMPATIBLE", "base", "current", 0, 0),
            new CorrectionResolutionEvidence.ScoreValidation(75, 75),
            "PASSED",
            "UNAVAILABLE",
            null);

    var decision =
        CorrectionAcceptancePolicy.evaluateRegression(
            CorrectionResolutionEvidence.delta(before, after));

    assertThat(decision.accepted()).isFalse();
    assertThat(decision.code()).isEqualTo("CORRECTION_PROTECTED_EVIDENCE_REGRESSION");
    assertThat(decision.reason()).contains("unproven");
  }

  private static CorrectionResolutionEvidence.ValidationSnapshot validation(
      String generation, int passed, int total) {
    return new CorrectionResolutionEvidence.ValidationSnapshot(
        "COMPLETE",
        generation.repeat(64),
        new CorrectionResolutionEvidence.BuildValidation("PASSED", true, 0),
        new CorrectionResolutionEvidence.TestValidation(
            "PASSED", passed, total, 0, true, "WORKSPACE_TEST_SUITE", 0),
        new CorrectionResolutionEvidence.WorkflowValidation("PASSED", 1, null),
        new CorrectionResolutionEvidence.ReviewerValidation("CLEAN"),
        new CorrectionResolutionEvidence.ContractValidation("COMPATIBLE", "base", "current", 0, 0),
        new CorrectionResolutionEvidence.ScoreValidation(90, 90),
        "PASSED",
        "PASSED",
        null);
  }

  private static ExecutionResult execution(TaskOutcome task) {
    return ExecutionResult.builder().perTask(List.of(task)).build();
  }

  private static TaskOutcome successfulTask() {
    return TaskOutcome.builder()
        .taskId("T1")
        .status(TaskOutcome.Status.SUCCESS)
        .smokeTestPassed(true)
        .build();
  }

  private static ReviewerResult cleanReview() {
    return ReviewerResult.builder()
        .overall(ReviewerResult.Overall.CLEAN)
        .finalBuildPassed(true)
        .finalTestsPassed(5)
        .finalTestsTotal(5)
        .workspaceQuiescent(true)
        .build();
  }
}
