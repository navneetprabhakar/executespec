package com.navneet.executespec.billing.service;

import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BillingSubscriptionWriter {

  private final BillingSubscriptionRepository subRepo;

  @Value("${billing.terms-version:2026-03-v1}")
  private String currentTermsVersion = "2026-03-v1";

  public BillingSubscriptionWriter(BillingSubscriptionRepository subRepo) {
    this.subRepo = subRepo;
  }

  void setCurrentTermsVersionForTest(String currentTermsVersion) {
    this.currentTermsVersion = currentTermsVersion;
  }

  public BillingSubscription createPending(
      String userId,
      String orgId,
      BillingPlan plan,
      String paymentGateway,
      String gatewaySubscriptionId,
      String gatewayCustomerId,
      boolean isFounder) {
    BillingSubscription sub =
        BillingSubscription.builder()
            .userId(userId)
            .orgId(orgId)
            .billingPlanId(plan.getId())
            .paymentGateway(paymentGateway)
            .gatewaySubscriptionId(gatewaySubscriptionId)
            .gatewayCustomerId(gatewayCustomerId)
            .status("CREATED")
            .totalSeats(plan.getIncludedSeats())
            .extraSeats(0)
            .cancelAtPeriodEnd(false)
            .termsAcceptedAt(java.time.Instant.now())
            .termsVersion(currentTermsVersion)
            .founderPricing(isFounder)
            .build();
    return subRepo.save(sub);
  }
}
