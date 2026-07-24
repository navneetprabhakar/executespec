package com.navneet.executespec.harness;

import java.util.concurrent.CancellationException;

import com.navneet.executespec.engine.WorkerResult;

/** Preserves crash/cancellation recovery state when a provider call is interrupted. */
final class WorkerInterruptionPolicy {

  private WorkerInterruptionPolicy() {}

  /**
   * LlmRouter restores the interrupt flag before WorkerAgent converts the provider exception to
   * {@link WorkerResult.Outcome#ERROR}. That result is not eligible for write-based soft
   * completion: the in-flight task must remain non-terminal for cancellation or fenced startup
   * recovery.
   */
  static void throwIfInterruptedError(WorkerResult result, String taskId) {
    if (result != null
        && result.outcome() == WorkerResult.Outcome.ERROR
        && Thread.currentThread().isInterrupted()) {
      throw new CancellationException("worker execution interrupted for task " + taskId);
    }
  }
}
