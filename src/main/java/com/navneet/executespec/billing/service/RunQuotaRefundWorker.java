package com.navneet.executespec.billing.service;

import java.time.Instant;

import com.navneet.executespec.billing.repository.RunQuotaRefundRepository;
import com.navneet.executespec.observability.Metrics;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class RunQuotaRefundWorker {

  private static final int BATCH_SIZE = 50;

  private final RunQuotaRefundRepository repository;
  private final RunQuotaRefundProcessor processor;
  private final RunQuotaRefundStore store;
  private final Metrics metrics;

  @Scheduled(fixedDelayString = "${billing.run-refunds.poll-interval-millis:30000}")
  public void poll() {
    metrics.recordQuotaRefundBacklog(
        repository.countByStatusAndCreatedAtBefore("PENDING", Instant.now().minusSeconds(300)));
    for (String runId :
        repository.findPendingRunIds(Instant.now(), PageRequest.of(0, BATCH_SIZE))) {
      attempt(runId);
    }
  }

  public void attempt(String runId) {
    try {
      if (processor.apply(runId)) {
        metrics.recordQuotaRefundAttempt("applied");
        log.info("Applied durable quota refund for run {}", runId);
      }
    } catch (RuntimeException failure) {
      metrics.recordQuotaRefundAttempt("failed");
      store.recordFailure(runId, failure);
      log.error(
          "Durable quota refund remains pending run={} errorType={}",
          runId,
          failure.getClass().getSimpleName());
    }
  }
}
