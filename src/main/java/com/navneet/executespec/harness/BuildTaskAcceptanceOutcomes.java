package com.navneet.executespec.harness;

import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.slf4j.Logger;

/** Constructs and reports the normalized outcomes of server-owned BUILD acceptance. */
final class BuildTaskAcceptanceOutcomes {

  private BuildTaskAcceptanceOutcomes() {}

  static boolean failed(TaskOutcome outcome) {
    if (outcome == null) return true;
    return switch (outcome.status()) {
      case SUCCESS, SUCCESS_AFTER_RETRY -> false;
      case COMPLETED_WITH_WARNING, WORKER_FAILED, SKIPPED -> true;
    };
  }

  static TaskOutcome accepted(PlannedTask task, TaskOutcome source) {
    if (source != null
        && (source.status() == TaskOutcome.Status.SUCCESS
            || source.status() == TaskOutcome.Status.SUCCESS_AFTER_RETRY)
        && Boolean.TRUE.equals(source.smokeTestPassed())) return source;
    boolean retried = source != null && source.retryAttempted();
    return TaskOutcome.builder()
        .taskId(source == null ? task.id() : source.taskId())
        .taskTitle(source == null ? task.title() : source.taskTitle())
        .status(retried ? TaskOutcome.Status.SUCCESS_AFTER_RETRY : TaskOutcome.Status.SUCCESS)
        .workerOutcome(source == null ? null : source.workerOutcome())
        .retryAttempted(retried)
        .retryWorkerOutcome(source == null ? null : source.retryWorkerOutcome())
        .smokeTestPassed(true)
        .smokeTestExitCode(0)
        .smokeTestOutput(source == null ? null : source.smokeTestOutput())
        .totalLatencyMs(source == null ? 0 : source.totalLatencyMs())
        .toolCallsDispatched(source == null ? 0 : source.toolCallsDispatched())
        .dispatchedProvider(source == null ? null : source.dispatchedProvider())
        .dispatchedModel(source == null ? null : source.dispatchedModel())
        .build();
  }

  static TaskOutcome rejected(TaskOutcome outcome, String reason) {
    return TaskOutcome.builder()
        .taskId(outcome.taskId())
        .taskTitle(outcome.taskTitle())
        .status(TaskOutcome.Status.COMPLETED_WITH_WARNING)
        .workerOutcome(outcome.workerOutcome())
        .retryAttempted(outcome.retryAttempted())
        .retryWorkerOutcome(outcome.retryWorkerOutcome())
        .smokeTestPassed(false)
        .smokeTestExitCode(outcome.smokeTestExitCode())
        .smokeTestOutput(
            (outcome.smokeTestOutput() == null ? "" : outcome.smokeTestOutput() + "\n") + reason)
        .totalLatencyMs(outcome.totalLatencyMs())
        .toolCallsDispatched(outcome.toolCallsDispatched())
        .dispatchedProvider(outcome.dispatchedProvider())
        .dispatchedModel(outcome.dispatchedModel())
        .goalDonenessDecision(outcome.goalDonenessDecision())
        .continuationDecision(outcome.continuationDecision())
        .continuationAction(outcome.continuationAction())
        .continuationActionProvider(outcome.continuationActionProvider())
        .continuationActionModel(outcome.continuationActionModel())
        .build();
  }

  static boolean emitStop(
      TaskOutcome outcome,
      String runId,
      PlannedTask task,
      int remaining,
      SseBroadcaster broadcaster,
      Logger log) {
    if (!failed(outcome)) return false;
    log.warn(
        "BUILD acceptance stop run={} task={} status={} smokePassed={} remaining={}",
        runId,
        task.id(),
        outcome.status(),
        outcome.smokeTestPassed(),
        remaining);
    String failure =
        "Task acceptance failed: "
            + outcome.status()
            + (Boolean.FALSE.equals(outcome.smokeTestPassed())
                ? " (deterministic verification failed)"
                : " (completion was not proven)");
    broadcaster.emit(
        runId,
        RunEvent.log(
            runId,
            task.id(),
            remaining > 0
                ? failure
                    + "; skipping "
                    + remaining
                    + " dependent task"
                    + (remaining == 1 ? "" : "s")
                : failure + "; execution cannot enter review as accepted"));
    return true;
  }
}
