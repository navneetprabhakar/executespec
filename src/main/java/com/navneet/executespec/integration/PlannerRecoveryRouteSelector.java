package com.navneet.executespec.integration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.router.AgentTier;
import com.navneet.executespec.router.AvailableProviderResolver;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelProviderCandidateRequest;
import com.navneet.executespec.router.ModelProviderCandidateService;
import com.navneet.executespec.router.ModelProviderRankingPolicy;
import com.navneet.executespec.router.ModelProviderScore;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Selects one bounded, run-authorized planner escalation from the effective model matrix. */
@Component
@RequiredArgsConstructor
class PlannerRecoveryRouteSelector {

  enum BlockReason {
    ROUTE_PINNED,
    CONFIRMATION_REQUIRED,
    NO_AVAILABLE_MATRIX_ROUTE,
    BENCHMARK_EVIDENCE_REQUIRED,
    NO_QUALITY_STEP_UP,
    ESCALATION_LIMIT_EXHAUSTED
  }

  record Policy(
      boolean routePinned,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      int maxModelStepUps,
      int maxProviderChanges) {
    Policy {
      maxModelStepUps = Math.max(0, maxModelStepUps);
      maxProviderChanges = Math.max(0, maxProviderChanges);
    }

    static Policy runAuthorized(
        boolean routePinned, boolean confirmationRequired, boolean benchmarkPriorRequired) {
      return new Policy(routePinned, confirmationRequired, benchmarkPriorRequired, 1, 1);
    }
  }

  record Selection(ResolvedModel route, BlockReason blockReason, List<String> reasons) {
    Selection {
      reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    static Selection selected(ResolvedModel route, List<String> reasons) {
      return new Selection(route, null, reasons);
    }

    static Selection blocked(BlockReason reason, String detail) {
      return new Selection(null, reason, List.of(detail));
    }

    boolean selected() {
      return route != null;
    }
  }

  private final AvailableProviderResolver availableProviders;
  private final ModelResolver modelResolver;
  private final ModelProviderCandidateService candidateService;

