package com.navneet.executespec.integration;

import com.navneet.executespec.harness.ExecutionResult;
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
      return new Result("FAILED", "EXECUTION_ABORTED");
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

  record Result(String status, String reason) {}
}
