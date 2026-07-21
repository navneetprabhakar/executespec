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

  /**
   * Legacy overload — persists the plan's minimum seat count. Retained only for callers that
   * genuinely have no per-checkout quantity; every checkout path must use the {@code
   * requestedSeats} overload below.
   */
  public BillingSubscription createPending(
      String userId,
      String orgId,
      BillingPlan plan,
      String paymentGateway,
      String gatewaySubscriptionId,
      String gatewayCustomerId,
      boolean isFounder) {
    return createPending(
        userId,
        orgId,
        plan,
        paymentGateway,
        gatewaySubscriptionId,
        gatewayCustomerId,
        isFounder,
        plan.getIncludedSeats());
  }

  /**
   * RR-419: persist the seat quantity that was actually validated and sent to the gateway.
   *
   * <p>The Razorpay checkout path charges {@code effectiveSeats} (the Razorpay plan carries the
   * per-seat price and the subscription sets {@code quantity = seats}), but the pending row used to
   * record only {@code plan.includedSeats}. A TEAM customer could therefore pay for 3–20 seats and
   * keep the 2-seat plan minimum in ExecuteSpec, corrupting seat access and every downstream
   * lifecycle calculation. Stripe's callback restores the charged quantity from the session;
   * Razorpay had no such reconciliation, so the checkout-requested quantity must be durable from
   * the start.
   *
   * <p>{@code requestedSeats} is clamped up to the plan minimum — never below it — so a malformed
   * request cannot under-provision a paid plan.
   */
  public BillingSubscription createPending(
      String userId,
      String orgId,
      BillingPlan plan,
      String paymentGateway,
      String gatewaySubscriptionId,
      String gatewayCustomerId,
      boolean isFounder,
      int requestedSeats) {
    int includedSeats = plan.getIncludedSeats();
    int totalSeats = Math.max(includedSeats, requestedSeats);
    BillingSubscription sub =
        BillingSubscription.builder()
            .userId(userId)
            .orgId(orgId)
            .billingPlanId(plan.getId())
            .paymentGateway(paymentGateway)
            .gatewaySubscriptionId(gatewaySubscriptionId)
            .gatewayCustomerId(gatewayCustomerId)
            .status("CREATED")
            .totalSeats(totalSeats)
            .extraSeats(Math.max(0, totalSeats - includedSeats))
            .cancelAtPeriodEnd(false)
            .termsAcceptedAt(java.time.Instant.now())
            .termsVersion(currentTermsVersion)
            .founderPricing(isFounder)
            .build();
    return subRepo.save(sub);
  }
}
