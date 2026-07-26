package com.navneet.executespec.sandboxroute;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.integration.BoundedTestGenKickoff;

import org.junit.jupiter.api.Test;

class SandboxRunTerminalListenerTest {

  @Test
  void completionAndFailureEventsBothRequestIdempotentTeardown() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    BoundedTestGenKickoff testGen = mock(BoundedTestGenKickoff.class);
    SandboxTerminalCleanupExecutor cleanup = immediateCleanupExecutor();
    SandboxRunTerminalListener listener = new SandboxRunTerminalListener(routing, testGen, cleanup);

    listener.onRunCompleted(new PipelineEvent.RunCompleted("complete-run"));
    listener.onRunFailed(new PipelineEvent.RunFailed("failed-run", "failed"));

    verify(routing).releaseForTerminalRun("complete-run");
    verify(routing).releaseForTerminalRun("failed-run");
  }

  @Test
  void scheduledSweepReleasesMissedTerminalRuns() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.releaseTerminalRuns(eq(50), any())).thenReturn(2);
    BoundedTestGenKickoff testGen = mock(BoundedTestGenKickoff.class);
    SandboxTerminalCleanupExecutor cleanup = mock(SandboxTerminalCleanupExecutor.class);
    SandboxRunTerminalListener listener = new SandboxRunTerminalListener(routing, testGen, cleanup);

    listener.releaseMissedTerminalRuns();

    verify(routing).releaseTerminalRuns(eq(50), any());
  }

  @Test
  void terminalEventRetainsSandboxWhileBoundedTestGenerationCanStillDispatch() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    BoundedTestGenKickoff testGen = mock(BoundedTestGenKickoff.class);
    when(testGen.isActive("run-3")).thenReturn(true);
    SandboxTerminalCleanupExecutor cleanup = immediateCleanupExecutor();
    SandboxRunTerminalListener listener = new SandboxRunTerminalListener(routing, testGen, cleanup);

    listener.onRunCompleted(new PipelineEvent.RunCompleted("run-3"));

    verify(routing, never()).releaseForTerminalRun("run-3");
  }

  private static SandboxTerminalCleanupExecutor immediateCleanupExecutor() {
    SandboxTerminalCleanupExecutor cleanup = mock(SandboxTerminalCleanupExecutor.class);
    doAnswer(
            invocation -> {
              invocation.getArgument(1, Runnable.class).run();
              return true;
            })
        .when(cleanup)
        .submit(any(), any());
    return cleanup;
  }
}
