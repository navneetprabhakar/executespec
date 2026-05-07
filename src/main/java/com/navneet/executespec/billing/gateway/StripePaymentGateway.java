package com.navneet.executespec.billing.gateway;

import jakarta.annotation.PostConstruct;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.Invoice;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe implementation of the PaymentGateway interface. Uses Stripe Checkout Sessions for
 * subscription creation, and verifies webhooks using Stripe-Signature header.
 */
@Component
@Slf4j
public class StripePaymentGateway implements PaymentGateway {

  @Value("${billing.stripe.secret-key:}")
  private String secretKey;

  @Value("${billing.stripe.webhook-secret:}")
  private String webhookSecret;

  @Value("${billing.stripe.pro-price-id:}")
  private String proPriceId;

  @Value("${billing.stripe.enterprise-price-id:}")
  private String enterprisePriceId;

  @Value("${billing.stripe.team-price-id:}")
  private String teamPriceId;

  @Value("${billing.stripe.team-price-id-inr:}")
  private String teamPriceIdInr;

  /** Injected lazily so the gateway can boot before BillingPlan repo is wired. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.navneet.executespec.billing.repository.BillingPlanRepository planRepo;

  @PostConstruct
  void init() {
    if (secretKey != null && !secretKey.isBlank()) {
      Stripe.apiKey = secretKey;
      log.info("Stripe API key configured");
    } else {
      log.info("Stripe API key not configured — Stripe gateway disabled");
    }
  }

  @Override
  public CheckoutResult createCheckoutSession(
      BillingEntity entity, String planId, String successUrl, String cancelUrl) {
    return createCheckoutSession(entity, planId, 1, successUrl, cancelUrl);
  }

  /**
   * v2.6.0 — seat-aware Stripe checkout. For per-plan tiers (PRO/MAX), call with {@code seats=1}.
   * For per-seat tiers (TEAM), pass the user's requested seat count (2-20 enforced upstream in
   * BillingService). Stripe charges {@code price × seats} automatically and records the quantity on
   * the resulting {@code Subscription} — pulled on webhook and written to {@code
   * BillingSubscription.totalSeats}.
   */
  @Override
  public CheckoutResult createCheckoutSession(
      BillingEntity entity, String planId, int seats, String successUrl, String cancelUrl) {
    if (seats < 1) {
      throw new IllegalArgumentException("seats must be >= 1, got " + seats);
    }
    try {
      String priceId = resolvePriceId(planId);

      SessionCreateParams.Builder builder =
          SessionCreateParams.builder()
              .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
              .setSuccessUrl(successUrl)
              .setCancelUrl(cancelUrl)
              .addLineItem(
                  SessionCreateParams.LineItem.builder()
                      .setPrice(priceId)
                      .setQuantity((long) seats)
                      .build())
              .putMetadata("billing_entity_id", entity.getId())
              .putMetadata("billing_entity_type", entity.getEntityType())
              .putMetadata("plan_id", planId)
              .putMetadata("seats", String.valueOf(seats));
      // V143 "product" metadata retired (V179, single-surface
      // retirement 2026-05-06). All checkouts run on the
      // unified 'executespec' surface; no discriminator
      // needed in Stripe metadata.

      // Attach existing Stripe customer if available
      if (entity.getStripeCustomerId() != null && !entity.getStripeCustomerId().isBlank()) {
        builder.setCustomer(entity.getStripeCustomerId());
      } else {
        builder.setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS);
      }

      Session session = Session.create(builder.build());

      log.info(
          "Stripe checkout session created: sessionId={}, entityId={}, entityType={}",
          session.getId(),
          entity.getId(),
          entity.getEntityType());

      return new CheckoutResult(session.getUrl(), session.getId());
    } catch (StripeException e) {
      log.error("Failed to create Stripe checkout session: {}", e.getMessage(), e);
      throw new RuntimeException("Stripe checkout session creation failed: " + e.getMessage(), e);
    }
  }

  @Override
  public WebhookResult handleWebhook(String payload, String signatureHeader) {
    if (webhookSecret == null || webhookSecret.isBlank()) {
      log.warn("Stripe webhook secret not configured — rejecting webhook");
      return new WebhookResult(false, "unknown", "Webhook secret not configured");
    }

    Event event;
    try {
      event = Webhook.constructEvent(payload, signatureHeader, webhookSecret);
    } catch (SignatureVerificationException e) {
      log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
      return new WebhookResult(false, "unknown", "Invalid signature");
    } catch (Exception e) {
      log.error("Failed to parse Stripe webhook: {}", e.getMessage(), e);
      return new WebhookResult(false, "unknown", "Parse error: " + e.getMessage());
    }

    String eventType = event.getType();
    log.info("Stripe webhook received: type={}, id={}", eventType, event.getId());

    // Event processing is handled by the caller (BillingService) using the event type.
    // Here we just validate and return the parsed event info.
    return new WebhookResult(true, eventType, "Event validated: " + event.getId());
  }

  @Override
  public void cancelSubscription(String gatewaySubscriptionId) {
    try {
      Subscription subscription = Subscription.retrieve(gatewaySubscriptionId);
      subscription.cancel(SubscriptionCancelParams.builder().setProrate(true).build());
      log.info("Stripe subscription cancelled: {}", gatewaySubscriptionId);
    } catch (StripeException e) {
      log.error(
          "Failed to cancel Stripe subscription {}: {}", gatewaySubscriptionId, e.getMessage(), e);
      throw new RuntimeException("Stripe subscription cancellation failed: " + e.getMessage(), e);
    }
  }

  /**
   * v2.6.0 — update the seat count on an active subscription. Called from the TEAM seat-adjust
   * endpoint. Stripe prorates automatically; the next invoice reflects the new quantity × price.
   *
   * <p>Mutates the first subscription item's quantity — fine for the TEAM tier which has exactly
   * one line item. Caller should pre-validate that {@code newQuantity} is within the TEAM 2-20
   * self-serve range.
   *
   * @throws IllegalArgumentException when {@code newQuantity < 1}
   * @throws RuntimeException when the Stripe API call fails
   */
  @Override
  public boolean updateSubscriptionQuantity(String gatewaySubscriptionId, long newQuantity) {
    if (newQuantity < 1) {
      throw new IllegalArgumentException("newQuantity must be >= 1, got " + newQuantity);
    }
    try {
      Subscription subscription = Subscription.retrieve(gatewaySubscriptionId);
      if (subscription.getItems() == null
          || subscription.getItems().getData() == null
          || subscription.getItems().getData().isEmpty()) {
        throw new RuntimeException(
            "Stripe subscription has no line items: " + gatewaySubscriptionId);
      }
      String itemId = subscription.getItems().getData().get(0).getId();

      subscription.update(
          SubscriptionUpdateParams.builder()
              .addItem(
                  SubscriptionUpdateParams.Item.builder()
                      .setId(itemId)
                      .setQuantity(newQuantity)
                      .build())
              .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
              .build());
      log.info(
          "Stripe subscription quantity updated: subId={} itemId={} newQuantity={}",
          gatewaySubscriptionId,
          itemId,
          newQuantity);
      return true;
    } catch (StripeException e) {
      log.error(
          "Failed to update Stripe subscription {} quantity to {}: {}",
          gatewaySubscriptionId,
          newQuantity,
          e.getMessage(),
          e);
      throw new RuntimeException(
          "Stripe subscription quantity update failed: " + e.getMessage(), e);
    }
  }

  /**
   * v2.6.0 — queue cancellation at the end of the current billing period. Used by {@code
   * TierLifecycleService} when a tier transitions to {@code SUNSETTING}. No refund, no mid-cycle
   * proration — the subscriber keeps full access until {@code current_period_end}, then {@code
   * PeriodEndDowngradeScheduler} picks them up and moves them to FREE.
   *
   * <p>Idempotent: calling this multiple times on the same subscription is a no-op on Stripe's side
   * — the {@code cancel_at_period_end=true} flag is state, not an event.
   */
  @Override
  public void cancelAtPeriodEnd(String gatewaySubscriptionId) {
    try {
      Subscription subscription = Subscription.retrieve(gatewaySubscriptionId);
      subscription.update(SubscriptionUpdateParams.builder().setCancelAtPeriodEnd(true).build());
      log.info(
          "Stripe subscription queued for cancellation at period end: {}", gatewaySubscriptionId);
    } catch (StripeException e) {
      log.error(
          "Failed to queue Stripe subscription {} for period-end cancel: {}",
          gatewaySubscriptionId,
          e.getMessage(),
          e);
      throw new RuntimeException(
          "Stripe subscription cancel-at-period-end failed: " + e.getMessage(), e);
    }
  }

  @Override
  public String getInvoicePdfUrl(String gatewayInvoiceId) {
    try {
      Invoice invoice = Invoice.retrieve(gatewayInvoiceId);
      return invoice.getInvoicePdf();
    } catch (StripeException e) {
      log.error(
          "Failed to retrieve Stripe invoice PDF URL for {}: {}",
          gatewayInvoiceId,
          e.getMessage(),
          e);
      return null;
    }
  }

  /** Create a one-time payment checkout session for run top-ups. */
  public CheckoutResult createTopupCheckoutSession(
      BillingEntity entity,
      String stripePriceId,
      int quantity,
      String successUrl,
      String cancelUrl) {
    try {
      SessionCreateParams.Builder builder =
          SessionCreateParams.builder()
              .setMode(SessionCreateParams.Mode.PAYMENT)
              .setSuccessUrl(successUrl)
              .setCancelUrl(cancelUrl)
              .addLineItem(
                  SessionCreateParams.LineItem.builder()
                      .setPrice(stripePriceId)
                      .setQuantity((long) quantity)
                      .build())
              .putMetadata("billing_entity_id", entity.getId())
              .putMetadata("billing_entity_type", entity.getEntityType())
              .putMetadata("type", "topup");

      if (entity.getStripeCustomerId() != null && !entity.getStripeCustomerId().isBlank()) {
        builder.setCustomer(entity.getStripeCustomerId());
      } else {
        builder.setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS);
      }

      Session session = Session.create(builder.build());
      log.info(
          "Stripe topup checkout session created: sessionId={}, entityId={}",
          session.getId(),
          entity.getId());
      return new CheckoutResult(session.getUrl(), session.getId());
    } catch (StripeException e) {
      log.error("Failed to create Stripe topup checkout: {}", e.getMessage(), e);
      throw new RuntimeException("Stripe topup checkout failed: " + e.getMessage(), e);
    }
  }

  /** Generate a Stripe Billing Portal URL for subscription self-management. */
  public String getCustomerPortalUrl(String stripeCustomerId, String returnUrl) {
    try {
      var params =
          com.stripe.param.billingportal.SessionCreateParams.builder()
              .setCustomer(stripeCustomerId)
              .setReturnUrl(returnUrl)
              .build();
      var session = com.stripe.model.billingportal.Session.create(params);
      return session.getUrl();
    } catch (StripeException e) {
      log.error(
          "Failed to create Stripe portal session for {}: {}", stripeCustomerId, e.getMessage(), e);
      throw new RuntimeException("Stripe portal session failed: " + e.getMessage(), e);
    }
  }

  @Override
  public String gatewayType() {
    return "STRIPE";
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  /**
   * v2.6.1 — resolve a Stripe Price ID for a given billing_plan.
   *
   * <p>Priority order:
   *
   * <ol>
   *   <li>{@code billing_plan.stripe_price_id} column — authoritative when the ops team has seeded
   *       it post-Stripe-Product creation. Lets us run test vs. live accounts without code changes.
   *   <li>Tier + currency env var — {@code STRIPE_*_PRICE_ID} / {@code _INR} fallback used in dev
   *       and before the DB column is populated.
   *   <li>{@code STRIPE_PRO_PRICE_ID} as an ultimate fallback for legacy call sites that don't map
   *       to any recognised tier.
   * </ol>
   */
  /**
   * Phase 2 wiring helper — looks up the tier for a plan id so the Stripe metadata can stamp the
   * correct surface. Returns null when the plan can't be resolved (caller will fall through to the
   * executespec default in TierProductSurfaceMapper).
   */
  private String resolveTierForPlan(String planId) {
    if (planId == null || planRepo == null) return null;
    try {
      java.util.UUID uuid = java.util.UUID.fromString(planId);
      com.navneet.executespec.billing.entity.BillingPlan plan =
          planRepo.findById(uuid).orElse(null);
      return plan != null ? plan.getTier() : null;
    } catch (IllegalArgumentException ignore) {
      // legacy string planId — pattern match. V165 — "max" rolls up to TEAM.
      String lower = planId.toLowerCase();
      if (lower.contains("enterprise")) return "ENTERPRISE";
      if (lower.contains("team")) return "TEAM";
      if (lower.contains("max")) return "TEAM";
      if (lower.contains("pro")) return "PRO";
      return null;
    }
  }

  private String resolvePriceId(String planId) {
    if (planId == null) return proPriceId;

    // Step 1: DB stripe_price_id when the plan id parses as UUID.
    if (planRepo != null) {
      try {
        java.util.UUID uuid = java.util.UUID.fromString(planId);
        com.navneet.executespec.billing.entity.BillingPlan plan =
            planRepo.findById(uuid).orElse(null);
        if (plan != null) {
          if (plan.getStripePriceId() != null && !plan.getStripePriceId().isBlank()) {
            return plan.getStripePriceId();
          }
          // Step 2: tier + currency env var fallback
          String tier = plan.getTier() == null ? "" : plan.getTier().toUpperCase();
          boolean isInr = plan.getBaseAmountInr() > 0 && plan.getBaseAmountUsd() == 0;
          return resolvePriceIdByTier(tier, isInr);
        }
      } catch (IllegalArgumentException ignore) {
        // not a UUID — fall through to string-pattern match below
      }
    }

    // Legacy string-pattern match on the planId string.
    String lower = planId.toLowerCase();
    if (lower.contains("enterprise")) return enterprisePriceId;
    if (lower.contains("team")) {
      return (lower.contains("inr") && teamPriceIdInr != null && !teamPriceIdInr.isBlank())
          ? teamPriceIdInr
          : teamPriceId;
    }
    return proPriceId;
  }

  private String resolvePriceIdByTier(String tier, boolean isInr) {
    switch (tier) {
      case "ENTERPRISE":
        return enterprisePriceId;
      case "TEAM":
        return (isInr && teamPriceIdInr != null && !teamPriceIdInr.isBlank())
            ? teamPriceIdInr
            : teamPriceId;
      case "PRO":
      default:
        return proPriceId;
    }
  }
}
