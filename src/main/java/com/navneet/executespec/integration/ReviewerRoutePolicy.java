package com.navneet.executespec.integration;

import java.util.List;

import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;

/** Selects the reviewer route promoted by the latest successful quality continuation. */
final class ReviewerRoutePolicy {

  private ReviewerRoutePolicy() {}

  static RunOrchestrator.ProviderModel select(
      String defaultProvider, String defaultModel, ExecutionResult execution) {
    var fallback = new RunOrchestrator.ProviderModel(defaultProvider, defaultModel);
    if (execution == null || execution.perTask() == null || execution.perTask().isEmpty()) {
      return fallback;
    }
    List<TaskOutcome> tasks = execution.perTask();
    for (int index = tasks.size() - 1; index >= 0; index--) {
      TaskOutcome task = tasks.get(index);
      if (!successful(task) || !qualityContinuation(task)) continue;
      var candidate =
          new RunOrchestrator.ProviderModel(
              task.continuationActionProvider(), task.continuationActionModel());
      return sameRoute(defaultProvider, defaultModel, candidate) ? fallback : candidate;
    }
    return fallback;
  }

  private static boolean successful(TaskOutcome task) {
    if (task == null || task.status() == null) return false;
    return switch (task.status()) {
      case SUCCESS, SUCCESS_AFTER_RETRY, COMPLETED_WITH_WARNING -> true;
      case WORKER_FAILED, SKIPPED -> false;
    };
  }

  private static boolean qualityContinuation(TaskOutcome task) {
    if (!hasText(task.continuationActionProvider()) || !hasText(task.continuationActionModel())) {
      return false;
    }
    RunContinuationDecision action = task.continuationAction();
    return action == RunContinuationDecision.STEP_UP_MODEL
        || action == RunContinuationDecision.CHANGE_PROVIDER;
  }

  static boolean sameRoute(String provider, String model, RunOrchestrator.ProviderModel route) {
    return equalsIgnoreCase(provider, route.provider()) && equalsIgnoreCase(model, route.model());
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    return left == null || right == null ? left == right : left.equalsIgnoreCase(right);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
