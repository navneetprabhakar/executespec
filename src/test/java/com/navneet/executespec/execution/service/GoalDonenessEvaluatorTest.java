package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.GoalEvidence;
import com.navneet.executespec.execution.model.RunContinuationDecision;

import org.junit.jupiter.api.Test;

class GoalDonenessEvaluatorTest {

  private final GoalDonenessEvaluator evaluator = new GoalDonenessEvaluator();

  @Test
  void doneWhenGoalCriteriaValidationOutputsAndConstraintsAreProven() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .goalCriteriaSatisfied(true)
                .validationAttempted(true)
                .validationPassed(true)
                .requiredOutputsPresent(true)
                .constraintsRespected(true)
                .completedItems(List.of("implemented endpoint", "tests pass"))
                .evidence(List.of("mvn test passed"))
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.DONE);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_DONE);
    assertThat(decision.incompleteItems()).isEmpty();
    assertThat(decision.reason()).contains("goal satisfied");
  }

  @Test
  void modelCompleteTaskClaimIsNotDoneWhenValidationFails() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .goalCriteriaSatisfied(false)
                .validationAttempted(true)
                .validationPassed(false)
                .requiredOutputsPresent(true)
                .constraintsRespected(true)
                .meaningfulDiffCount(3)
                .completeTaskClaimed(true)
                .falseDoneClaims(1)
                .maxFalseDoneClaims(2)
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.NOT_DONE_BUT_PROGRESSING);
    assertThat(decision.recommendedDecision())
        .isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.anotherModelProviderMightHelp()).isTrue();
    assertThat(decision.incompleteItems()).contains("goal criteria not proven satisfied");
  }

  @Test
  void blockedWhenEnvironmentRequirementIsMissing() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .blockedByEnvironment(true)
                .incompleteItems(List.of("missing GITHUB_TOKEN"))
                .risks(List.of("credential unavailable"))
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.BLOCKED);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.userInputRequired()).isTrue();
    assertThat(decision.incompleteItems()).containsExactly("missing GITHUB_TOKEN");
  }

  @Test
  void uncertainWhenValidationCannotProveSuccess() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .goalCriteriaSatisfied(true)
                .validationAttempted(false)
                .validationPassed(null)
                .requiredOutputsPresent(true)
                .constraintsRespected(true)
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.UNCERTAIN);
    assertThat(decision.recommendedDecision())
        .isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.incompleteItems()).contains("validation not proven");
  }

  @Test
  void stopsWasteGuardAfterNoProgressLimit() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .noProgressIterations(8)
                .maxNoProgressIterations(8)
                .meaningfulDiffCount(0)
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.WASTE_RISK);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_WASTE_GUARD);
    assertThat(decision.reason()).contains("no meaningful progress");
  }

  @Test
  void stopsWasteGuardAfterRepeatedFalseCompletionClaims() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .validationAttempted(true)
                .validationPassed(false)
                .completeTaskClaimed(true)
                .falseDoneClaims(2)
                .maxFalseDoneClaims(2)
                .meaningfulDiffCount(2)
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.WASTE_RISK);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_WASTE_GUARD);
    assertThat(decision.reason()).contains("false completion");
  }

  @Test
  void stopsWasteGuardWhenBudgetCeilingIsReachedBeforeDone() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .budgetCeilingReached(true)
                .meaningfulDiffCount(3)
                .validationAttempted(true)
                .validationPassed(false)
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.WASTE_RISK);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_WASTE_GUARD);
    assertThat(decision.reason()).contains("ceiling reached");
  }

  @Test
  void failedWhenValidationFailsAndNoUsefulProgressRemains() {
    GoalDonenessDecision decision =
        evaluator.evaluate(
            base()
                .validationAttempted(true)
                .validationPassed(false)
                .meaningfulDiffCount(0)
                .build());

    assertThat(decision.state()).isEqualTo(GoalDonenessState.FAILED);
    assertThat(decision.recommendedDecision()).isEqualTo(RunContinuationDecision.STOP_FAILED);
    assertThat(decision.anotherModelProviderMightHelp()).isTrue();
  }

  private static GoalEvidence.GoalEvidenceBuilder base() {
    return GoalEvidence.builder()
        .goalSummary("add project README generation")
        .maxNoProgressIterations(8)
        .maxFalseDoneClaims(2);
  }
}
