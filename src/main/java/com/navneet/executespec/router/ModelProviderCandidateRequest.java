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
    double requiredQualityThreshold,
    List<String> providerAllowList,
    List<String> modelAllowList,
    List<ModelProviderCapabilitySignal> externalSignals,
    Map<String, Boolean> providerAvailability,
    List<String> providerPreferenceOrder) {

  public ModelProviderCandidateRequest {
    taskCategory = taskCategory == null || taskCategory.isBlank() ? "UNKNOWN" : taskCategory;
    requiredQualityThreshold = Math.max(0.0, Math.min(100.0, requiredQualityThreshold));
    providerAllowList = providerAllowList == null ? List.of() : List.copyOf(providerAllowList);
    modelAllowList = modelAllowList == null ? List.of() : List.copyOf(modelAllowList);
    externalSignals = externalSignals == null ? List.of() : List.copyOf(externalSignals);
    providerAvailability =
        providerAvailability == null ? Map.of() : Map.copyOf(providerAvailability);
    providerPreferenceOrder =
        providerPreferenceOrder == null ? List.of() : List.copyOf(providerPreferenceOrder);
  }
}
