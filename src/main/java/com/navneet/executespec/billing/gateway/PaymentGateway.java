package com.navneet.executespec.billing.gateway;

/**
 * Unified payment gateway interface for multi-gateway support (Razorpay, Stripe).
 * Each gateway implementation handles provider-specific checkout, webhook, and subscription logic.
 */
public interface PaymentGateway {

    /**
     * Create a checkout session / subscription link for the given billing entity and plan.
     */
    CheckoutResult createCheckoutSession(BillingEntity entity, String planId,
                                          String successUrl, String cancelUrl);

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
     * Retrieve the invoice PDF URL from the gateway (Stripe-native).
     * For Razorpay, returns null (PDFs are generated server-side).
     */
    String getInvoicePdfUrl(String gatewayInvoiceId);

    /**
     * Returns the gateway type identifier (e.g., "RAZORPAY", "STRIPE").
     */
    String gatewayType();
}
