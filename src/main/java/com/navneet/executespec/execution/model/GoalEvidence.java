package com.navneet.executespec.execution.model;

import java.util.List;

import lombok.Builder;

/**
 * Deterministic and model-judged evidence collected for a task/run goal.
 *
 * <p>This is deliberately provider-neutral and runtime-neutral. Current callers can populate only
 * the fields they know; future wiring can derive values from worker outcomes, smoke tests, VBC,
 * validation reports, cost snapshots, and run events.
 */
@Builder
public record GoalEvidence(
    String goalSummary,
    Boolean goalCriteriaSatisfied,
    Boolean validationPassed,
    boolean validationAttempted,
    boolean requiredOutputsPresent,
    boolean constraintsRespected,
    int meaningfulDiffCount,
    int noProgressIterations,
    int maxNoProgressIterations,
    int falseDoneClaims,
    int maxFalseDoneClaims,
    boolean budgetCeilingReached,
    boolean blockedByEnvironment,
    boolean unsafeContinuation,
    boolean completeTaskClaimed,
    List<String> completedItems,
    List<String> incompleteItems,
    List<String> evidence,
    List<String> risks) {

  public GoalEvidence {
    goalSummary = goalSummary == null || goalSummary.isBlank() ? "unspecified goal" : goalSummary;
    maxNoProgressIterations =
        maxNoProgressIterations <= 0 ? Integer.MAX_VALUE : maxNoProgressIterations;
    maxFalseDoneClaims = maxFalseDoneClaims <= 0 ? Integer.MAX_VALUE : maxFalseDoneClaims;
    completedItems = completedItems == null ? List.of() : List.copyOf(completedItems);
    incompleteItems = incompleteItems == null ? List.of() : List.copyOf(incompleteItems);
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    risks = risks == null ? List.of() : List.copyOf(risks);
  }

  public boolean hasMeaningfulProgress() {
    return meaningfulDiffCount > 0 || !completedItems.isEmpty();
  }

  public boolean validationFailed() {
    return validationAttempted && Boolean.FALSE.equals(validationPassed);
  }

  public boolean validationUnavailable() {
    return !validationAttempted || validationPassed == null;
  }
}
