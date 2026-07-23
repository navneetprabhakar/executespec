package com.navneet.executespec.billing.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.billing.repository.RunQuotaRefundRepository;
import com.navneet.executespec.observability.Metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunQuotaRefundWorkerTest {

  @Mock private RunQuotaRefundRepository repository;
  @Mock private RunQuotaRefundProcessor processor;
  @Mock private RunQuotaRefundStore store;
  @Mock private Metrics metrics;

  private RunQuotaRefundWorker worker;

  @BeforeEach
  void setUp() {
    worker = new RunQuotaRefundWorker(repository, processor, store, metrics);
  }

  @Test
  void failedApplyIsRecordedAndRemainsAvailableForScheduledRetry() {
    IllegalStateException failure = new IllegalStateException("database unavailable");
    when(processor.apply("run-1")).thenThrow(failure);

    worker.attempt("run-1");

    verify(store).recordFailure("run-1", failure);
    verify(metrics).recordQuotaRefundAttempt("failed");
  }
}
