package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalContinuationRequest;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderRankingDecision;
import com.navneet.executespec.router.ModelProviderRankingPolicy;
import com.navneet.executespec.router.ModelProviderScore;

import org.junit.jupiter.api.Test;

class GoalContinuationPolicyTest {

  private final ModelProviderRankingPolicy rankingPolicy = new ModelProviderRankingPolicy();
  private final GoalContinuationPolicy policy = new GoalContinuationPolicy();

  @Test
  void stopDoneRemainsTerminalAndDoesNotStepDown() {
    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(done(), current(), ranked(current(), cheapEligible()))
                .taskSimplerThanExpected(true)
                .maxModelStepDowns(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STOP_DONE);
    assertThat(decision.terminal()).isTrue();
    assertThat(decision.selectedCandidate()).isEmpty();
  }

  @Test
  void blockedRemainsTerminalEvenWhenBetterCandidateExists() {
    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(blocked(), current(), ranked(current(), strongerSameProvider()))
                .qualityInsufficient(true)
                .maxModelStepUps(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.terminal()).isTrue();
  }

  @Test
  void continueSameWhenProgressingAndNoEscalationSignalExists() {
    GoalContinuationPolicyDecision decision =
        policy.decide(request(progressing(false), current(), ranked(current())).build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.selectedCandidate()).contains(current());
  }

  @Test
  void stepUpWhenQualityInsufficientAndStrongerSameProviderCandidateExists() {
    ModelProviderScore stronger = strongerSameProvider();

    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(progressing(true), current(), ranked(stronger, current()))
                .qualityInsufficient(true)
                .maxModelStepUps(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STEP_UP_MODEL);
    assertThat(decision.selectedCandidate()).contains(stronger);
    assertThat(decision.reasons().getFirst()).contains("quality is insufficient");
  }

  @Test
  void changeProviderWhenProviderBehaviorIsPoorAndAlternateClearsThreshold() {
    ModelProviderScore alternate = strongerDifferentProvider();

    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(progressing(true), current(), ranked(alternate, current()))
                .providerBehaviorPoor(true)
                .maxProviderSwitches(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.CHANGE_PROVIDER);
    assertThat(decision.selectedCandidate()).contains(alternate);
  }

  @Test
  void wasteRiskCanEscalateOnceWhenAnotherModelMightHelp() {
    ModelProviderScore stronger = strongerSameProvider();

    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(wasteRisk(true), current(), ranked(stronger, current()))
                .qualityInsufficient(true)
                .maxModelStepUps(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STEP_UP_MODEL);
    assertThat(decision.selectedCandidate()).contains(stronger);
  }

  @Test
  void wasteRiskStopsWhenEscalationLimitIsExhausted() {
    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(wasteRisk(true), current(), ranked(strongerSameProvider(), current()))
                .qualityInsufficient(true)
                .modelStepUpsUsed(1)
                .maxModelStepUps(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STOP_WASTE_GUARD);
    assertThat(decision.terminal()).isTrue();
    assertThat(decision.reasons().getFirst()).contains("no useful model/provider escalation");
  }

  @Test
  void wasteRiskStopsWhenNoStrongerEligibleCandidateRemains() {
    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(wasteRisk(true), current(), ranked(current()))
                .qualityInsufficient(true)
                .maxModelStepUps(1)
                .maxProviderSwitches(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STOP_WASTE_GUARD);
    assertThat(decision.terminal()).isTrue();
    assertThat(decision.selectedCandidate()).isEmpty();
    assertThat(decision.reasons().getFirst())
        .contains("no useful model/provider escalation remains within configured limits");
  }

  @Test
  void stepDownOnlyUsesCheaperCandidateThatClearsQualityThreshold() {
    ModelProviderScore cheapWeak = score("anthropic", "haiku-weak", 69, 1.0, 300, 70, "too weak");
    ModelProviderScore cheapEligible = cheapEligible();

    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(progressing(false), current(), ranked(cheapWeak, current(), cheapEligible))
                .taskSimplerThanExpected(true)
                .maxModelStepDowns(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STEP_DOWN_MODEL);
    assertThat(decision.selectedCandidate()).contains(cheapEligible);
  }

