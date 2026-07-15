package com.navneet.executespec.router;

import java.util.List;

/**
 * Auditable result of the initial task model selection seam.
 *
 * <p>This is deliberately separate from continuation decisions: it describes what would be used for
 * the first worker attempt and never performs a provider call or mutates a run. When observed
 * quality evidence is absent, callers must honour the supplied current route.
 */
public record InitialRoutingDecision(
    String provider,
    String model,
    boolean changed,
    boolean observedQualityEvidenceUsed,
    List<ModelProviderScore> consideredCandidates,
    List<ModelProviderScore> rejectedCandidates,
    List<String> reasons) {

  public InitialRoutingDecision {
    if (provider == null || provider.isBlank()) {
      throw new IllegalArgumentException("provider required");
    }
    if (model == null || model.isBlank()) {
      throw new IllegalArgumentException("model required");
    }
    consideredCandidates =
        consideredCandidates == null ? List.of() : List.copyOf(consideredCandidates);
    rejectedCandidates = rejectedCandidates == null ? List.of() : List.copyOf(rejectedCandidates);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public String routeLabel() {
    return provider + "/" + model;
  }
}
