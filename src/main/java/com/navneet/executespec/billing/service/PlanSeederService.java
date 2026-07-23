package com.navneet.executespec.billing.service;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.billing.client.RazorpayRestClient;
import com.navneet.executespec.billing.config.RazorpayConfig;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.repository.BillingPlanRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanSeederService {

  private final RazorpayRestClient razorpayClient;
  private final RazorpayConfig razorpayConfig;
  private final BillingPlanRepository planRepo;
  private final TransactionTemplate transactionTemplate;

  /**
   * Seeds Razorpay plan IDs for INR billing plans that don't have one yet. USD plans are skipped
   * (they need Stripe price IDs set separately). Idempotent: only creates Razorpay plans for rows
   * with null razorpay_plan_id.
   *
   * <p><b>RR-400 (billing idempotency).</b> This method used to be a single {@code @Transactional}
   * unit that looped through external Razorpay {@code createPlan} calls before saving any local id.
   * A failure on plan N rolled back the ids saved for plans 1..N-1 while the corresponding Razorpay
   * plans — which cannot roll back — stayed created. The next retry saw null ids and created them
   * all over again, so one partial failure orphaned and then repeatedly duplicated chargeable
   * gateway plans, leaving the local-to-gateway pricing mapping ambiguous. The admin endpoint
   * exposes this operation directly, so it is retried by hand.
   *
   * <p>The outer method is now non-transactional and each plan is committed on its own in {@link
   * #persistSeededPlanId} with {@code REQUIRES_NEW} <i>immediately</i> after its gateway call
   * returns. A failure therefore loses at most the one plan it was working on, and a retry only
   * creates the plans that genuinely have no id. Per-plan failures no longer abort the rest of the
   * sweep; the outcome is returned so the caller can surface partial state.
   *
   * <p>The remaining hole — a crash BETWEEN {@code createPlan} returning and {@link
   * #persistSeededPlanId} committing — left the Razorpay plan created but no local id saved, so a
   * retry (seeing a null id) created a SECOND chargeable plan. Each {@code createPlan} now carries
   * a deterministic idempotency key derived from the plan's local UUID ({@link #seedingKey}), so a
   * retry reuses the same key and Razorpay returns the ORIGINAL plan instead of duplicating it. The
   * key is committed to nothing local first because it does not need to be: it is reconstructable
   * from the plan row that already exists, so it is stable across process restarts. (Razorpay's
   * idempotency window is finite; the key protects the realistic crash-and-retry window rather than
   * an indefinite one.)
   */
  public SeedResult seedAllPlans() {
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
      return new SeedResult(0, 0, List.of());
    }

    int seeded = 0;
    int failed = 0;
    List<String> errors = new ArrayList<>();

    for (BillingPlan plan : inrPlans) {
      String rzpPeriod = annualPeriod(plan.getPeriod()) ? "yearly" : "monthly";
      int rzpInterval = 1;
      int amountPaise = (int) inrAmountPaise(plan);
      try {
        JsonNode rzpPlan =
            razorpayClient.createPlan(
                plan.getName(), amountPaise, rzpPeriod, rzpInterval, seedingKey(plan.getId()));
        String rzpPlanId = rzpPlan.get("id").asText();

        // Commit this id on its own, immediately. An error on a LATER plan must never roll
        // back a Razorpay plan that already exists upstream.
        persistSeededPlanId(plan.getId(), rzpPlanId);
        seeded++;

        log.info(
            "Seeded Razorpay plan: {} ({} paise/unit) → razorpay_plan_id={}",
            plan.getName(),
            amountPaise,
            rzpPlanId);
      } catch (Exception e) {
        failed++;
        errors.add(plan.getName() + ": " + e.getMessage());
        log.error(
            "Razorpay plan seeding failed for plan={} ({} paise/unit) — continuing with the"
                + " remaining plans: {}",
            plan.getName(),
            amountPaise,
            e.getMessage(),
            e);
      }
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

    log.info("Plan seeding complete: seeded={} failed={}", seeded, failed);
    return new SeedResult(seeded, failed, List.copyOf(errors));
  }

  /** Outcome of one seeding sweep. Partial success is a real, reportable state. */
  public record SeedResult(int seeded, int failed, List<String> errors) {}

  /**
   * RR-400 — the deterministic Razorpay idempotency key for seeding one local plan. It is exactly
   * the plan's immutable local UUID, so the first attempt and every retry send the same key and
   * Razorpay dedupes to the original plan instead of creating a duplicate. A UUID is globally
   * unique (never colliding with any other idempotency key this account uses) and comfortably
   * within gateway key-length limits, so no prefix or truncation is needed.
   */
  static String seedingKey(java.util.UUID planId) {
    return planId.toString();
  }

  /**
   * Persist one plan's Razorpay id in its own committed transaction, driven by an explicit {@link
   * TransactionTemplate} rather than a self-invoked {@code @Transactional} method (self-calls
   * bypass the Spring proxy and would silently give no transaction at all).
   *
   * <p>Re-reads the row so a concurrent seeding sweep that already claimed this plan is not
   * overwritten — the first committed id wins and the duplicate is logged rather than silently
   * replacing a live gateway mapping.
   */
  void persistSeededPlanId(java.util.UUID planId, String razorpayPlanId) {
    transactionTemplate.executeWithoutResult(
        status -> {
          BillingPlan fresh = planRepo.findById(planId).orElse(null);
          if (fresh == null) {
            log.warn(
                "Plan {} disappeared during seeding; razorpay_plan_id={} is orphaned",
                planId,
                razorpayPlanId);
            return;
          }
          if (fresh.getRazorpayPlanId() != null) {
            log.warn(
                "Plan {} already carries razorpay_plan_id={}; newly created {} is a duplicate and"
                    + " needs manual cleanup in the Razorpay dashboard",
                planId,
                fresh.getRazorpayPlanId(),
                razorpayPlanId);
            return;
          }
          fresh.setRazorpayPlanId(razorpayPlanId);
          planRepo.save(fresh);
        });
  }

  /**
   * RR-418 — the runtime only ever recognised {@code ANNUAL}, but every annual migration inserted
   * {@code YEARLY}, so annual plans were seeded as MONTHLY Razorpay plans. V310 forward-migrates
   * the stored rows; this alias keeps the seeder correct for any row that predates or bypasses it.
   */
  static boolean annualPeriod(String period) {
    String normalized = period == null ? "" : period.trim().toUpperCase();
    return "ANNUAL".equals(normalized) || "YEARLY".equals(normalized);
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
