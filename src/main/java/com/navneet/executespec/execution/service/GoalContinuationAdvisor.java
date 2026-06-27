package com.navneet.executespec.execution.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    GoalContinuationPolicyDecision decision =
        continuationPolicy.decide(
            GoalContinuationRequest.builder()
                .goalDecision(request.goalDecision())
                .currentCandidate(current)
                .rankingDecision(ranking)
                .qualityInsufficient(request.qualityInsufficient())
                .providerBehaviorPoor(request.providerBehaviorPoor())
                .taskSimplerThanExpected(request.taskSimplerThanExpected())
                .expensiveContinuationRequiresConfirmation(
                    request.expensiveContinuationRequiresConfirmation())
                .benchmarkQualityPriorRequiredForAutomaticContinuation(
                    request.benchmarkQualityPriorRequiredForAutomaticContinuation())
                .userConfirmedExpensiveContinuation(request.userConfirmedExpensiveContinuation())
                .providerSwitchesUsed(request.providerSwitchesUsed())
                .maxProviderSwitches(request.maxProviderSwitches())
                .modelStepUpsUsed(request.modelStepUpsUsed())
                .maxModelStepUps(request.maxModelStepUps())
                .modelStepDownsUsed(request.modelStepDownsUsed())
                .maxModelStepDowns(request.maxModelStepDowns())
                .build());
    return withRankingEvidence(decision, ranking);
  }

  private static GoalContinuationPolicyDecision withRankingEvidence(
      GoalContinuationPolicyDecision decision, ModelProviderRankingDecision ranking) {
    if (decision == null) {
      return null;
    }
    Map<String, Object> policy = new LinkedHashMap<>();
    if (decision.policy() != null) {
      policy.putAll(decision.policy());
    }
    policy.put("rankingPolicy", "quality_desc_then_cost_asc_then_latency_asc");
    policy.put("rankingReasons", ranking == null ? List.of() : ranking.reasons());
    policy.put(
        "eligibleCandidateCount", ranking == null ? 0 : Math.max(0, ranking.eligible().size()));
    policy.put(
        "rejectedBelowQualityThresholdCount",
        ranking == null ? 0 : Math.max(0, ranking.rejectedBelowThreshold().size()));
    policy.put(
        "topEligibleCandidates",
        ranking == null ? List.of() : candidateSummaries(ranking.eligible(), 5));
    policy.put(
        "topRejectedCandidates",
        ranking == null ? List.of() : candidateSummaries(ranking.rejectedBelowThreshold(), 5));
    return new GoalContinuationPolicyDecision(
        decision.decision(), decision.selectedCandidate(), decision.reasons(), policy);
  }

  private static List<Map<String, Object>> candidateSummaries(
      List<ModelProviderScore> candidates, int limit) {
    if (candidates == null || candidates.isEmpty() || limit <= 0) {
      return List.of();
    }
    return candidates.stream().limit(limit).map(GoalContinuationAdvisor::candidateSummary).toList();
  }

  private static Map<String, Object> candidateSummary(ModelProviderScore candidate) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("provider", candidate.provider());
    out.put("model", candidate.model());
    out.put("qualityScore", candidate.qualityScore());
    out.put("requiredQualityThreshold", candidate.requiredQualityThreshold());
    out.put("costPerMillionTokens", candidate.costPerMillionTokens());
    out.put("latencyP50Ms", candidate.latencyP50Ms());
    out.put("latencyP95Ms", candidate.latencyP95Ms());
    out.put("benchmarkQualityPriorAvailable", candidate.benchmarkQualityPriorAvailable());
    return out;
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
