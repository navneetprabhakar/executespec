package com.navneet.executespec.billing.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunQuotaRefundListenerTest {

  private final StateStore stateStore = mock(StateStore.class);
  private final TierEnforcementService tierEnforcementService = mock(TierEnforcementService.class);
  private final UsageTrackingService usageTrackingService = mock(UsageTrackingService.class);
  private final RunQuotaRefundListener listener =
      new RunQuotaRefundListener(stateStore, tierEnforcementService, usageTrackingService);

  @Test
  void failedRunRefundsOneDispatchChargeNotEstimatedCreditsCharged() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .status("FAILED")
            .createdByUserId("user-1")
            .creditsCharged(5)
            .build();
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

    listener.onRunFailed(new PipelineEvent.RunFailed("run-1", "failed"));

    verify(tierEnforcementService).decrementRunCount("user-1", 1);
    verify(usageTrackingService).decrement("user-1", "RUNS", 1);
    verify(tierEnforcementService, never()).decrementRunCount("user-1", 5);
  }

  @Test
  void dirtyPartialDoesNotRefund() {
    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .status("PARTIAL")
            .statusReason("FINAL_BUILD_FAILED")
            .createdByUserId("user-1")
            .creditsCharged(5)
            .build();
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

    listener.onRunFailed(new PipelineEvent.RunFailed("run-1", "partial"));

    verify(tierEnforcementService, never())
        .decrementRunCount(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    verify(usageTrackingService, never())
        .decrement(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt());
  }
}
