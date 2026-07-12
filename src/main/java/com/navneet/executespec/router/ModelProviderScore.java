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
        false,
        providerAvailability(reasons),
        reasons);
  }

  /**
   * Compatibility constructor for callers that already provide benchmark-prior state.
   *
   * <p>Compatibility callers do not have enough structured information to assert observed quality
   * evidence. They must migrate to the canonical constructor; human-readable reasons are never
   * interpreted as quality proof.
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
        false,
        providerAvailability(reasons),
        reasons);
  }

  public boolean meetsQualityThreshold() {
    return qualityScore >= requiredQualityThreshold;
  }

  public String label() {
    return provider + "/" + model;
  }

  private static boolean providerAvailability(List<String> reasons) {
    if (reasons == null) return true;
    return reasons.stream()
        .map(reason -> reason == null ? "" : reason.toLowerCase(java.util.Locale.ROOT))
        .noneMatch(reason -> reason.contains("provider unavailable"));
  }
}
