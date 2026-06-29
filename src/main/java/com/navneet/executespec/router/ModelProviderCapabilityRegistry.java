package com.navneet.executespec.router;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure capability aggregator for benchmark-informed model/provider selection.
 *
 * <p>This converts externally collected signals into {@link ModelProviderScore} candidates. It does
 * not choose the winner; {@link ModelProviderRankingPolicy} still owns the strict
 * quality/cost/latency priority order.
 */
public class ModelProviderCapabilityRegistry {

  private static final double BENCHMARK_WEIGHT = 0.45;
  private static final double LOCAL_SUCCESS_WEIGHT = 0.25;
  private static final double VALIDATION_WEIGHT = 0.15;
  private static final double CODE_REVIEW_WEIGHT = 0.10;
  private static final double TOOL_RELIABILITY_WEIGHT = 0.05;
  private static final double UNAVAILABLE_PROVIDER_PENALTY = 100.0;
  private static final double FALLBACK_ORDER_PENALTY = 0.25;

  public List<ModelProviderScore> scoreAll(List<ModelProviderCapabilitySignal> signals) {
    if (signals == null || signals.isEmpty()) {
      return List.of();
    }
    List<ModelProviderScore> scores = new ArrayList<>();
    for (ModelProviderCapabilitySignal signal : signals) {
      if (signal != null) {
        scores.add(score(signal));
      }
    }
    return List.copyOf(scores);
  }

  public ModelProviderScore score(ModelProviderCapabilitySignal signal) {
    if (signal == null) {
      throw new IllegalArgumentException("signal required");
    }

    List<WeightedSignal> weightedSignals = weightedSignals(signal);
    double quality =
        weightedSignals.isEmpty()
            ? fallbackQuality(signal)
            : weightedSignals.stream().mapToDouble(WeightedSignal::weightedValue).sum()
                / weightedSignals.stream().mapToDouble(WeightedSignal::weight).sum();
    quality -= signal.recentFailurePenalty();
    quality -= signal.fallbackOrder() * FALLBACK_ORDER_PENALTY;
    if (!signal.providerAvailable()) {
      quality -= UNAVAILABLE_PROVIDER_PENALTY;
    }
    quality = clampQuality(quality);

    List<String> reasons = new ArrayList<>(signal.reasons());
    if (weightedSignals.isEmpty()) {
      reasons.add(
          String.format(
              Locale.ROOT,
              "quality %.2f from fallback score; benchmark/local validation signals unavailable",
              quality));
    } else {
      reasons.add(
          String.format(
              Locale.ROOT,
              "quality %.2f from %s",
              quality,
              weightedSignals.stream().map(WeightedSignal::name).toList()));
    }
    reasons.add(
        String.format(
            Locale.ROOT,
            "cost %.4f per 1M tokens (input %.4f + output %.4f)",
            signal.blendedCostPerMillionTokens(),
            signal.inputCostPerMillionTokens(),
            signal.outputCostPerMillionTokens()));
    reasons.add(
        "latency p50="
            + signal.latencyP50Ms()
            + "ms p95="
            + signal.latencyP95Ms()
            + "ms; context="
            + signal.contextWindowTokens());
    if (!signal.providerAvailable()) {
      reasons.add("provider unavailable; candidate is forced below threshold");
    }
    if (signal.recentFailurePenalty() > 0.0) {
      reasons.add(
          String.format(
              Locale.ROOT, "recent failure penalty %.2f applied", signal.recentFailurePenalty()));
    }

    return new ModelProviderScore(
        signal.provider(),
        signal.model(),
        signal.taskCategory(),
        quality,
        signal.blendedCostPerMillionTokens(),
        signal.latencyP50Ms(),
        signal.latencyP95Ms(),
        signal.requiredQualityThreshold(),
        signal.benchmarkQualityScore() != null,
        reasons);
  }

  private static List<WeightedSignal> weightedSignals(ModelProviderCapabilitySignal signal) {
    List<WeightedSignal> signals = new ArrayList<>();
    add(signals, "benchmark", signal.benchmarkQualityScore(), BENCHMARK_WEIGHT);
    add(signals, "local_success", signal.localSuccessRateScore(), LOCAL_SUCCESS_WEIGHT);
    add(signals, "validation", signal.validationPassRateScore(), VALIDATION_WEIGHT);
    add(signals, "code_review", signal.codeReviewScore(), CODE_REVIEW_WEIGHT);
    add(signals, "tool_reliability", signal.toolReliabilityScore(), TOOL_RELIABILITY_WEIGHT);
    return signals;
  }

  private static void add(List<WeightedSignal> signals, String name, Double value, double weight) {
    if (value != null) {
      signals.add(new WeightedSignal(name, clampQuality(value), weight));
    }
  }

  private static double fallbackQuality(ModelProviderCapabilitySignal signal) {
    if (signal.fallbackQualityScore() != null) {
      return clampQuality(signal.fallbackQualityScore());
    }
    return Math.min(60.0, signal.requiredQualityThreshold());
  }

  private static double clampQuality(double value) {
    return Math.max(0.0, Math.min(100.0, value));
  }

  private record WeightedSignal(String name, double value, double weight) {
    double weightedValue() {
      return value * weight;
    }
  }
}
