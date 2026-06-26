package com.navneet.executespec.execution.service;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.GoalDonenessState;
import com.navneet.executespec.execution.model.GoalEvidence;
import com.navneet.executespec.execution.model.RunContinuationDecision;

/**
 * Pure policy for mapping goal evidence to a doneness state and next high-level decision.
 *
 * <p>This class does not dispatch tools, switch providers, retry, or mutate run state. Runtime
 * callers must still enforce budgets and apply any continuation decision through their existing
 * orchestrator/executor boundaries.
 */
public class GoalDonenessEvaluator {

  public GoalDonenessDecision evaluate(GoalEvidence evidence) {
    if (evidence == null) {
      return decision(
          GoalDonenessState.UNCERTAIN,
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          "goal evidence is missing; cannot prove completion",
          "unspecified goal",
          List.of(),
          List.of("collect goal evidence"),
          List.of(),
          List.of("missing evidence"),
          false,
          false);
    }

    if (evidence.blockedByEnvironment()) {
      return fromEvidence(
          evidence,
          GoalDonenessState.BLOCKED,
          RunContinuationDecision.STOP_BLOCKED,
          "blocked by environment or required external input",
          true,
          false);
    }

    if (evidence.unsafeContinuation()) {
      return fromEvidence(
          evidence,
          GoalDonenessState.BLOCKED,
          RunContinuationDecision.STOP_BLOCKED,
          "continuation would be unsafe or outside the goal scope",
          true,
          false);
    }

    if (isDone(evidence)) {
      return fromEvidence(
          evidence,
          GoalDonenessState.DONE,
          RunContinuationDecision.STOP_DONE,
          "goal satisfied with validation evidence and required outputs present",
          false,
          false);
    }

    if (evidence.budgetCeilingReached()) {
      return fromEvidence(
          evidence,
          GoalDonenessState.WASTE_RISK,
          RunContinuationDecision.STOP_WASTE_GUARD,
          evidence.budgetCeilingReason() + " before proof of completion",
          false,
          false);
    }

    if (repeatedFalseCompletion(evidence)) {
      return fromEvidence(
          evidence,
          GoalDonenessState.WASTE_RISK,
          RunContinuationDecision.STOP_WASTE_GUARD,
          "repeated false completion claims while validation still fails",
          false,
          false);
    }

    if (noProgressLimitReached(evidence)) {
      return fromEvidence(
          evidence,
          GoalDonenessState.WASTE_RISK,
          RunContinuationDecision.STOP_WASTE_GUARD,
          "no meaningful progress within the configured no-progress limit",
          false,
          false);
    }

    if (evidence.validationFailed()) {
      if (evidence.hasMeaningfulProgress()) {
        return fromEvidence(
            evidence,
            GoalDonenessState.NOT_DONE_BUT_PROGRESSING,
            RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
            "validation failed, but the task has produced meaningful progress",
            false,
            true);
      }
      return fromEvidence(
          evidence,
          GoalDonenessState.FAILED,
          RunContinuationDecision.STOP_FAILED,
          "validation failed and no useful progress evidence remains",
          false,
          true);
    }

    if (evidence.hasMeaningfulProgress()) {
      return fromEvidence(
          evidence,
          GoalDonenessState.NOT_DONE_BUT_PROGRESSING,
          RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
          "goal is not proven done yet, but meaningful progress exists",
          false,
          false);
    }

    return fromEvidence(
        evidence,
        GoalDonenessState.UNCERTAIN,
        RunContinuationDecision.CONTINUE_SAME_MODEL_PROVIDER,
        "goal completion is unproven because validation evidence is missing or incomplete",
        false,
        false);
  }

  private static boolean isDone(GoalEvidence evidence) {
    return Boolean.TRUE.equals(evidence.goalCriteriaSatisfied())
        && Boolean.TRUE.equals(evidence.validationPassed())
        && evidence.requiredOutputsPresent()
        && evidence.constraintsRespected();
  }

  private static boolean repeatedFalseCompletion(GoalEvidence evidence) {
    return evidence.completeTaskClaimed()
        && evidence.validationFailed()
        && evidence.falseDoneClaims() >= evidence.maxFalseDoneClaims();
  }

  private static boolean noProgressLimitReached(GoalEvidence evidence) {
    return !evidence.hasMeaningfulProgress()
        && evidence.noProgressIterations() >= evidence.maxNoProgressIterations();
  }

  private static GoalDonenessDecision fromEvidence(
      GoalEvidence evidence,
      GoalDonenessState state,
      RunContinuationDecision recommendedDecision,
      String reason,
      boolean userInputRequired,
      boolean anotherModelProviderMightHelp) {
    return decision(
        state,
        recommendedDecision,
        reason,
        evidence.goalSummary(),
        evidence.completedItems(),
        incompleteItemsFor(evidence, state),
        evidence.evidence(),
        risksFor(evidence, reason),
        userInputRequired,
        anotherModelProviderMightHelp);
  }

  private static List<String> incompleteItemsFor(GoalEvidence evidence, GoalDonenessState state) {
    if (!evidence.incompleteItems().isEmpty() || state == GoalDonenessState.DONE) {
      return evidence.incompleteItems();
    }
    List<String> items = new ArrayList<>();
    if (!Boolean.TRUE.equals(evidence.goalCriteriaSatisfied())) {
      items.add("goal criteria not proven satisfied");
    }
    if (!Boolean.TRUE.equals(evidence.validationPassed())) {
      items.add(evidence.validationUnavailable() ? "validation not proven" : "validation failed");
    }
    if (!evidence.requiredOutputsPresent()) {
      items.add("required outputs not proven present");
    }
    if (!evidence.constraintsRespected()) {
      items.add("constraints not proven respected");
    }
    return List.copyOf(items);
  }

  private static List<String> risksFor(GoalEvidence evidence, String reason) {
    List<String> risks = new ArrayList<>(evidence.risks());
    if (risks.stream().noneMatch(reason::equals)) {
      risks.add(reason);
    }
    return List.copyOf(risks);
  }

  private static GoalDonenessDecision decision(
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
    return new GoalDonenessDecision(
        state,
        recommendedDecision,
        reason,
        goalSummary,
        completedItems,
        incompleteItems,
        evidence,
        risks,
        userInputRequired,
        anotherModelProviderMightHelp);
  }
}
