package com.navneet.executespec.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Pure orchestration seam for selecting the first worker route from existing candidate signals.
 *
 * <p>The service intentionally does not call an LLM, write a run, or change provider spend by
 * itself. It delegates candidate construction to {@link ModelProviderCandidateService} and the
 * quality/cost/latency ordering to {@link ModelProviderRankingPolicy}. A route is changed only when
 * the selected candidate carries observed quality evidence; YAML fallback quality alone is not
 * enough to override the caller's current route.
 */
@Service
@RequiredArgsConstructor
public class InitialRoutingService {

  private final ModelProviderCandidateService candidateService;
  private final ModelProviderRankingPolicy rankingPolicy = new ModelProviderRankingPolicy();

  public InitialRoutingDecision decide(
      String currentProvider, String currentModel, ModelProviderCandidateRequest request) {
    requireRoute(currentProvider, currentModel);
    List<ModelProviderScore> candidates;
    try {
      candidates = candidateService.buildCandidates(request);
    } catch (RuntimeException ex) {
      return unchanged(
          currentProvider,
          currentModel,
          List.of("initial candidate collection failed closed: " + safeMessage(ex)));
    }
    if (candidates == null || candidates.isEmpty()) {
      return unchanged(currentProvider, currentModel, List.of("no initial routing candidates"));
    }

    List<ModelProviderScore> available = new ArrayList<>();
    List<ModelProviderScore> rejected = new ArrayList<>();
    for (ModelProviderScore candidate : candidates) {
      if (candidate == null) continue;
      if (isUnavailable(candidate)) rejected.add(candidate);
      else available.add(candidate);
    }
    if (available.isEmpty()) {
      return new InitialRoutingDecision(
          currentProvider,
          currentModel,
          false,
          false,
          List.of(),
          rejected,
          List.of("all initial routing candidates were unavailable; kept current route"));
    }

    ModelProviderRankingDecision ranked = rankingPolicy.rank(available);
    ModelProviderScore selected = ranked.selected().orElse(null);
    if (selected == null) {
      return new InitialRoutingDecision(
          currentProvider,
          currentModel,
          false,
          false,
          ranked.eligible(),
          concat(rejected, ranked.rejectedBelowThreshold()),
          append(ranked.reasons(), "no eligible initial route; kept current route"));
    }
    if (!hasObservedQualityEvidence(selected)) {
      return new InitialRoutingDecision(
          currentProvider,
          currentModel,
          false,
          false,
          ranked.eligible(),
          concat(rejected, ranked.rejectedBelowThreshold()),
          append(
              ranked.reasons(),
              "selected candidate has no observed quality evidence; kept current route"));
    }

    boolean changed =
        !currentProvider.equalsIgnoreCase(selected.provider())
            || !currentModel.equalsIgnoreCase(selected.model());
    return new InitialRoutingDecision(
        selected.provider(),
        selected.model(),
        changed,
        true,
        ranked.eligible(),
        concat(rejected, ranked.rejectedBelowThreshold()),
        append(ranked.reasons(), "observed quality evidence authorized initial route selection"));
  }

  private static InitialRoutingDecision unchanged(
      String provider, String model, List<String> reasons) {
    return new InitialRoutingDecision(provider, model, false, false, List.of(), List.of(), reasons);
  }

  private static boolean isUnavailable(ModelProviderScore candidate) {
    return candidate.reasons().stream()
        .map(reason -> reason == null ? "" : reason.toLowerCase(Locale.ROOT))
        .anyMatch(reason -> reason.contains("provider unavailable"));
  }

  private static boolean hasObservedQualityEvidence(ModelProviderScore candidate) {
    if (candidate.benchmarkQualityPriorAvailable()) return true;
    return candidate.reasons().stream()
        .map(reason -> reason == null ? "" : reason.toLowerCase(Locale.ROOT))
        .anyMatch(
            reason ->
                reason.contains("persisted ") && reason.contains("validation pass rate")
                    || reason.contains("local 24h success score")
                    || (reason.contains("quality ")
                        && reason.contains(" from [")
                        && !reason.contains("fallback")));
  }

  private static List<ModelProviderScore> concat(
      List<ModelProviderScore> first, List<ModelProviderScore> second) {
    List<ModelProviderScore> out = new ArrayList<>();
    if (first != null) out.addAll(first);
    if (second != null) out.addAll(second);
    return List.copyOf(out);
  }

  private static List<String> append(List<String> reasons, String reason) {
    List<String> out = new ArrayList<>(reasons == null ? List.of() : reasons);
    out.add(reason);
    return List.copyOf(out);
  }

  private static String safeMessage(RuntimeException ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }

  private static void requireRoute(String provider, String model) {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
  }
}
