package com.navneet.executespec.execution.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalContinuationRequest;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderCandidateRequest;
import com.navneet.executespec.router.ModelProviderCandidateService;
import com.navneet.executespec.router.ModelProviderRankingDecision;
import com.navneet.executespec.router.ModelProviderRankingPolicy;
import com.navneet.executespec.router.ModelProviderScore;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Runtime-facing adapter that turns live model/provider candidates into continuation advice.
 *
 * <p>This is still advisory: it does not dispatch workers, mutate runs, or switch providers.
 */
@Component
@RequiredArgsConstructor
public class GoalContinuationAdvisor {

  private final ModelProviderCandidateService candidateService;
  private final ModelProviderRankingPolicy rankingPolicy = new ModelProviderRankingPolicy();
  private final GoalContinuationPolicy continuationPolicy = new GoalContinuationPolicy();

  public GoalContinuationPolicyDecision advise(GoalContinuationAdvisorRequest request) {
    if (request == null || request.goalDecision() == null) {
      return new GoalContinuationPolicyDecision(
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          Optional.empty(),
          List.of("goal continuation advisor request is missing required goal evidence"));
    }

    List<ModelProviderScore> candidates =
        candidateService.buildCandidates(
            ModelProviderCandidateRequest.builder()
                .taskCategory(request.taskCategory())
                .requiredQualityThreshold(request.requiredQualityThreshold())
                .providerAllowList(request.providerAllowList())
                .modelAllowList(request.modelAllowList())
                .externalSignals(request.externalSignals())
                .providerAvailability(request.providerAvailability())
                .providerPreferenceOrder(request.providerPreferenceOrder())
                .build());
    ModelProviderRankingDecision ranking = rankingPolicy.rank(candidates);
    ModelProviderScore current = findCurrentCandidate(candidates, request).orElse(null);

    return continuationPolicy.decide(
        GoalContinuationRequest.builder()
            .goalDecision(request.goalDecision())
            .currentCandidate(current)
            .rankingDecision(ranking)
            .qualityInsufficient(request.qualityInsufficient())
            .providerBehaviorPoor(request.providerBehaviorPoor())
            .taskSimplerThanExpected(request.taskSimplerThanExpected())
            .expensiveContinuationRequiresConfirmation(
                request.expensiveContinuationRequiresConfirmation())
            .userConfirmedExpensiveContinuation(request.userConfirmedExpensiveContinuation())
            .providerSwitchesUsed(request.providerSwitchesUsed())
            .maxProviderSwitches(request.maxProviderSwitches())
            .modelStepUpsUsed(request.modelStepUpsUsed())
            .maxModelStepUps(request.maxModelStepUps())
            .modelStepDownsUsed(request.modelStepDownsUsed())
            .maxModelStepDowns(request.maxModelStepDowns())
            .build());
  }

  private static Optional<ModelProviderScore> findCurrentCandidate(
      List<ModelProviderScore> candidates, GoalContinuationAdvisorRequest request) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    String provider = normalize(request.currentProvider());
    String model = normalize(request.currentModel());
    return candidates.stream()
        .filter(
            candidate ->
                normalize(candidate.provider()).equals(provider)
                    && normalize(candidate.model()).equals(model))
        .findFirst();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
