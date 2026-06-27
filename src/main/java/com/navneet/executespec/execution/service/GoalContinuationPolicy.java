package com.navneet.executespec.execution.service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalContinuationRequest;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderRankingDecision;
import com.navneet.executespec.router.ModelProviderScore;

/**
 * Pure policy for turning goal doneness plus ranked model/provider candidates into the next action.
 *
 * <p>This does not mutate run state, dispatch workers, or switch providers. It is the deterministic
 * policy seam future runtime wiring can call before spending another expensive turn.
 */
public class GoalContinuationPolicy {

  public GoalContinuationPolicyDecision decide(GoalContinuationRequest request) {
    if (request == null || request.goalDecision() == null) {
      return decision(
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          Optional.empty(),
          "goal continuation evidence is missing; keep current model/provider");
    }

    GoalDonenessDecision goal = request.goalDecision();
    if (goal.recommendedDecision() == RunContinuationDecision.STOP_DONE) {
      return decision(goal.recommendedDecision(), Optional.empty(), goal.reason());
    }
    if (goal.recommendedDecision() == RunContinuationDecision.STOP_BLOCKED) {
      return decision(goal.recommendedDecision(), Optional.empty(), goal.reason());
    }

    Optional<GoalContinuationPolicyDecision> escalation = escalationDecision(request);
    if (escalation.isPresent()) {
      boolean userConfirmedForEscalation = userConfirmedFor(request, escalation.get());
      if (request.benchmarkQualityPriorRequiredForAutomaticContinuation()
          && !userConfirmedForEscalation
          && escalation.get().selectedCandidate().stream()
              .noneMatch(ModelProviderScore::benchmarkQualityPriorAvailable)) {
        return blockedEscalationDecision(
            RunContinuationDecision.STOP_BLOCKED,
            escalation.get().selectedCandidate(),
            "selected continuation candidate lacks benchmark quality prior; proposed action "
                + escalation.get().decision()
                + proposedCandidateSuffix(escalation.get()),
            escalation.get().decision());
      }
      if (request.expensiveContinuationRequiresConfirmation()
          && !userConfirmedForEscalation) {
        return blockedEscalationDecision(
            RunContinuationDecision.STOP_BLOCKED,
            escalation.get().selectedCandidate(),
            "expensive continuation requires user confirmation before more spend; proposed action "
                + escalation.get().decision()
                + proposedCandidateSuffix(escalation.get()),
            escalation.get().decision());
      }
      return escalation.get();
    }

    if (goal.recommendedDecision() == RunContinuationDecision.STOP_FAILED
        || goal.recommendedDecision() == RunContinuationDecision.STOP_WASTE_GUARD) {
      if (escalationMightHelp(request)) {
        return decision(
            goal.recommendedDecision(),
            Optional.empty(),
            goal.reason()
                + "; no useful model/provider escalation remains within configured limits");
      }
      return decision(goal.recommendedDecision(), Optional.empty(), goal.reason());
    }

    if (request.taskSimplerThanExpected() && request.canStepDown()) {
      Optional<ModelProviderScore> cheaper = cheaperEligibleCandidate(request);
      if (cheaper.isPresent()) {
        ModelProviderScore selected = cheaper.get();
        if (sameProvider(request.currentCandidate(), selected)) {
          return decision(
              RunContinuationDecision.STEP_DOWN_MODEL,
              cheaper,
              "task is simpler than expected; cheaper eligible model clears quality threshold: "
                  + selected.label());
        }
        if (request.canSwitchProvider()) {
          return decision(
              RunContinuationDecision.CHANGE_PROVIDER,
              cheaper,
              "task is simpler than expected; cheaper eligible candidate requires provider change: "
                  + selected.label());
        }
      }
    }

    return decision(
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        Optional.ofNullable(request.currentCandidate()),
        "goal is not proven done, but no bounded escalation or step-down is justified");
  }

