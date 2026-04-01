package com.navneet.executespec.billing.controller;

import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.gateway.WebhookResult;
import com.navneet.executespec.billing.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class BillingWebhookController {

    private final BillingService billingService;
    private final PaymentGatewayRouter gatewayRouter;

    /**
     * POST /api/webhooks/razorpay
     *
     * NOT behind JWT auth — authenticated via HMAC signature.
     * SecurityConfig must have: .requestMatchers(POST, "/api/webhooks/razorpay").permitAll()
     *
     * ALWAYS returns 200 to prevent Razorpay from retrying.
     * Raw payload is stored in billing_webhook_event for audit + reprocessing.
     */
    @PostMapping("/razorpay")
    public ResponseEntity<Void> handleRazorpayWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        if (signature == null || signature.isBlank()) {
            log.warn("Webhook received without X-Razorpay-Signature header");
            return ResponseEntity.status(401).build();
        }

        try {
            billingService.processRazorpayWebhook(rawPayload, signature);
        } catch (SecurityException e) {
            log.warn("Webhook rejected: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            // Log but return 200 — we stored the raw payload, can reprocess later
            log.error("Webhook processing error (returning 200): {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/webhooks/stripe
     *
     * NOT behind JWT auth — authenticated via Stripe-Signature header.
     * SecurityConfig must have: .requestMatchers(POST, "/api/webhooks/stripe").permitAll()
     *
     * ALWAYS returns 200 to prevent Stripe from retrying.
     */
    @PostMapping("/stripe")
    public ResponseEntity<Void> handleStripeWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        if (signature == null || signature.isBlank()) {
            log.warn("Stripe webhook received without Stripe-Signature header");
            return ResponseEntity.status(401).build();
        }

        if (!gatewayRouter.isStripeEnabled()) {
            log.warn("Stripe webhook received but Stripe is not enabled");
            return ResponseEntity.status(403).build();
        }

        try {
            WebhookResult result = gatewayRouter.stripe().handleWebhook(rawPayload, signature);
            if (!result.success()) {
                log.warn("Stripe webhook rejected: {}", result.message());
                return ResponseEntity.status(401).build();
            }

            // Delegate event processing to BillingService
            billingService.processStripeWebhook(rawPayload, signature, result.eventType());
        } catch (Exception e) {
            // Log but return 200 — prevent Stripe from retrying
            log.error("Stripe webhook processing error (returning 200): {}", e.getMessage(), e);
        }

        return ResponseEntity.ok().build();
    }
}
