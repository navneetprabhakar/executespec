package com.navneet.executespec.billing.service;

import java.time.Instant;

import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.repository.RunQuotaRefundRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RunQuotaRefundProcessor {

  private final RunQuotaRefundRepository repository;
  private final TierEnforcementService tierEnforcementService;
  private final UsageTrackingService usageTrackingService;

  /** Applies both quota counters atomically. The locked ledger row fences concurrent JVMs. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean apply(String runId) {
    var refund = repository.findForUpdate(runId).orElse(null);
    if (refund == null || "APPLIED".equals(refund.getStatus())) return false;

    tierEnforcementService.decrementRunCount(refund.getUserId(), refund.getCredits());
    usageTrackingService.decrement(refund.getUserId(), "RUNS", refund.getCredits());

    Instant now = Instant.now();
    refund.setStatus("APPLIED");
    refund.setAppliedAt(now);
    refund.setUpdatedAt(now);
    refund.setLastError(null);
    repository.save(refund);
    return true;
  }
}
