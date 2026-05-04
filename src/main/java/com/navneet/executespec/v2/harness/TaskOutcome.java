package com.navneet.executespec.v2.harness;

import com.navneet.executespec.v2.agent.WorkerResult;

import lombok.Builder;

/**
 * Terminal state of a single task executed by {@link SequentialExecutor}.
 *
 * @param taskId plan task ID (e.g. "T1")
 * @param taskTitle task title for display
 * @param status terminal status of the task
 * @param workerOutcome the raw worker outcome from the primary attempt
 * @param retryAttempted true if a smoke-fail retry was triggered
 * @param retryWorkerOutcome the raw worker outcome from the retry (null if no retry)
 * @param smokeTestPassed final smoke test result (after retry if applicable). null means smoke test
 *     didn't run (e.g. worker errored out).
 * @param smokeTestExitCode exit code of the final smoke test invocation
 * @param smokeTestOutput truncated smoke test stdout/stderr (head+tail)
 * @param totalLatencyMs wall-clock for the full task execution (primary + retry + smoke)
 * @param toolCallsDispatched sum across primary + retry worker invocations
 * @param dispatchedProvider the provider actually used for this task's worker call (e.g.
 *     "anthropic"). Captured from {@code SequentialExecutor.tierConfigFor} so per-task DB rows
 *     reflect the tier-resolved provider, not the run-level default. Nullable for SKIPPED tasks.
 * @param dispatchedModel the model actually used for this task's worker call (e.g.
 *     "claude-sonnet-4-6" for HIGH-complexity on anthropic). Captured from {@code
 *     SequentialExecutor.tierConfigFor}. Closes the b171fa52 finding where every task row was
 *     persisted as Haiku because {@code RunTokenAttributor.attributeWorker} wrote the run-level
 *     default into every row, masking the tier-correct workhouse/planning escalation.
 */
@Builder
public record TaskOutcome(
    String taskId,
    String taskTitle,
    Status status,
    WorkerResult workerOutcome,
    boolean retryAttempted,
    WorkerResult retryWorkerOutcome,
    Boolean smokeTestPassed,
    Integer smokeTestExitCode,
    String smokeTestOutput,
    long totalLatencyMs,
    int toolCallsDispatched,
    String dispatchedProvider,
    String dispatchedModel) {

  public enum Status {
    /** Worker completed AND smoke test passed. */
    SUCCESS,
    /** Worker completed AND smoke test passed AFTER a retry. */
    SUCCESS_AFTER_RETRY,
    /** Worker completed but smoke test failed (retry also failed or wasn't attempted). */
    COMPLETED_WITH_WARNING,
    /** Worker did not complete (STOPPED_WITHOUT_COMPLETE / ITERATION_LIMIT / ERROR). */
    WORKER_FAILED,
    /** Executor aborted the run before reaching this task. */
    SKIPPED
  }
}
