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
    List<String> reasons,
    boolean contextFitsRequestedBudget) {

  public ModelProviderScore {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model required");
    }
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    // RR-397 fail-closed: a NaN/infinite quality score or threshold must never be able to pass the
    // below-threshold check. Java comparisons against NaN are always false, so a NaN qualityScore
    // would slip past {@code score < threshold} rejection and a NaN threshold would let every score
    // look eligible. Collapse a non-finite score to the worst quality (0.0 — guaranteed below any
    // threshold and a stable sort key) and a non-finite threshold to the strictest bar (100.0 —
    // unsatisfiable by a garbage config), so a malformed input fails the decision rather than
    // silently making every candidate eligible for automatic continuation. Cost is coerced to a
    // large sentinel so a NaN cost never ranks as "cheapest".
    qualityScore = Double.isFinite(qualityScore) ? qualityScore : 0.0;
    costPerMillionTokens =
        Double.isFinite(costPerMillionTokens) ? costPerMillionTokens : Double.MAX_VALUE;
    requiredQualityThreshold =
        Double.isFinite(requiredQualityThreshold)
            ? Math.max(0.0, Math.min(100.0, requiredQualityThreshold))
            : 100.0;
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
        reasons,
        true);
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
        reasons,
        true);
  }

  /** Compatibility constructor retaining the pre-context-budget score shape. */
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
      boolean observedQualityEvidenceAvailable,
      boolean providerAvailable,
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
        observedQualityEvidenceAvailable,
        providerAvailable,
        reasons,
        true);
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
