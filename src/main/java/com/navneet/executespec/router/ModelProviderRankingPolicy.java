package com.navneet.executespec.router;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Quality-first ranking policy for candidate model/provider choices.
 *
 * <p>The policy is deliberately lexicographic: candidates below the required quality threshold are
 * rejected before cost or latency are considered. Among eligible candidates, quality dominates
 * cost, and cost dominates latency. This prevents a cheaper or faster model from winning when it
 * cannot satisfy the task's configured quality bar.
 */
public class ModelProviderRankingPolicy {

  private static final Comparator<ModelProviderScore> ELIGIBLE_ORDER =
      Comparator.comparingDouble(ModelProviderScore::qualityScore)
          .reversed()
          .thenComparingDouble(ModelProviderScore::costPerMillionTokens)
          .thenComparingLong(ModelProviderScore::latencyP50Ms)
          .thenComparingLong(ModelProviderScore::latencyP95Ms)
          .thenComparing(ModelProviderScore::provider)
          .thenComparing(ModelProviderScore::model);

  public ModelProviderRankingDecision rank(List<ModelProviderScore> candidates) {
    if (candidates == null || candidates.isEmpty()) {
      return new ModelProviderRankingDecision(
          java.util.Optional.empty(), List.of(), List.of(), List.of("no candidates supplied"));
    }

    List<ModelProviderScore> eligible = new ArrayList<>();
    List<ModelProviderScore> rejected = new ArrayList<>();
    for (ModelProviderScore candidate : candidates) {
      if (candidate == null) {
        continue;
      }
      if (candidate.meetsQualityThreshold()) {
        eligible.add(candidate);
      } else {
        rejected.add(candidate);
      }
    }

    eligible.sort(ELIGIBLE_ORDER);
    rejected.sort(
        Comparator.comparing(ModelProviderScore::provider)
            .thenComparing(ModelProviderScore::model));

    if (eligible.isEmpty()) {
      return new ModelProviderRankingDecision(
          java.util.Optional.empty(), List.of(), rejected, List.of(noEligibleReason(rejected)));
    }

    ModelProviderScore selected = eligible.getFirst();
    List<String> reasons = new ArrayList<>();
    reasons.add(
        String.format(
            Locale.ROOT,
            "selected %s for %s: quality %.2f >= threshold %.2f; ranked by quality desc, cost asc,"
                + " latency asc",
            selected.label(),
            selected.taskCategory(),
            selected.qualityScore(),
            selected.requiredQualityThreshold()));
    if (!rejected.isEmpty()) {
      reasons.add(rejected.size() + " candidate(s) rejected below quality threshold");
    }
    return new ModelProviderRankingDecision(
        java.util.Optional.of(selected), eligible, rejected, reasons);
  }

  private static String noEligibleReason(List<ModelProviderScore> rejected) {
    if (rejected.isEmpty()) {
      return "no eligible candidates after null filtering";
    }
    return rejected.size()
        + " candidate(s) rejected below required quality threshold; no model/provider selected";
  }
}
