package com.navneet.executespec.harness;

import java.util.List;

import lombok.Builder;

/**
 * Aggregate output of {@link SequentialExecutor#execute(ExecutionRequest)}.
 *
 * @param overall run-level terminal state
 * @param tasksExecuted count of tasks that actually ran (COMPLETED or COMPLETED_WITH_WARNING or
 *     WORKER_FAILED). Excludes SKIPPED tasks.
 * @param tasksSuccessful count with Status == SUCCESS or SUCCESS_AFTER_RETRY
 * @param tasksWithWarning count with Status == COMPLETED_WITH_WARNING
 * @param tasksFailed count with Status == WORKER_FAILED
 * @param tasksSkipped count with Status == SKIPPED
 * @param perTask per-task outcomes in plan order
 * @param totalLatencyMs wall-clock for the full run (sum of all task latencies + overhead)
 */
@Builder
public record ExecutionResult(
    Overall overall,
    int tasksExecuted,
    int tasksSuccessful,
    int tasksWithWarning,
    int tasksFailed,
    int tasksSkipped,
    List<TaskOutcome> perTask,
    long totalLatencyMs,
    /**
     * v2.5.10 — status of the last non-skipped task. Used by RunOrchestrator to separate "final
     * task was clean" from "final task warned" when deriving the run-level status_reason. Nullable
     * for empty plans.
     */
    TaskOutcome.Status finalTaskStatus,
    /**
     * v2.5.10 — true when ANY task *other than the last non-skipped one* had
     * COMPLETED_WITH_WARNING. Lets RunOrchestrator tag a clean final as
     * CLEAN_WITH_INTERMEDIATE_WARNINGS vs CLEAN_COMPLETION.
     */
    boolean anyIntermediateWarning) {

  public enum Overall {
    /** Every task finished SUCCESS or SUCCESS_AFTER_RETRY — clean run. */
    ALL_SUCCESS,
    /** At least one COMPLETED_WITH_WARNING but no hard failures. Smoke tests were imperfect. */
    PARTIAL_WITH_WARNINGS,
    /** At least one WORKER_FAILED. Some tasks didn't produce code. */
    FAILED,
    /** Run was aborted mid-way (e.g. structural break — no further tasks attempted). */
    ABORTED
  }
}
