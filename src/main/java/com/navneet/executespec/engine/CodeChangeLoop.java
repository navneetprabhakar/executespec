package com.navneet.executespec.engine;

import com.navneet.executespec.context.SavedContext;

/**
 * Provider-neutral inspect-edit-verify execution kernel shared by BUILD and PATCH orchestration.
 *
 * <p>This is deliberately narrower than either pipeline. Callers own workspace preparation,
 * planning, task acceptance, transactional generation, review, publication, and mode-specific
 * request policy. The loop owns one model/tool conversation against one prepared workspace.
 *
 * <p>Keeping this as an explicit port prevents BUILD from invoking PATCH orchestration as a black
 * box while still requiring both modes to use the same mutation engine.
 */
public interface CodeChangeLoop {

  /** Execute a fresh bounded code-change attempt. */
  WorkerResult run(WorkerTaskRequest request);

  /** Execute or resume a bounded attempt from a durable conversation snapshot. */
  WorkerResult run(WorkerTaskRequest request, SavedContext resumeFrom);

  /**
   * Read the durable cancellation fence before a caller enters another provider or task boundary.
   */
  boolean cancellationRequested(String runId);
}
