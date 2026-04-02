package com.navneet.executespec.billing.gateway;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stripe implementation of the PaymentGateway interface.
 * Uses Stripe Checkout Sessions for subscription creation, and
 * verifies webhooks using Stripe-Signature header.
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
    public CheckoutResult createCheckoutSession(BillingEntity entity, String planId,
                                                  String successUrl, String cancelUrl) {
        try {
            String priceId = resolvePriceId(planId);

            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setPrice(priceId)
                            .setQuantity(1L)
                            .build())
                    .putMetadata("billing_entity_id", entity.getId())
                    .putMetadata("billing_entity_type", entity.getEntityType())
                    .putMetadata("plan_id", planId);

            // Attach existing Stripe customer if available
            if (entity.getStripeCustomerId() != null && !entity.getStripeCustomerId().isBlank()) {
                builder.setCustomer(entity.getStripeCustomerId());
            } else {
                builder.setCustomerCreation(SessionCreateParams.CustomerCreation.ALWAYS);
            }

            Session session = Session.create(builder.build());

            log.info("Stripe checkout session created: sessionId={}, entityId={}, entityType={}",
                    session.getId(), entity.getId(), entity.getEntityType());

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
            subscription.cancel(SubscriptionCancelParams.builder()
                    .setProrate(true)
                    .build());
            log.info("Stripe subscription cancelled: {}", gatewaySubscriptionId);
        } catch (StripeException e) {
            log.error("Failed to cancel Stripe subscription {}: {}", gatewaySubscriptionId, e.getMessage(), e);
            throw new RuntimeException("Stripe subscription cancellation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getInvoicePdfUrl(String gatewayInvoiceId) {
        try {
            Invoice invoice = Invoice.retrieve(gatewayInvoiceId);
            return invoice.getInvoicePdf();
        } catch (StripeException e) {
            log.error("Failed to retrieve Stripe invoice PDF URL for {}: {}",
                    gatewayInvoiceId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Create a one-time payment checkout session for run top-ups.
     */
    public CheckoutResult createTopupCheckoutSession(BillingEntity entity, String stripePriceId,
                                                       int quantity, String successUrl, String cancelUrl) {
        try {
            SessionCreateParams.Builder builder = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .addLineItem(SessionCreateParams.LineItem.builder()
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
            log.info("Stripe topup checkout session created: sessionId={}, entityId={}", session.getId(), entity.getId());
            return new CheckoutResult(session.getUrl(), session.getId());
        } catch (StripeException e) {
            log.error("Failed to create Stripe topup checkout: {}", e.getMessage(), e);
            throw new RuntimeException("Stripe topup checkout failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a Stripe Billing Portal URL for subscription self-management.
     */
    public String getCustomerPortalUrl(String stripeCustomerId, String returnUrl) {
        try {
            var params = com.stripe.param.billingportal.SessionCreateParams.builder()
                    .setCustomer(stripeCustomerId)
                    .setReturnUrl(returnUrl)
                    .build();
            var session = com.stripe.model.billingportal.Session.create(params);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("Failed to create Stripe portal session for {}: {}", stripeCustomerId, e.getMessage(), e);
            throw new RuntimeException("Stripe portal session failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String gatewayType() {
        return "STRIPE";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String resolvePriceId(String planId) {
        // planId is the internal plan identifier — map to Stripe price IDs
        // The planId here comes from the billing_plan table, we match on tier
        if (planId != null) {
            String lower = planId.toLowerCase();
            if (lower.contains("enterprise")) {
                return enterprisePriceId;
            }
        }
        // Default to Pro price
        return proPriceId;
    }
}
