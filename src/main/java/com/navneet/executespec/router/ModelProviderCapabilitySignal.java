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
    List<String> reasons,
    Boolean contextFitsRequestedBudget) {

  public ModelProviderCapabilitySignal {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model required");
    }
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    // RR-397 fail-closed: Math.max preserves NaN, so a non-finite penalty or cost would corrupt the
    // derived quality/cost ranking. A non-finite penalty is treated as none (0.0 — quality is then
    // floored downstream) and a non-finite cost as the maximum (so garbage can never rank
    // cheapest).
    recentFailurePenalty =
        Double.isFinite(recentFailurePenalty) ? Math.max(0.0, recentFailurePenalty) : 0.0;
    inputCostPerMillionTokens =
        Double.isFinite(inputCostPerMillionTokens)
            ? Math.max(0.0, inputCostPerMillionTokens)
            : Double.MAX_VALUE;
    outputCostPerMillionTokens =
        Double.isFinite(outputCostPerMillionTokens)
            ? Math.max(0.0, outputCostPerMillionTokens)
            : Double.MAX_VALUE;
    latencyP50Ms = Math.max(0L, latencyP50Ms);
    latencyP95Ms = Math.max(latencyP50Ms, latencyP95Ms);
    contextWindowTokens = Math.max(0, contextWindowTokens);
    providerAvailable = providerAvailable == null ? Boolean.TRUE : providerAvailable;
    fallbackOrder = Math.max(0, fallbackOrder);
    // RR-397 fail-closed: a non-finite threshold becomes the strictest bar (100.0) rather than
    // passing through as NaN and defeating every below-threshold rejection.
    requiredQualityThreshold =
        Double.isFinite(requiredQualityThreshold)
            ? Math.max(0.0, Math.min(100.0, requiredQualityThreshold))
            : 100.0;
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
    contextFitsRequestedBudget = contextFitsRequestedBudget == null || contextFitsRequestedBudget;
  }

  public double blendedCostPerMillionTokens() {
    return inputCostPerMillionTokens + outputCostPerMillionTokens;
  }
}
