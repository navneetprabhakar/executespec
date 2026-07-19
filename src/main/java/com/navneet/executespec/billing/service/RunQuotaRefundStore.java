package com.navneet.executespec.billing.service;

import java.time.Instant;

import com.navneet.executespec.billing.repository.RunQuotaRefundRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RunQuotaRefundStore {

  private final RunQuotaRefundRepository repository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean enqueue(String runId, String userId, int credits) {
    return repository.insertIfAbsent(runId, userId, credits) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(String runId, RuntimeException failure) {
    Instant now = Instant.now();
    repository.recordFailure(runId, failure.getClass().getSimpleName(), now, now.plusSeconds(60));
  }
}
