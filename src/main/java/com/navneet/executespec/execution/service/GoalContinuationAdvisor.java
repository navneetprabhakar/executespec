package com.navneet.executespec.execution.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.execution.model.GoalContinuationAdvisorRequest;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalContinuationRequest;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderCandidateRequest;
import com.navneet.executespec.router.ModelProviderCandidateService;
import com.navneet.executespec.router.ModelProviderRankingDecision;
import com.navneet.executespec.router.ModelProviderRankingPolicy;
import com.navneet.executespec.router.ModelProviderScore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runtime-facing adapter that turns live model/provider candidates into continuation advice.
 *
 * <p>This is still advisory: it does not dispatch workers, mutate runs, or switch providers.
 */
@Component
public class GoalContinuationAdvisor {

  private final ModelProviderCandidateService candidateService;
  private final RunEffectiveRouteService effectiveRoutes;
  private final GoalContinuationPolicy continuationPolicy = new GoalContinuationPolicy();

  public GoalContinuationAdvisor(ModelProviderCandidateService candidateService) {
    this(candidateService, null);
  }

  @Autowired
  public GoalContinuationAdvisor(
      ModelProviderCandidateService candidateService, RunEffectiveRouteService effectiveRoutes) {
    this.candidateService = candidateService;
    this.effectiveRoutes = effectiveRoutes;
  }

  @Value("${execution.continuation.quality-tie-band:1.0}")
  private double rankingQualityTieBand = ModelProviderRankingPolicy.DEFAULT_QUALITY_TIE_BAND;

  public GoalContinuationPolicyDecision advise(GoalContinuationAdvisorRequest request) {
    if (request == null || request.goalDecision() == null) {
      return new GoalContinuationPolicyDecision(
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          Optional.empty(),
          List.of("goal continuation advisor request is missing required goal evidence"));
    }

    EffectiveRouteProjection physicalRoute = effectiveCurrentRoute(request);
    String currentProvider =
        physicalRoute.attribution() == EffectiveRouteProjection.Attribution.LEDGER
            ? physicalRoute.provider()
            : request.currentProvider();
    String currentModel =
        physicalRoute.attribution() == EffectiveRouteProjection.Attribution.LEDGER
            ? physicalRoute.model()
            : request.currentModel();

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
                .requiredContextTokens(request.requiredContextTokens())
                .build());
    double qualityTieBand = Math.max(0.0, rankingQualityTieBand);
    ModelProviderRankingDecision ranking =
        new ModelProviderRankingPolicy(qualityTieBand).rank(candidates);
    ModelProviderScore current =
        findCurrentCandidate(candidates, currentProvider, currentModel).orElse(null);

    GoalContinuationPolicyDecision decision =
        continuationPolicy.decide(
            GoalContinuationRequest.builder()
                .goalDecision(request.goalDecision())
                .currentCandidate(current)
                .rankingDecision(ranking)
                .qualityInsufficient(request.qualityInsufficient())
                .providerBehaviorPoor(request.providerBehaviorPoor())
                .taskSimplerThanExpected(request.taskSimplerThanExpected())
                .routePinned(request.routePinned())
                .expensiveContinuationRequiresConfirmation(
                    request.expensiveContinuationRequiresConfirmation())
                .benchmarkQualityPriorRequiredForAutomaticContinuation(
                    request.benchmarkQualityPriorRequiredForAutomaticContinuation())
                .userConfirmedExpensiveContinuation(request.userConfirmedExpensiveContinuation())
                .userConfirmedContinuationAction(request.userConfirmedContinuationAction())
                .userConfirmedContinuationProvider(request.userConfirmedContinuationProvider())
                .userConfirmedContinuationModel(request.userConfirmedContinuationModel())
                .providerSwitchesUsed(request.providerSwitchesUsed())
                .maxProviderSwitches(request.maxProviderSwitches())
                .modelStepUpsUsed(request.modelStepUpsUsed())
                .maxModelStepUps(request.maxModelStepUps())
                .modelStepDownsUsed(request.modelStepDownsUsed())
                .maxModelStepDowns(request.maxModelStepDowns())
                .build());
    return withRankingEvidence(
        decision,
        ranking,
        qualityTieBand,
        request.routePinned(),
        physicalRoute,
        request.currentProvider(),
        request.currentModel(),
        currentProvider,
        currentModel);
  }

  private static GoalContinuationPolicyDecision withRankingEvidence(
      GoalContinuationPolicyDecision decision,
      ModelProviderRankingDecision ranking,
      double qualityTieBand,
      boolean routePinned,
      EffectiveRouteProjection physicalRoute,
      String requestedCurrentProvider,
      String requestedCurrentModel,
      String currentProvider,
      String currentModel) {
    if (decision == null) {
      return null;
    }
    Map<String, Object> policy = new LinkedHashMap<>();
    if (decision.policy() != null) {
      policy.putAll(decision.policy());
    }
    policy.put("rankingPolicy", "quality_desc_then_cost_asc_then_latency_asc");
    policy.put("rankingQualityTieBand", qualityTieBand);
    policy.put("routePinned", routePinned);
    policy.put("currentRouteAttribution", physicalRoute.attribution().name());
    putIfPresent(policy, "requestedCurrentProvider", requestedCurrentProvider);
    putIfPresent(policy, "requestedCurrentModel", requestedCurrentModel);
    putIfPresent(policy, "currentProvider", currentProvider);
    putIfPresent(policy, "currentModel", currentModel);
    policy.put("rankingReasons", decisionAwareRankingReasons(decision, ranking));
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

  private static List<String> decisionAwareRankingReasons(
      GoalContinuationPolicyDecision decision, ModelProviderRankingDecision ranking) {
    if (decision == null || ranking == null) {
      return List.of();
    }
    ModelProviderScore selected = decision.selectedCandidate().orElse(null);
    ModelProviderScore top = ranking.eligible().isEmpty() ? null : ranking.eligible().getFirst();
    if (selected == null || top == null || sameCandidate(selected, top)) {
      return ranking.reasons();
    }
    return List.of(
        "retained "
            + selected.provider()
            + "/"
            + selected.model()
            + " because decision="
            + decision.decision()
            + "; the globally top-ranked candidate was not selected for this continuation",
        "top ranked candidate "
            + top.provider()
            + "/"
            + top.model()
            + " remains visible in topEligibleCandidates for comparison");
  }

  private static boolean sameCandidate(ModelProviderScore left, ModelProviderScore right) {
    return left != null
        && right != null
        && normalize(left.provider()).equals(normalize(right.provider()))
        && normalize(left.model()).equals(normalize(right.model()));
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
      List<ModelProviderScore> candidates, String currentProvider, String currentModel) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    String provider = normalize(currentProvider);
    String model = normalize(currentModel);
    return candidates.stream()
        .filter(
            candidate ->
                normalize(candidate.provider()).equals(provider)
                    && normalize(candidate.model()).equals(model))
        .findFirst();
  }

  private EffectiveRouteProjection effectiveCurrentRoute(GoalContinuationAdvisorRequest request) {
    if (effectiveRoutes == null || request == null) return EffectiveRouteProjection.UNATTRIBUTED;
    return effectiveRoutes.latestSuccessfulForTask(request.runId(), request.taskId());
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static void putIfPresent(Map<String, Object> policy, String key, String value) {
    if (value != null && !value.isBlank()) policy.put(key, value);
  }
}
