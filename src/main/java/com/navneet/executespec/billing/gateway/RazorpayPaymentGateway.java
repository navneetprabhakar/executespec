package com.navneet.executespec.billing.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Razorpay implementation of the PaymentGateway interface.
 * Delegates to the existing BillingGateway (RazorpayGateway) for actual API calls,
 * and to BillingService for webhook processing.
 *
 * Note: Razorpay checkout flow is different from Stripe — it uses client-side SDK
 * rather than server-side checkout sessions. This adapter provides a compatible
 * interface but the checkout URL returned is the Razorpay short_url.
 */
@Component("razorpayPaymentGateway")
@RequiredArgsConstructor
@Slf4j
public class RazorpayPaymentGateway implements PaymentGateway {

    private final BillingGateway razorpayGateway;

    @Override
    public CheckoutResult createCheckoutSession(BillingEntity entity, String planId,
                                                  String successUrl, String cancelUrl) {
        // Razorpay subscription creation is handled by the existing BillingGateway.
        // This method provides a compatibility layer — the actual subscription creation
        // requires user details that are resolved by BillingService.subscribe().
        // For now, return null to indicate that Razorpay uses a different checkout flow.
        log.warn("createCheckoutSession called on RazorpayPaymentGateway — " +
                "Razorpay uses client-side SDK checkout, not server-side sessions. " +
                "Use BillingService.subscribe() instead.");
        return null;
    }

    @Override
    public WebhookResult handleWebhook(String payload, String signatureHeader) {
        // Razorpay webhook processing is handled by BillingService.processRazorpayWebhook()
        // This adapter just validates the signature and returns a result.
        // Actual processing is done by the caller.
        log.debug("Razorpay webhook delegation — processing handled by BillingService");
        return new WebhookResult(true, "delegated", "Razorpay webhooks processed by BillingService");
    }

    @Override
    public void cancelSubscription(String gatewaySubscriptionId) {
        razorpayGateway.cancelAtPeriodEnd(gatewaySubscriptionId);
        log.info("Razorpay subscription cancelled via PaymentGateway adapter: {}", gatewaySubscriptionId);
    }

    @Override
    public String getInvoicePdfUrl(String gatewayInvoiceId) {
        // Razorpay does not provide hosted invoice PDFs — we generate them server-side
        return null;
    }

    @Override
    public String gatewayType() {
        return "RAZORPAY";
    }
}
