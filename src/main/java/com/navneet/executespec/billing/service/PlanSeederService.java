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

    // Seed any active INR plan missing a Razorpay plan id — flat base-amount plans
    // (Pro/Solo) AND per-seat plans (TEAM: base_amount_inr = 0, per_seat_inr > 0).
    // For a per-seat plan the Razorpay plan carries the per-SEAT price; the
    // subscription is expected to set quantity = seats (see RazorpayGateway).
    List<BillingPlan> inrPlans =
        planRepo.findByActiveTrue().stream()
            .filter(p -> inrAmountPaise(p) > 0 && p.getRazorpayPlanId() == null)
            .toList();

    if (inrPlans.isEmpty()) {
      log.info("All INR plans already have Razorpay plan IDs, skipping seed");
      return;
    }

    for (BillingPlan plan : inrPlans) {
      String rzpPeriod = "ANNUAL".equals(plan.getPeriod()) ? "yearly" : "monthly";
      int rzpInterval = 1;

      int amountPaise = (int) inrAmountPaise(plan);
      JsonNode rzpPlan =
          razorpayClient.createPlan(plan.getName(), amountPaise, rzpPeriod, rzpInterval);
      String rzpPlanId = rzpPlan.get("id").asText();
      plan.setRazorpayPlanId(rzpPlanId);
      planRepo.save(plan);

      log.info(
          "Seeded Razorpay plan: {} ({} paise/unit) → razorpay_plan_id={}",
          plan.getName(),
          amountPaise,
          rzpPlanId);
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

  /**
   * INR amount in paise to charge for a plan: the flat base amount for tier plans, or the per-seat
   * amount for per-seat (TEAM) plans (where {@code base_amount_inr = 0}). The Razorpay plan carries
   * this per-unit price; the subscription multiplies it by the seat quantity.
   */
  private static long inrAmountPaise(BillingPlan p) {
    return p.getBaseAmountInr() > 0 ? p.getBaseAmountInr() : p.getPerSeatInr();
  }
}
