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
 * unless candidates are inside the configured near-quality band; inside that band cost wins, then
 * latency. This prevents a cheaper or faster model from winning when it cannot satisfy the task's
 * configured quality bar, while making cost and latency meaningful for effectively equivalent
 * quality scores.
 */
public class ModelProviderRankingPolicy {

  public static final double DEFAULT_QUALITY_TIE_BAND = 1.0;

  private final double qualityTieBand;

  public ModelProviderRankingPolicy() {
    this(DEFAULT_QUALITY_TIE_BAND);
  }

  public ModelProviderRankingPolicy(double qualityTieBand) {
    this.qualityTieBand = Math.max(0.0, qualityTieBand);
  }

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

    eligible = orderEligible(eligible);
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
            "selected %s for %s: quality %.2f >= threshold %.2f; ranked by quality desc outside"
                + " %.2f quality points, then cost asc, latency asc inside that band",
            selected.label(),
            selected.taskCategory(),
            selected.qualityScore(),
            selected.requiredQualityThreshold(),
            qualityTieBand));
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

  private List<ModelProviderScore> orderEligible(List<ModelProviderScore> candidates) {
    List<ModelProviderScore> byQuality = new ArrayList<>(candidates);
    byQuality.sort(
        Comparator.comparingDouble(ModelProviderScore::qualityScore)
            .reversed()
            .thenComparingDouble(ModelProviderScore::costPerMillionTokens)
            .thenComparingLong(ModelProviderScore::latencyP50Ms)
            .thenComparingLong(ModelProviderScore::latencyP95Ms)
            .thenComparing(ModelProviderScore::provider)
            .thenComparing(ModelProviderScore::model));

    List<ModelProviderScore> ordered = new ArrayList<>();
    int index = 0;
    while (index < byQuality.size()) {
      double bandAnchorQuality = byQuality.get(index).qualityScore();
      List<ModelProviderScore> band = new ArrayList<>();
      while (index < byQuality.size()
          && bandAnchorQuality - byQuality.get(index).qualityScore() <= qualityTieBand) {
        band.add(byQuality.get(index));
        index++;
      }
      band.sort(
          Comparator.comparingDouble(ModelProviderScore::costPerMillionTokens)
              .thenComparingLong(ModelProviderScore::latencyP50Ms)
              .thenComparingLong(ModelProviderScore::latencyP95Ms)
              .thenComparing(Comparator.comparingDouble(ModelProviderScore::qualityScore).reversed())
              .thenComparing(ModelProviderScore::provider)
              .thenComparing(ModelProviderScore::model));
      ordered.addAll(band);
    }
    return ordered;
  }
}
