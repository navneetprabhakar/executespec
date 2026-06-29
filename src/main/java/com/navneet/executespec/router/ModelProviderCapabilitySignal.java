package com.navneet.executespec.router;

import java.util.List;

import lombok.Builder;

/**
 * Provider/model capability inputs collected from benchmark, local-run, validation, and config
 * sources before quality-first ranking.
 *
 * <p>All quality-like scores use a 0-100 scale. Null scores mean the signal is unavailable and are
 * excluded from the weighted quality calculation.
 */
@Builder
public record ModelProviderCapabilitySignal(
    String provider,
    String model,
    String taskCategory,
    Double benchmarkQualityScore,
    Double localSuccessRateScore,
    Double validationPassRateScore,
    Double codeReviewScore,
    Double toolReliabilityScore,
    double recentFailurePenalty,
    double inputCostPerMillionTokens,
    double outputCostPerMillionTokens,
    long latencyP50Ms,
    long latencyP95Ms,
    int contextWindowTokens,
    Boolean providerAvailable,
    int fallbackOrder,
    double requiredQualityThreshold,
    Double fallbackQualityScore,
    List<String> reasons) {

  public ModelProviderCapabilitySignal {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model required");
    }
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    recentFailurePenalty = Math.max(0.0, recentFailurePenalty);
    inputCostPerMillionTokens = Math.max(0.0, inputCostPerMillionTokens);
    outputCostPerMillionTokens = Math.max(0.0, outputCostPerMillionTokens);
    latencyP50Ms = Math.max(0L, latencyP50Ms);
    latencyP95Ms = Math.max(latencyP50Ms, latencyP95Ms);
    contextWindowTokens = Math.max(0, contextWindowTokens);
    providerAvailable = providerAvailable == null ? Boolean.TRUE : providerAvailable;
    fallbackOrder = Math.max(0, fallbackOrder);
    requiredQualityThreshold = Math.max(0.0, Math.min(100.0, requiredQualityThreshold));
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public double blendedCostPerMillionTokens() {
    return inputCostPerMillionTokens + outputCostPerMillionTokens;
  }
}
