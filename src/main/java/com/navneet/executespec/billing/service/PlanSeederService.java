package com.navneet.executespec.billing.service;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.billing.client.RazorpayRestClient;
import com.navneet.executespec.billing.config.RazorpayConfig;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.repository.BillingPlanRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanSeederService {

  private final RazorpayRestClient razorpayClient;
  private final RazorpayConfig razorpayConfig;
  private final BillingPlanRepository planRepo;

  /**
   * Seeds Razorpay plan IDs for INR billing plans that don't have one yet. USD plans are skipped
   * (they need Stripe price IDs set separately). Idempotent: only creates Razorpay plans for rows
   * with null razorpay_plan_id.
   */
  @Transactional
  public void seedAllPlans() {
    // Log key mode (test vs live) for visibility
    String keyId = razorpayConfig.getKeyId();
    boolean isLive = keyId != null && keyId.startsWith("rzp_live_");
    log.info(
        "Razorpay plan seeder running in {} mode (key={}...)",
        isLive ? "LIVE" : "TEST",
        keyId != null && keyId.length() > 12 ? keyId.substring(0, 12) : keyId);

    List<BillingPlan> inrPlans =
        planRepo.findByActiveTrue().stream()
            .filter(p -> p.getBaseAmountInr() > 0 && p.getRazorpayPlanId() == null)
            .toList();

    if (inrPlans.isEmpty()) {
      log.info("All INR plans already have Razorpay plan IDs, skipping seed");
      return;
    }

    for (BillingPlan plan : inrPlans) {
      String rzpPeriod = "ANNUAL".equals(plan.getPeriod()) ? "yearly" : "monthly";
      int rzpInterval = 1;

      JsonNode rzpPlan =
          razorpayClient.createPlan(
              plan.getName(), (int) plan.getBaseAmountInr(), rzpPeriod, rzpInterval);
      String rzpPlanId = rzpPlan.get("id").asText();
      plan.setRazorpayPlanId(rzpPlanId);
      planRepo.save(plan);

      log.info("Seeded Razorpay plan: {} → razorpay_plan_id={}", plan.getName(), rzpPlanId);
    }

    // Remind about USD/Stripe plans
    long usdWithoutStripe =
        planRepo.findByActiveTrue().stream()
            .filter(p -> p.getBaseAmountUsd() > 0 && p.getStripePriceId() == null)
            .count();
    if (usdWithoutStripe > 0) {
      log.warn(
          "{} USD plan(s) still need stripe_price_id set (configure via Stripe Dashboard)",
          usdWithoutStripe);
    }

    // Log webhook URL reminder
    String webhookUrl = razorpayConfig.getWebhookUrl();
    if (webhookUrl != null && !webhookUrl.isBlank()) {
      log.info(
          "Razorpay webhook URL: {} — ensure this is configured in Razorpay Dashboard → Webhooks",
          webhookUrl);
      log.info(
          "Required events: subscription.charged, subscription.cancelled, subscription.halted,"
              + " payment.failed");
    } else {
      log.warn(
          "razorpay.webhook-url is not set — configure it and add to Razorpay Dashboard →"
              + " Webhooks");
    }

    log.info("Plan seeding complete");
  }
}
