package com.navneet.executespec.harness;

import java.util.List;

/** Builds the run-level result from persisted per-task outcomes. */
final class ExecutionResultSummarizer {

  private ExecutionResultSummarizer() {}

  static ExecutionResult summarize(
      List<TaskOutcome> outcomes,
      long totalLatency,
      boolean abortedByGoalDecision,
      boolean cancelled) {
    int success = 0;
    int warning = 0;
    int failure = 0;
    int skipped = 0;
    for (TaskOutcome outcome : outcomes) {
      switch (outcome.status()) {
        case SUCCESS, SUCCESS_AFTER_RETRY -> success++;
        case COMPLETED_WITH_WARNING -> warning++;
        case WORKER_FAILED -> failure++;
        case SKIPPED -> skipped++;
      }
    }

    ExecutionResult.Overall overall;
    if (cancelled || abortedByGoalDecision) overall = ExecutionResult.Overall.ABORTED;
    else if (failure > 0) overall = ExecutionResult.Overall.FAILED;
    else if (warning > 0) overall = ExecutionResult.Overall.PARTIAL_WITH_WARNINGS;
    else overall = ExecutionResult.Overall.ALL_SUCCESS;

    TaskOutcome lastNonSkipped = null;
    for (int index = outcomes.size() - 1; index >= 0; index--) {
      if (outcomes.get(index).status() != TaskOutcome.Status.SKIPPED) {
        lastNonSkipped = outcomes.get(index);
        break;
      }
    }
    boolean intermediateWarning = false;
    if (lastNonSkipped != null) {
      for (TaskOutcome outcome : outcomes) {
        if (outcome != lastNonSkipped
            && outcome.status() == TaskOutcome.Status.COMPLETED_WITH_WARNING) {
          intermediateWarning = true;
          break;
        }
      }
    }

    return ExecutionResult.builder()
        .overall(overall)
        .tasksExecuted(outcomes.size() - skipped)
        .tasksSuccessful(success)
        .tasksWithWarning(warning)
        .tasksFailed(failure)
        .tasksSkipped(skipped)
        .perTask(outcomes)
        .totalLatencyMs(totalLatency)
        .finalTaskStatus(lastNonSkipped != null ? lastNonSkipped.status() : null)
        .anyIntermediateWarning(intermediateWarning)
        .build();
  }
}
