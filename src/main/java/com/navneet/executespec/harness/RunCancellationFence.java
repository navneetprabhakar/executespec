package com.navneet.executespec.harness;

import java.util.concurrent.CancellationException;

import com.navneet.executespec.engine.CodeChangeLoop;

/** Separates durable run cancellation from an unowned provider/thread interruption. */
final class RunCancellationFence {

  private RunCancellationFence() {}

  static boolean requested(CodeChangeLoop worker, String runId) {
    return Thread.currentThread().isInterrupted()
        || (worker != null && worker.cancellationRequested(runId));
  }

  static void throwIfRequested(CodeChangeLoop worker, String runId, String taskId) {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException(
          "thread interrupted before task/provider dispatch: " + taskId);
    }
    if (worker == null || !worker.cancellationRequested(runId)) return;
    Thread.currentThread().interrupt();
    throw new DurableRunCancellationException(
        "run cancelled before task/provider dispatch: " + taskId);
  }
}
