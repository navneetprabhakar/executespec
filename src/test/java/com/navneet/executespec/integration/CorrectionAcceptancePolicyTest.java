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
