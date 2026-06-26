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
    String budgetCeilingReason,
    int inputTokens,
    int outputTokens,
    int cacheReadTokens,
    int cacheWriteTokens,
    int reasoningTokens,
    int totalTokens,
    double costUsd,
    long wallClockMs,
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
    budgetCeilingReason = budgetCeilingReason == null ? "" : budgetCeilingReason;
    inputTokens = Math.max(0, inputTokens);
    outputTokens = Math.max(0, outputTokens);
    cacheReadTokens = Math.max(0, cacheReadTokens);
    cacheWriteTokens = Math.max(0, cacheWriteTokens);
    reasoningTokens = Math.max(0, reasoningTokens);
    totalTokens = Math.max(0, totalTokens);
    costUsd = Math.max(0.0, costUsd);
    wallClockMs = Math.max(0L, wallClockMs);
  }

  public GoalDonenessDecision(
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
    this(
        state,
        recommendedDecision,
        reason,
        goalSummary,
        completedItems,
        incompleteItems,
        evidence,
        risks,
        "",
        0,
        0,
        0,
        0,
        0,
        0,
        0.0,
        0L,
        userInputRequired,
        anotherModelProviderMightHelp);
  }
}
