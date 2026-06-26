package com.navneet.executespec.execution.model;

import java.util.List;

/** Explainable output from evaluating whether a task/run goal is done. */
public record GoalDonenessDecision(
    GoalDonenessState state,
    RunContinuationDecision recommendedDecision,
    String reason,
    String goalSummary,
    List<String> completedItems,
    List<String> incompleteItems,
    List<String> evidence,
    List<String> risks,
    boolean userInputRequired,
    boolean anotherModelProviderMightHelp) {

  public GoalDonenessDecision {
    if (state == null) {
      throw new IllegalArgumentException("state required");
    }
    if (recommendedDecision == null) {
      throw new IllegalArgumentException("recommendedDecision required");
    }
    reason = reason == null || reason.isBlank() ? state.name() : reason;
    goalSummary = goalSummary == null || goalSummary.isBlank() ? "unspecified goal" : goalSummary;
    completedItems = completedItems == null ? List.of() : List.copyOf(completedItems);
    incompleteItems = incompleteItems == null ? List.of() : List.copyOf(incompleteItems);
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    risks = risks == null ? List.of() : List.copyOf(risks);
  }
}
