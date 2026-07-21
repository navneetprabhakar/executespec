package com.navneet.executespec.router;

import java.util.List;
import java.util.Map;

import lombok.Builder;

/**
 * Runtime inputs for building model/provider candidates from the YAML matrix plus observed signals.
 *
 * <p>The request is intentionally selection-only: it filters candidate models and supplies
 * availability or external benchmark signals, but the strict quality/cost/latency ordering remains
 * owned by {@link ModelProviderRankingPolicy}.
 */
@Builder
public record ModelProviderCandidateRequest(
    String taskCategory,
    String taskComplexity,
    double requiredQualityThreshold,
    List<String> providerAllowList,
    List<String> modelAllowList,
    List<ModelProviderCapabilitySignal> externalSignals,
    Map<String, Boolean> providerAvailability,
    List<String> providerPreferenceOrder,
    long requiredContextTokens) {

  /** Compatibility constructor for callers that do not yet project a context budget. */
  public ModelProviderCandidateRequest(
      String taskCategory,
      String taskComplexity,
      double requiredQualityThreshold,
      List<String> providerAllowList,
      List<String> modelAllowList,
      List<ModelProviderCapabilitySignal> externalSignals,
      Map<String, Boolean> providerAvailability,
      List<String> providerPreferenceOrder) {
    this(
        taskCategory,
        taskComplexity,
        requiredQualityThreshold,
        providerAllowList,
        modelAllowList,
        externalSignals,
        providerAvailability,
        providerPreferenceOrder,
        0L);
  }

  public ModelProviderCandidateRequest {
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    taskComplexity =
        taskComplexity == null || taskComplexity.isBlank() ? "UNKNOWN" : taskComplexity;
    // RR-397 fail-closed: Math.max/min preserve NaN, so a NaN threshold would flow through intact
    // and, because every comparison against NaN is false, make every candidate look eligible.
    // Coerce a non-finite threshold to the strictest bar (100.0) so a malformed config rejects
    // candidates rather than admitting all of them.
    requiredQualityThreshold =
        Double.isFinite(requiredQualityThreshold)
            ? Math.max(0.0, Math.min(100.0, requiredQualityThreshold))
            : 100.0;
    providerAllowList = providerAllowList == null ? List.of() : List.copyOf(providerAllowList);
    modelAllowList = modelAllowList == null ? List.of() : List.copyOf(modelAllowList);
    externalSignals = externalSignals == null ? List.of() : List.copyOf(externalSignals);
    providerAvailability =
        providerAvailability == null ? Map.of() : Map.copyOf(providerAvailability);
    providerPreferenceOrder =
        providerPreferenceOrder == null ? List.of() : List.copyOf(providerPreferenceOrder);
    requiredContextTokens = Math.max(0L, requiredContextTokens);
  }
}
