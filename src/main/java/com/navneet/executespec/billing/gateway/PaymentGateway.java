package com.navneet.executespec.billing.gateway;

/**
 * Unified payment gateway interface for multi-gateway support (Razorpay, Stripe).
 * Each gateway implementation handles provider-specific checkout, webhook, and subscription logic.
 */
public interface PaymentGateway {

    /**
     * Create a checkout session / subscription link for the given billing entity and plan.
     * Defaults to a quantity of 1 — appropriate for every per-plan tier (FREE / PRO / MAX).
     */
    CheckoutResult createCheckoutSession(BillingEntity entity, String planId,
                                          String successUrl, String cancelUrl);

    /**
     * v2.6.0 — seat-aware checkout for per-seat-priced plans (TEAM).
     * Passes {@code seats} as the line-item quantity on gateways that natively
     * support it (Stripe). Default implementation ignores {@code seats} and
     * delegates to the single-seat flow — fine for plans with
     * {@code per_seat_*=0} but wrong for TEAM, so gateways that support TEAM
     * MUST override.
     */
    default CheckoutResult createCheckoutSession(BillingEntity entity, String planId,
                                                  int seats, String successUrl, String cancelUrl) {
        return createCheckoutSession(entity, planId, successUrl, cancelUrl);
    }

    /**
     * Verify and process an incoming webhook payload.
     * Returns structured result with event type and processing status.
     */
    WebhookResult handleWebhook(String payload, String signatureHeader);

    /**
     * Cancel an active subscription at the gateway.
     */
    void cancelSubscription(String gatewaySubscriptionId);

    /**
     * v2.6.0 — cancel an active subscription at the end of the current billing
     * period. Used by the tier-lifecycle SUNSETTING transition to wind down a
     * tier without forcing immediate churn.
     *
     * <p>Default implementation delegates to {@link #cancelSubscription} for
     * gateways that don't natively support deferred cancellation. Subclasses
     * should override when a dedicated API exists (Stripe:
     * {@code Subscription.update(cancel_at_period_end=true)}; Razorpay:
     * {@code Subscription.cancel(cancel_at_cycle_end=true)}).
     */
    default void cancelAtPeriodEnd(String gatewaySubscriptionId) {
        cancelSubscription(gatewaySubscriptionId);
    }

    /**
     * Retrieve the invoice PDF URL from the gateway (Stripe-native).
     * For Razorpay, returns null (PDFs are generated server-side).
     */
    String getInvoicePdfUrl(String gatewayInvoiceId);

    /**
     * Returns the gateway type identifier (e.g., "RAZORPAY", "STRIPE").
     */
    String gatewayType();
}
