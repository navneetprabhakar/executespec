package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.entity.RunQuotaRefund;
import com.navneet.executespec.billing.repository.RunQuotaRefundRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunQuotaRefundProcessorTest {

  @Mock private RunQuotaRefundRepository repository;
  @Mock private TierEnforcementService tierEnforcementService;
  @Mock private UsageTrackingService usageTrackingService;

  private RunQuotaRefundProcessor processor;

  @BeforeEach
  void setUp() {
    processor =
        new RunQuotaRefundProcessor(repository, tierEnforcementService, usageTrackingService);
  }

  @Test
  void appliesBothCountersThenMarksIntentApplied() {
    RunQuotaRefund refund = pending();
    when(repository.findForUpdate("run-1")).thenReturn(Optional.of(refund));

    assertThat(processor.apply("run-1")).isTrue();

    verify(tierEnforcementService).decrementRunCount("user-1", 1);
    verify(usageTrackingService).decrement("user-1", "RUNS", 1);
    verify(repository).save(refund);
    assertThat(refund.getStatus()).isEqualTo("APPLIED");
    assertThat(refund.getAppliedAt()).isNotNull();
  }

  @Test
  void anAppliedIntentIsIdempotent() {
    RunQuotaRefund refund = pending();
    refund.setStatus("APPLIED");
    when(repository.findForUpdate("run-1")).thenReturn(Optional.of(refund));

    assertThat(processor.apply("run-1")).isFalse();

    verify(tierEnforcementService, never())
        .decrementRunCount(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    verify(usageTrackingService, never())
        .decrement(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void secondCounterFailureLeavesIntentPendingForTransactionalRetry() {
    RunQuotaRefund refund = pending();
    when(repository.findForUpdate("run-1")).thenReturn(Optional.of(refund));
    org.mockito.Mockito.doThrow(new IllegalStateException("billing unavailable"))
        .when(usageTrackingService)
        .decrement("user-1", "RUNS", 1);

    assertThatThrownBy(() -> processor.apply("run-1")).isInstanceOf(IllegalStateException.class);

    assertThat(refund.getStatus()).isEqualTo("PENDING");
    verify(repository, never()).save(refund);
  }

  private static RunQuotaRefund pending() {
    Instant now = Instant.now();
    return RunQuotaRefund.builder()
        .runId("run-1")
        .userId("user-1")
        .credits(1)
        .status("PENDING")
        .nextAttemptAt(now)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }
}
