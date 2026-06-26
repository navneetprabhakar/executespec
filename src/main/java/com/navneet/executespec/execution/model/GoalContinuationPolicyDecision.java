package com.navneet.executespec.execution.model;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.router.ModelProviderScore;

/** Explainable continuation/escalation/stop decision selected after goal evaluation. */
public record GoalContinuationPolicyDecision(
    RunContinuationDecision decision,
    Optional<ModelProviderScore> selectedCandidate,
    List<String> reasons) {

  public GoalContinuationPolicyDecision {
    if (decision == null) {
      throw new IllegalArgumentException("decision required");
    }
    selectedCandidate = selectedCandidate == null ? Optional.empty() : selectedCandidate;
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public boolean terminal() {
    return switch (decision) {
      case STOP_DONE, STOP_BLOCKED, STOP_FAILED, STOP_WASTE_GUARD -> true;
      case CONTINUE_SAME_MODEL_PROVIDER, STEP_UP_MODEL, STEP_DOWN_MODEL, CHANGE_PROVIDER -> false;
    };
  }
}