  Selection select(String runId, ResolvedModel currentRoute, Policy policy) {
    if (policy.routePinned()) {
      return Selection.blocked(BlockReason.ROUTE_PINNED, "exact run route forbids escalation");
    }
    if (policy.confirmationRequired()) {
      return Selection.blocked(
          BlockReason.CONFIRMATION_REQUIRED,
          "run policy requires confirmation before model/provider escalation");
    }

    Set<LlmProvider> available = availableProviders.resolve(runId);
    Map<LlmProvider, ResolvedModel> effectivePlanningMatrix = new EnumMap<>(LlmProvider.class);
    for (LlmProvider provider : available) {
      try {
        ResolvedModel route =
            modelResolver.resolve("OutlinePlannerAgent", provider, AgentTier.PLANNING);
        if (route != null
            && route.getActualProvider() == provider
            && route.getModel() != null
            && !route.getModel().isBlank()) {
          effectivePlanningMatrix.put(provider, route);
        }
      } catch (RuntimeException ignored) {
        // One invalid/retired matrix cell must not hide other explicitly allowed providers.
      }
    }
    if (effectivePlanningMatrix.isEmpty()) {
      return Selection.blocked(
          BlockReason.NO_AVAILABLE_MATRIX_ROUTE,
          "no available provider has an effective PLANNING model-matrix route");
    }

    Map<String, Boolean> availability = new LinkedHashMap<>();
    effectivePlanningMatrix.keySet().forEach(p -> availability.put(p.name().toLowerCase(), true));
    List<ModelProviderScore> candidates;
    try {
      candidates =
          candidateService.buildCandidates(
              ModelProviderCandidateRequest.builder()
                  .taskCategory("PLANNING_CONTRACT_REPAIR")
                  .requiredQualityThreshold(0.0)
                  .providerAllowList(new ArrayList<>(availability.keySet()))
                  .modelAllowList(
                      effectivePlanningMatrix.values().stream()
                          .map(ResolvedModel::getModel)
                          .distinct()
                          .toList())
                  .providerAvailability(availability)
                  .providerPreferenceOrder(List.of(currentRoute.providerName()))
                  .build());
    } catch (RuntimeException candidateFailure) {
      return Selection.blocked(
          BlockReason.NO_AVAILABLE_MATRIX_ROUTE,
          "planner recovery candidate collection failed closed");
    }

    ModelProviderScore currentScore =
        candidates.stream()
            .filter(candidate -> sameRoute(candidate, currentRoute))
            .findFirst()
            .orElse(null);
    List<ModelProviderScore> eligible =
        candidates.stream()
            .filter(ModelProviderScore::providerAvailable)
            .filter(ModelProviderScore::contextFitsRequestedBudget)
            .filter(candidate -> effectivePair(effectivePlanningMatrix, candidate))
            .filter(candidate -> !sameRoute(candidate, currentRoute))
            .filter(
                candidate ->
                    !policy.benchmarkPriorRequired() || candidate.benchmarkQualityPriorAvailable())
            .filter(
                candidate ->
                    currentScore == null || candidate.qualityScore() > currentScore.qualityScore())
            .filter(candidate -> withinLimits(candidate, currentRoute, policy))
            .toList();
    if (eligible.isEmpty()) {
      if (policy.benchmarkPriorRequired()
          && candidates.stream()
              .filter(candidate -> !sameRoute(candidate, currentRoute))
              .noneMatch(ModelProviderScore::benchmarkQualityPriorAvailable)) {
        return Selection.blocked(
            BlockReason.BENCHMARK_EVIDENCE_REQUIRED,
            "run policy requires compatible benchmark evidence for planner escalation");
      }
      boolean anyWithinLimit =
          candidates.stream()
              .filter(candidate -> !sameRoute(candidate, currentRoute))
              .anyMatch(candidate -> withinLimits(candidate, currentRoute, policy));
      return Selection.blocked(
          anyWithinLimit ? BlockReason.NO_QUALITY_STEP_UP : BlockReason.ESCALATION_LIMIT_EXHAUSTED,
          anyWithinLimit
              ? "effective model matrix contains no higher-quality planner route"
              : "run-level planner escalation limit is exhausted");
    }

    ModelProviderScore selected =
        new ModelProviderRankingPolicy().rank(eligible).selected().orElse(null);
    if (selected == null) {
      return Selection.blocked(
          BlockReason.NO_QUALITY_STEP_UP, "no eligible planner escalation route ranked");
    }
    ResolvedModel matrixRoute = effectivePlanningMatrix.get(parseProvider(selected.provider()));
    if (matrixRoute == null
        || !normalize(matrixRoute.getModel()).equals(normalize(selected.model()))) {
      return Selection.blocked(
          BlockReason.NO_AVAILABLE_MATRIX_ROUTE,
          "ranked route is outside the effective model matrix");
    }
    return Selection.selected(
        matrixRoute,
        List.of(
            "selected one bounded planner escalation from the effective PLANNING model matrix",
            "quality "
                + selected.qualityScore()
                + (currentScore == null
                    ? " (current route had no candidate score)"
                    : " > current " + currentScore.qualityScore())));
  }

  private static boolean withinLimits(
      ModelProviderScore candidate, ResolvedModel currentRoute, Policy policy) {
    boolean providerChange =
        !normalize(candidate.provider()).equals(normalize(currentRoute.providerName()));
    return providerChange ? policy.maxProviderChanges() > 0 : policy.maxModelStepUps() > 0;
  }

  private static boolean effectivePair(
      Map<LlmProvider, ResolvedModel> matrix, ModelProviderScore candidate) {
    ResolvedModel route = matrix.get(parseProvider(candidate.provider()));
    return route != null && normalize(route.getModel()).equals(normalize(candidate.model()));
  }

  private static boolean sameRoute(ModelProviderScore score, ResolvedModel route) {
    return normalize(score.provider()).equals(normalize(route.providerName()))
        && normalize(score.model()).equals(normalize(route.getModel()));
  }

  private static LlmProvider parseProvider(String provider) {
    String normalized = normalize(provider);
    if ("google".equals(normalized)) normalized = "gemini";
    try {
      return LlmProvider.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
