package com.navneet.executespec.harness;

import com.navneet.executespec.engine.WorkerResult;
import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;

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
 * @param dispatchedProvider the provider REQUESTED for this task's worker call (e.g. "anthropic"),
 *     captured from {@code SequentialExecutor.tierConfigFor}. This is the tier-resolved route the
 *     executor asked for — NOT proof of the provider that served the call. Cross-provider failover
 *     and managed-AI tier locks rebuild the request after this value is captured, so the two can
 *     legitimately diverge. The route that actually served the task is projected from the {@code
 *     run_step_usage} ledger into {@code agent_task.effective_provider} (V305); read that, not this
 *     field, when you need physical route truth. Nullable for SKIPPED tasks.
 * @param dispatchedModel the model REQUESTED for this task's worker call (e.g. "claude-sonnet-4-6"
 *     for HIGH-complexity on anthropic), captured from {@code SequentialExecutor.tierConfigFor}.
 *     Same caveat as {@code dispatchedProvider}: requested, not served — see {@code
 *     agent_task.effective_model}. Recording this per task (rather than the run-level default)
 *     closed the b171fa52 finding where every task row was persisted as Haiku, masking the
 *     tier-correct workhorse/planning escalation.
 * @param goalDonenessDecision conservative goal-based continuation decision emitted at the terminal
 *     task boundary. Null for synthetic/resume-skipped outcomes.
 * @param continuationDecision bounded next-action advice derived from the goal decision and
 *     provider/model candidates. Null for synthetic/resume-skipped outcomes.
 * @param continuationAction decision that was automatically executed for this task. Null when the
 *     executor did not rerun the task through the continuation control plane.
 * @param continuationActionProvider provider selected by the executed continuation action.
 * @param continuationActionModel model selected by the executed continuation action.
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
    String dispatchedModel,
    GoalDonenessDecision goalDonenessDecision,
    GoalContinuationPolicyDecision continuationDecision,
    RunContinuationDecision continuationAction,
    String continuationActionProvider,
    String continuationActionModel) {

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
