package com.navneet.executespec.harness;

import com.navneet.executespec.engine.WorkerResult;

/**
 * Turns a non-COMPLETED worker outcome into the short failure reason persisted on the task row and
 * rendered on the run detail page.
 *
 * <p>Split out of {@link SequentialExecutor}, which owns task orchestration. This class owns one
 * responsibility, and it is a containment boundary rather than a formatting convenience: the string
 * it returns is customer-visible and durable (see {@code
 * V184__widen_agent_task_last_llm_failure.sql}, which sizes the column against these messages), so
 * it must carry diagnostic metadata — outcome, iteration count, tool-call count — and must NEVER
 * carry model text, tool payloads or raw provider responses.
 */
final class WorkerFailureDescriber {

  private WorkerFailureDescriber() {}

  /** v2.5.12 — maps a non-COMPLETED worker outcome to a human-readable failure reason. */
  static String describe(WorkerResult r) {
    if (r == null || r.outcome() == null) return "Worker outcome unknown";
    return switch (r.outcome()) {
      case STOPPED_WITHOUT_COMPLETE ->
          "Worker stopped without calling completeTask after " + r.iterations() + " iterations";
      case ITERATION_LIMIT_EXCEEDED ->
          "Iteration cap exceeded ("
              + r.iterations()
              + " iters, "
              + r.toolCallsDispatched()
              + " tool calls). Worker did not call completeTask.";
      case WALL_CLOCK_EXCEEDED ->
          "Wall-clock cap exceeded ("
              + r.iterations()
              + " iters, "
              + r.toolCallsDispatched()
              + " tool calls).";
      case ERROR -> "Worker threw an unrecoverable exception; inspect the correlation trace.";
      case BUDGET_EXCEEDED -> "Run hit its hard cost cap; worker stopped cleanly mid-task.";
      default -> "Worker outcome=" + r.outcome();
    };
  }
}
