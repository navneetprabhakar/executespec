package com.navneet.executespec.router;

import java.util.List;
import java.util.Optional;

/** Result of applying the quality-first model/provider ranking policy. */
public record ModelProviderRankingDecision(
    Optional<ModelProviderScore> selected,
    List<ModelProviderScore> eligible,
    List<ModelProviderScore> rejectedBelowThreshold,
    List<String> reasons) {

  public ModelProviderRankingDecision {
    selected = selected == null ? Optional.empty() : selected;
    eligible = eligible == null ? List.of() : List.copyOf(eligible);
    rejectedBelowThreshold =
        rejectedBelowThreshold == null ? List.of() : List.copyOf(rejectedBelowThreshold);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public boolean hasSelection() {
    return selected.isPresent();
  }
}
