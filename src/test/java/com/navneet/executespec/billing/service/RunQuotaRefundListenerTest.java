package com.navneet.executespec.billing.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class RunQuotaRefundListenerTest {

  private final StateStore stateStore = mock(StateStore.class);
  private final RunQuotaRefundStore refundStore = mock(RunQuotaRefundStore.class);
  private final RunQuotaRefundWorker refundWorker = mock(RunQuotaRefundWorker.class);
  private final RunQuotaRefundListener listener =
      new RunQuotaRefundListener(stateStore, refundStore, refundWorker);

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

    verify(refundStore).enqueue("run-1", "user-1", 1);
    verify(refundWorker).attempt("run-1");
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

    verify(refundStore, never())
        .enqueue(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt());
    verify(refundWorker, never()).attempt(org.mockito.ArgumentMatchers.anyString());
  }
}
