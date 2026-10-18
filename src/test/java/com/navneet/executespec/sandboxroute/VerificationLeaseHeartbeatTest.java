package com.navneet.executespec.sandboxroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class VerificationLeaseHeartbeatTest {
  @Test
  void isolatedClockSurvivesATickFailureAndStopsOnShutdown() throws Exception {
    var routing = mock(SandboxCommandRoutingService.class);
    var ticks = new CountDownLatch(2);
    var calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              ticks.countDown();
              if (calls.incrementAndGet() == 1)
                throw new IllegalStateException("synthetic DB outage");
              return null;
            })
        .when(routing)
        .renewVerificationSessions();
    var heartbeat = new VerificationLeaseHeartbeat(routing, 10);
    try {
      heartbeat.start();
      assertThat(ticks.await(3, TimeUnit.SECONDS)).isTrue();
    } finally {
      heartbeat.close();
    }
    clearInvocations(routing);
    verify(routing, after(50).never()).renewVerificationSessions();
  }
}
