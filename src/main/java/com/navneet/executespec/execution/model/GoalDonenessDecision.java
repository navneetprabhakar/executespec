package com.navneet.executespec.execution.model;

import java.util.List;

/** Explainable output from evaluating whether a task/run goal is done. */
public record GoalDonenessDecision(
    GoalDonenessState state,
    RunContinuationDecision recommendedDecision,
    String reason,
    String recommendedNextStep,
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
    recommendedNextStep =
        recommendedNextStep == null || recommendedNextStep.isBlank()
            ? defaultRecommendedNextStep(
                recommendedDecision, userInputRequired, anotherModelProviderMightHelp)
            : recommendedNextStep;
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
    this(
        state,
        recommendedDecision,
        reason,
        "",
        goalSummary,
        completedItems,
        incompleteItems,
        evidence,
        risks,
        budgetCeilingReason,
        inputTokens,
        outputTokens,
        cacheReadTokens,
        cacheWriteTokens,
        reasoningTokens,
        totalTokens,
        costUsd,
        wallClockMs,
        userInputRequired,
        anotherModelProviderMightHelp);
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
        "",
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

  private static String defaultRecommendedNextStep(
      RunContinuationDecision recommendedDecision,
      boolean userInputRequired,
      boolean anotherModelProviderMightHelp) {
    return switch (recommendedDecision) {
      case STOP_DONE -> "No further work is required for this task.";
      case STOP_BLOCKED ->
          userInputRequired
              ? "Collect the required user input or fix the environment blocker, then rerun or"
                  + " resume."
              : "Review the blocked continuation risk before rerunning.";
      case STOP_FAILED ->
          anotherModelProviderMightHelp
              ? "Retry with stronger validation evidence or an alternate model/provider if the goal"
                  + " still matters."
              : "Stop this attempt and inspect the failed evidence before rerunning.";
      case STOP_WASTE_GUARD ->
          anotherModelProviderMightHelp
              ? "Use one bounded escalation or provider change only if a stronger eligible"
                  + " candidate remains; otherwise stop."
              : "Stop this attempt and inspect the evidence before spending more tokens.";
      case CONTINUE_SAME_MODEL_PROVIDER ->
          "Continue with the same model/provider while collecting stronger validation evidence.";
      case STEP_UP_MODEL -> "Step up to the selected stronger model and rerun the task once.";
      case STEP_DOWN_MODEL ->
          "Step down only to a cheaper/faster model that still clears the quality threshold.";
      case CHANGE_PROVIDER ->
          "Switch only to the selected eligible provider and rerun within configured limits.";
    };
  }
}
