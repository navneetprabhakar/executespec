package com.navneet.executespec.harness;

import java.util.concurrent.CancellationException;

import com.navneet.executespec.engine.WorkerResult;

/** Preserves crash/cancellation recovery state when a provider call is interrupted. */
final class WorkerInterruptionPolicy {

  private WorkerInterruptionPolicy() {}

  /**
   * WorkerAgent preserves the thread interrupt flag whether it observes cancellation at an
   * iteration boundary ({@link WorkerResult.Outcome#STOPPED_WITHOUT_COMPLETE}) or through a
   * provider exception ({@link WorkerResult.Outcome#ERROR}). Neither result is eligible for
   * write-based soft completion: the in-flight task must remain non-terminal for fenced recovery.
   */
  static void throwIfInterruptedError(WorkerResult result, String taskId) {
    if (result != null
        && (result.outcome() == WorkerResult.Outcome.ERROR
            || result.outcome() == WorkerResult.Outcome.STOPPED_WITHOUT_COMPLETE)
        && Thread.currentThread().isInterrupted()) {
      throw new CancellationException("worker execution interrupted for task " + taskId);
    }
  }
}
