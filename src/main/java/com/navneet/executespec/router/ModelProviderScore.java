package com.navneet.executespec.router;

import java.util.List;

/**
 * Explainable candidate score for benchmark-informed model/provider selection.
 *
 * <p>{@code qualityScore} and {@code requiredQualityThreshold} use a 0-100 scale. Cost is
 * normalized to dollars per million tokens so candidates can be ranked without knowing the eventual
 * task size.
 */
public record ModelProviderScore(
    String provider,
    String model,
    String taskCategory,
    double qualityScore,
    double costPerMillionTokens,
    long latencyP50Ms,
    long latencyP95Ms,
    double requiredQualityThreshold,
    boolean benchmarkQualityPriorAvailable,
    boolean observedQualityEvidenceAvailable,
    boolean providerAvailable,
    List<String> reasons) {

  public ModelProviderScore {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model required");
    }
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public ModelProviderScore(
      String provider,
      String model,
      String taskCategory,
      double qualityScore,
      double costPerMillionTokens,
      long latencyP50Ms,
      long latencyP95Ms,
      double requiredQualityThreshold,
      List<String> reasons) {
    this(
        provider,
        model,
        taskCategory,
        qualityScore,
        costPerMillionTokens,
        latencyP50Ms,
        latencyP95Ms,
        requiredQualityThreshold,
        false,
        observedQualityEvidence(reasons, false),
        providerAvailability(reasons),
        reasons);
  }

  /**
   * Compatibility constructor for callers that already provide benchmark-prior state.
   *
   * <p>The explicit evidence bit is intentionally derived once at the score boundary instead of
   * making routing policy parse human-readable reason strings. New producers should use the
   * canonical constructor (or the capability registry) so the bit remains auditable.
   */
  public ModelProviderScore(
      String provider,
      String model,
      String taskCategory,
      double qualityScore,
      double costPerMillionTokens,
      long latencyP50Ms,
      long latencyP95Ms,
      double requiredQualityThreshold,
      boolean benchmarkQualityPriorAvailable,
      List<String> reasons) {
    this(
        provider,
        model,
        taskCategory,
        qualityScore,
        costPerMillionTokens,
        latencyP50Ms,
        latencyP95Ms,
        requiredQualityThreshold,
        benchmarkQualityPriorAvailable,
        observedQualityEvidence(reasons, benchmarkQualityPriorAvailable),
        providerAvailability(reasons),
        reasons);
  }

  public boolean meetsQualityThreshold() {
    return qualityScore >= requiredQualityThreshold;
  }

  public String label() {
    return provider + "/" + model;
  }

  private static boolean observedQualityEvidence(
      List<String> reasons, boolean benchmarkQualityPriorAvailable) {
    if (benchmarkQualityPriorAvailable || reasons == null) return benchmarkQualityPriorAvailable;
    return reasons.stream()
        .map(reason -> reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT))
        .anyMatch(
            reason ->
                reason.contains("persisted ")
                    && reason.contains("validation pass rate")
                    || reason.contains("local 24h success score")
                    || (reason.contains("quality ")
                        && reason.contains(" from [")
                        && !reason.contains("fallback")));
  }

  private static boolean providerAvailability(List<String> reasons) {
    if (reasons == null) return true;
    return reasons.stream()
        .map(reason -> reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT))
        .noneMatch(reason -> reason.contains("provider unavailable"));
  }
}
