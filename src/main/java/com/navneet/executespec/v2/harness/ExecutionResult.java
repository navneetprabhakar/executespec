package com.navneet.executespec.v2.harness;

import lombok.Builder;

import java.util.List;

/**
 * Aggregate output of {@link SequentialExecutorV2#execute(ExecutionRequest)}.
 *
 * @param overall              run-level terminal state
 * @param tasksExecuted        count of tasks that actually ran (COMPLETED or COMPLETED_WITH_WARNING
 *                             or WORKER_FAILED). Excludes SKIPPED tasks.
 * @param tasksSuccessful      count with Status == SUCCESS or SUCCESS_AFTER_RETRY
 * @param tasksWithWarning     count with Status == COMPLETED_WITH_WARNING
 * @param tasksFailed          count with Status == WORKER_FAILED
 * @param tasksSkipped         count with Status == SKIPPED
 * @param perTask              per-task outcomes in plan order
 * @param totalLatencyMs       wall-clock for the full run (sum of all task latencies + overhead)
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
        long totalLatencyMs) {

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