  @Test
  void doesNotStepDownToCheaperCandidateBelowQualityThreshold() {
    ModelProviderScore cheapWeak = score("anthropic", "haiku-weak", 69, 1.0, 300, 70, "too weak");

    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(progressing(false), current(), ranked(cheapWeak, current()))
                .taskSimplerThanExpected(true)
                .maxModelStepDowns(1)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER);
    assertThat(decision.selectedCandidate()).contains(current());
  }

  @Test
  void expensiveContinuationRequiresConfirmationBeforeEscalation() {
    ModelProviderScore stronger = strongerSameProvider();

    GoalContinuationPolicyDecision decision =
        policy.decide(
            request(progressing(true), current(), ranked(stronger, current()))
                .qualityInsufficient(true)
                .maxModelStepUps(1)
                .expensiveContinuationRequiresConfirmation(true)
                .userConfirmedExpensiveContinuation(false)
                .build());

    assertThat(decision.decision()).isEqualTo(RunContinuationDecision.STOP_BLOCKED);
    assertThat(decision.selectedCandidate()).contains(stronger);
    assertThat(decision.reasons().getFirst()).contains("requires user confirmation");
    assertThat(decision.reasons().getFirst()).contains("proposed action STEP_UP_MODEL");
    assertThat(decision.reasons().getFirst()).contains(stronger.label());
  }

  private GoalContinuationRequest.GoalContinuationRequestBuilder request(
      GoalDonenessDecision goalDecision,
      ModelProviderScore current,
      ModelProviderRankingDecision ranking) {
    return GoalContinuationRequest.builder()
        .goalDecision(goalDecision)
        .currentCandidate(current)
        .rankingDecision(ranking);
  }

  private ModelProviderRankingDecision ranked(ModelProviderScore... candidates) {
    return rankingPolicy.rank(List.of(candidates));
  }

  private static GoalDonenessDecision done() {
    return goal(GoalDonenessState.DONE, RunContinuationDecision.STOP_DONE, "done", false, false);
  }

  private static GoalDonenessDecision blocked() {
    return goal(
        GoalDonenessState.BLOCKED, RunContinuationDecision.STOP_BLOCKED, "blocked", true, false);
  }

  private static GoalDonenessDecision progressing(boolean anotherModelMightHelp) {
    return goal(
        GoalDonenessState.NOT_DONE_BUT_PROGRESSING,
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        "progressing",
        false,
        anotherModelMightHelp);
  }

  private static GoalDonenessDecision wasteRisk(boolean anotherModelMightHelp) {
    return goal(
        GoalDonenessState.WASTE_RISK,
        RunContinuationDecision.STOP_WASTE_GUARD,
        "waste risk",
        false,
        anotherModelMightHelp);
  }

  private static GoalDonenessDecision goal(
      GoalDonenessState state,
      RunContinuationDecision decision,
      String reason,
      boolean userInputRequired,
      boolean anotherModelMightHelp) {
    return new GoalDonenessDecision(
        state,
        decision,
        reason,
        "goal",
        List.of("completed"),
        List.of("incomplete"),
        List.of("evidence"),
        List.of("risk"),
        userInputRequired,
        anotherModelMightHelp);
  }

  private static ModelProviderScore current() {
    return score("anthropic", "sonnet", 82, 6.0, 1_200, 70, "current");
  }

  private static ModelProviderScore strongerSameProvider() {
    return score("anthropic", "opus", 91, 18.0, 2_000, 70, "stronger");
  }

  private static ModelProviderScore strongerDifferentProvider() {
    return score("openai", "gpt-5.4", 92, 16.0, 1_800, 70, "alternate provider");
  }

  private static ModelProviderScore cheapEligible() {
    return score("anthropic", "haiku", 78, 1.4, 450, 70, "cheaper eligible");
  }

  private static ModelProviderScore score(
      String provider,
      String model,
      double quality,
      double cost,
      long latency,
      double threshold,
      String reason) {
    return new ModelProviderScore(
        provider,
        model,
        "BUG_FIX",
        quality,
        cost,
        latency,
        latency * 2,
        threshold,
        List.of(reason));
  }
}