  private Optional<GoalContinuationPolicyDecision> escalationDecision(
      GoalContinuationRequest request) {
    if (requiresBenchmarkPriorForAutomaticAction(request)) {
      Optional<GoalContinuationPolicyDecision> benchmarkBacked =
          escalationDecision(request, true);
      if (benchmarkBacked.isPresent()) {
        return benchmarkBacked;
      }
    }
    return escalationDecision(request, false);
  }

  private Optional<GoalContinuationPolicyDecision> escalationDecision(
      GoalContinuationRequest request, boolean benchmarkBackedOnly) {
    if (request.providerBehaviorPoor() && request.canSwitchProvider()) {
      Optional<ModelProviderScore> alternateProvider =
          bestDifferentProvider(request, benchmarkBackedOnly);
      if (alternateProvider.isPresent()) {
        return Optional.of(
            decision(
                RunContinuationDecision.CHANGE_PROVIDER,
                alternateProvider,
                "provider behavior is poor; selected better eligible provider: "
                    + alternateProvider.get().label()));
      }
    }

    if (!escalationMightHelp(request)) {
      return Optional.empty();
    }

    Optional<ModelProviderScore> stronger = strongerEligibleCandidate(request, benchmarkBackedOnly);
    if (stronger.isEmpty()) {
      return Optional.empty();
    }

    ModelProviderScore selected = stronger.get();
    if (sameProvider(request.currentCandidate(), selected) && request.canStepUp()) {
      return Optional.of(
          decision(
              RunContinuationDecision.STEP_UP_MODEL,
              stronger,
              "quality is insufficient; stronger eligible model clears threshold: "
                  + selected.label()));
    }
    if (!sameProvider(request.currentCandidate(), selected) && request.canSwitchProvider()) {
      return Optional.of(
          decision(
              RunContinuationDecision.CHANGE_PROVIDER,
              stronger,
              "quality is insufficient; best eligible candidate requires provider change: "
                  + selected.label()));
    }
    return Optional.empty();
  }

  private static boolean requiresBenchmarkPriorForAutomaticAction(GoalContinuationRequest request) {
    return request != null
        && request.benchmarkQualityPriorRequiredForAutomaticContinuation();
  }

  private static boolean escalationMightHelp(GoalContinuationRequest request) {
    if (request == null || request.goalDecision() == null) {
      return false;
    }
    return request.providerBehaviorPoor()
        || request.qualityInsufficient()
        || request.goalDecision().anotherModelProviderMightHelp()
        || request.goalDecision().recommendedDecision() == RunContinuationDecision.STOP_FAILED
        || request.goalDecision().recommendedDecision() == RunContinuationDecision.STOP_WASTE_GUARD;
  }

  private static Optional<ModelProviderScore> strongerEligibleCandidate(
      GoalContinuationRequest request, boolean benchmarkBackedOnly) {
    ModelProviderScore current = request.currentCandidate();
    if (current == null) {
      return eligibleForAutomaticAction(request, benchmarkBackedOnly).stream().findFirst();
    }
    return eligibleForAutomaticAction(request, benchmarkBackedOnly).stream()
        .filter(candidate -> !sameCandidate(current, candidate))
        .filter(candidate -> candidate.qualityScore() > current.qualityScore())
        .findFirst();
  }

  private static Optional<ModelProviderScore> bestDifferentProvider(
      GoalContinuationRequest request, boolean benchmarkBackedOnly) {
    ModelProviderScore current = request.currentCandidate();
    if (current == null) {
      return eligibleForAutomaticAction(request, benchmarkBackedOnly).stream().findFirst();
    }
    return eligibleForAutomaticAction(request, benchmarkBackedOnly).stream()
        .filter(candidate -> !candidate.provider().equalsIgnoreCase(current.provider()))
        .findFirst();
  }

