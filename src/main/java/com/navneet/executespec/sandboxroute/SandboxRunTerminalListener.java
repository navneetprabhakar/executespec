package com.navneet.executespec.sandboxroute;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.integration.BoundedTestGenKickoff;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Connects the pipeline's terminal lifecycle to sandbox teardown.
 *
 * <p>The event path is low-latency. The database sweep is the durable fallback for a lost event,
 * process restart, PARTIAL completion, and direct cancellation (which persists terminal status
 * without publishing a pipeline event). Both paths are idempotent and remain active while sandbox
 * feature flags are off.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SandboxRunTerminalListener {

  private final SandboxCommandRoutingService routing;
  private final BoundedTestGenKickoff testGenKickoff;
  private final SandboxTerminalCleanupExecutor cleanupExecutor;

  @EventListener
  public void onRunCompleted(PipelineEvent.RunCompleted event) {
    cleanupExecutor.submit(event.runId(), () -> release(event.runId()));
  }

  @EventListener
  public void onRunFailed(PipelineEvent.RunFailed event) {
    cleanupExecutor.submit(event.runId(), () -> release(event.runId()));
  }

  @Scheduled(fixedDelayString = "${executespec.sandbox.terminal-sweep-interval-ms:5000}")
  public void releaseMissedTerminalRuns() {
    try {
      int released = routing.releaseTerminalRuns(50, testGenKickoff::isActive);
      if (released > 0) {
        log.info("released {} sandboxes belonging to terminal runs", released);
      }
    } catch (RuntimeException failure) {
      log.error("terminal sandbox sweep failed", failure);
    }
  }

  private void release(String runId) {
    if (testGenKickoff.isActive(runId)) {
      log.debug(
          "retaining sandbox for terminal run {} while bounded test generation is active", runId);
      return;
    }
    try {
      routing.releaseForTerminalRun(runId);
    } catch (RuntimeException failure) {
      // The scheduled durable sweep retries. A listener failure must not alter the already-terminal
      // pipeline run or suppress unrelated completion listeners.
      log.error("sandbox teardown failed for terminal run {}", runId, failure);
    }
  }
}
