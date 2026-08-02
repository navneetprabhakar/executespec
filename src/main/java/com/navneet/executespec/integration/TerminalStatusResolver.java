package com.navneet.executespec.integration;

import com.navneet.executespec.harness.ExecutionResult;
import com.navneet.executespec.harness.TaskOutcome;
import com.navneet.executespec.reviewer.ReviewerResult;

/**
 * Pure run-terminal classification.
 *
 * <p>The orchestrator owns persistence and lifecycle side effects; this class owns only the
 * deterministic mapping from execution/reviewer evidence to the customer-visible terminal status
 * and reason. Keeping the decision here makes the precedence order testable without constructing
 * the orchestration graph.
 */
final class TerminalStatusResolver {

  private TerminalStatusResolver() {}

  static Result resolve(ExecutionResult exec, ReviewerResult review) {
    if (exec.overall() == ExecutionResult.Overall.ABORTED) {
      return new Result("FAILED", abortedReason(exec));
    }
    ReviewerResult.Overall verdict = review != null ? review.overall() : null;
    boolean buildPassed = review != null && Boolean.TRUE.equals(review.finalBuildPassed());
    boolean anyWarn = exec.anyIntermediateWarning();
    boolean anyTaskSucceeded = exec.tasksSuccessful() > 0;

    // A clean reviewer/build cannot erase an unrecoverable or unverified final worker outcome.
    // Review proves the resulting tree builds; it does not prove the worker completed its task or
    // that its tests passed.
    if (exec.finalTaskStatus()
        == com.navneet.executespec.harness.TaskOutcome.Status.COMPLETED_WITH_WARNING) {
      return new Result("PARTIAL", "FINAL_TASK_WARNING");
    }

    if (buildPassed
        && verdict != null
        && (verdict == ReviewerResult.Overall.CLEAN
            || verdict == ReviewerResult.Overall.FIXED_ISSUES)) {
      return new Result(
          "COMPLETE", anyWarn ? "CLEAN_WITH_INTERMEDIATE_WARNINGS" : "CLEAN_COMPLETION");
    }
    if (verdict == ReviewerResult.Overall.REVIEW_FAILED) {
      return new Result("PARTIAL", "REVIEWER_FAILED");
    }
    if (review != null && !buildPassed) {
      return new Result("PARTIAL", "FINAL_BUILD_FAILED");
    }
    if (verdict == ReviewerResult.Overall.UNRESOLVED_ISSUES) {
      return new Result("PARTIAL", "REVIEWER_UNRESOLVED");
    }
    if (!anyTaskSucceeded && exec.tasksFailed() > 0) {
      return new Result("FAILED", "ALL_TASKS_FAILED");
    }

    // No reviewer evidence is a known state, not the historical LEGACY_UNKNOWN fallback.
    return new Result(RunPipelineSupport.mapOverallToV1Status(exec.overall()), "NO_REVIEW_RESULT");
  }

  private static String abortedReason(ExecutionResult exec) {
    if (exec.perTask() == null) return "EXECUTION_ABORTED";
    for (int index = exec.perTask().size() - 1; index >= 0; index--) {
      TaskOutcome task = exec.perTask().get(index);
      if (task == null || task.status() == TaskOutcome.Status.SKIPPED) continue;
      var worker =
          task.retryWorkerOutcome() != null ? task.retryWorkerOutcome() : task.workerOutcome();
      if (worker == null || worker.outcome() == null) return "EXECUTION_ABORTED";
      return switch (worker.outcome()) {
        case BUDGET_EXCEEDED -> "WORKER_BUDGET_EXCEEDED";
        case ITERATION_LIMIT_EXCEEDED -> "WORKER_ITERATION_LIMIT_EXCEEDED";
        case WALL_CLOCK_EXCEEDED -> "WORKER_WALL_CLOCK_EXCEEDED";
        case ABSOLUTE_DEADLINE_EXCEEDED -> "WORKER_ABSOLUTE_DEADLINE_EXCEEDED";
        default -> "EXECUTION_ABORTED";
      };
    }
    return "EXECUTION_ABORTED";
  }

  record Result(String status, String reason) {}
}