  private static Optional<ModelProviderScore> cheaperEligibleCandidate(
      GoalContinuationRequest request) {
    ModelProviderScore current = request.currentCandidate();
    if (current == null) {
      return Optional.empty();
    }
    return eligible(request).stream()
        .filter(candidate -> !sameCandidate(current, candidate))
        .filter(candidate -> candidate.costPerMillionTokens() < current.costPerMillionTokens())
        .sorted(
            Comparator.comparingDouble(ModelProviderScore::qualityScore)
                .reversed()
                .thenComparingDouble(ModelProviderScore::costPerMillionTokens)
                .thenComparingLong(ModelProviderScore::latencyP50Ms))
        .findFirst();
  }

  private static List<ModelProviderScore> eligible(GoalContinuationRequest request) {
    ModelProviderRankingDecision ranking = request.rankingDecision();
    return ranking == null ? List.of() : ranking.eligible();
  }

  private static List<ModelProviderScore> eligibleForAutomaticAction(
      GoalContinuationRequest request, boolean benchmarkBackedOnly) {
    List<ModelProviderScore> eligible = eligible(request);
    if (!benchmarkBackedOnly) {
      return eligible;
    }
    return eligible.stream()
        .filter(
            candidate ->
                candidate.benchmarkQualityPriorAvailable()
                    || userConfirmedForAnyEscalation(request, candidate))
        .toList();
  }

  private static boolean userConfirmedFor(
      GoalContinuationRequest request, GoalContinuationPolicyDecision decision) {
    if (decision == null || decision.selectedCandidate().isEmpty()) {
      return false;
    }
    return userConfirmedFor(request, decision.decision(), decision.selectedCandidate().get());
  }

  private static boolean userConfirmedForAnyEscalation(
      GoalContinuationRequest request, ModelProviderScore candidate) {
    return userConfirmedFor(request, RunContinuationDecision.CHANGE_PROVIDER, candidate)
        || userConfirmedFor(request, RunContinuationDecision.STEP_UP_MODEL, candidate)
        || userConfirmedFor(request, RunContinuationDecision.STEP_DOWN_MODEL, candidate);
  }

  private static boolean userConfirmedFor(
      GoalContinuationRequest request,
      RunContinuationDecision action,
      ModelProviderScore selectedCandidate) {
    if (request == null
        || selectedCandidate == null
        || !request.userConfirmedExpensiveContinuation()
        || request.userConfirmedContinuationAction() == null
        || action == null
        || request.userConfirmedContinuationAction() != action
        || !hasText(request.userConfirmedContinuationProvider())
        || !hasText(request.userConfirmedContinuationModel())) {
      return false;
    }
    return normalize(request.userConfirmedContinuationProvider())
            .equals(normalize(selectedCandidate.provider()))
        && normalize(request.userConfirmedContinuationModel())
            .equals(normalize(selectedCandidate.model()));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean sameProvider(ModelProviderScore a, ModelProviderScore b) {
    return a != null && b != null && a.provider().equalsIgnoreCase(b.provider());
  }

  private static boolean sameCandidate(ModelProviderScore a, ModelProviderScore b) {
    return sameProvider(a, b) && a.model().equalsIgnoreCase(b.model());
  }

  private static GoalContinuationPolicyDecision decision(
      RunContinuationDecision decision,
      Optional<ModelProviderScore> selectedCandidate,
      String reason) {
    return new GoalContinuationPolicyDecision(
        decision, selectedCandidate, reason == null ? List.of() : List.of(reason));
  }

  private static GoalContinuationPolicyDecision blockedEscalationDecision(
      RunContinuationDecision decision,
      Optional<ModelProviderScore> selectedCandidate,
      String reason,
      RunContinuationDecision proposedAction) {
    return new GoalContinuationPolicyDecision(
        decision,
        selectedCandidate,
        reason == null ? List.of() : List.of(reason),
        proposedAction == null ? Map.of() : Map.of("proposedAction", proposedAction.name()));
  }

  private static String proposedCandidateSuffix(GoalContinuationPolicyDecision decision) {
    return decision.selectedCandidate().map(candidate -> " -> " + candidate.label()).orElse("");
  }
}
