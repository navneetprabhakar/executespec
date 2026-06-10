package com.navneet.executespec.integration;

import java.time.Instant;
import java.util.concurrent.Future;

/**
 * Narrow seam exposing the irreducible run-orchestration handles that {@link RunLifecycleService}
 * needs from {@link RunOrchestrator}: the {@code activeRuns} cancel/halt map (invariant #4), the
 * resume entry into the (staying) {@code runPipeline}, and run termination.
 *
 * <p>Implemented by {@link RunOrchestrator}; injected into {@link RunLifecycleService}. The
 * dependency is <strong>one-directional</strong> (lifecycle → port → orchestrator) so there is no
 * bean cycle and no {@code @Lazy} — controllers call the lifecycle service directly, and the
 * orchestrator never injects it back. Extracted in the RunOrchestrator god-class split, increment 6
 * (audit #43).
 */
interface RunControlPort {

  /** Register the pipeline {@link Future} so cancel/pause/halt can interrupt it (invariant #4). */
  void putActiveRun(String runId, Future<?> future);

  /**
   * Remove + return the in-flight pipeline {@link Future} for {@code runId} ({@code null} if none).
   */
  Future<?> removeActiveRun(String runId);

  /** Resume entry into the canonical (staying) {@code runPipeline} 11-arg overload. */
  void runPipelineForResume(RunOrchestrator.RunPipelineRequest req);

  /** Mark a run terminal (persists status + status_reason, emits SSE, records metrics). */
  default void terminate(String runId, String finalStatus, String note, Instant startedAt) {
    terminate(runId, finalStatus, null, note, startedAt);
  }

  /** Mark a run terminal with an explicit status reason. */
  void terminate(
      String runId, String finalStatus, String statusReason, String note, Instant startedAt);
}
