package com.navneet.executespec.billing.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import com.navneet.executespec.billing.entity.WebhookEventLog;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.gateway.WebhookResult;
import com.navneet.executespec.billing.repository.WebhookEventLogRepository;
import com.navneet.executespec.billing.service.BillingService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class BillingWebhookController {

  private final BillingService billingService;
  private final PaymentGatewayRouter gatewayRouter;
  private final WebhookEventLogRepository webhookEventLogRepo;

  /**
   * POST /api/webhooks/razorpay
   *
   * <p>NOT behind JWT auth — authenticated via HMAC signature. SecurityConfig must have:
   * .requestMatchers(POST, "/api/webhooks/razorpay").permitAll()
   *
   * <p>ALWAYS returns 200 to prevent Razorpay from retrying. Raw payload is stored in
   * billing_webhook_event for audit + reprocessing.
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
   * <p>NOT behind JWT auth — authenticated via Stripe-Signature header. SecurityConfig must have:
   * .requestMatchers(POST, "/api/webhooks/stripe").permitAll()
   *
   * <p>ALWAYS returns 200 to prevent Stripe from retrying.
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

      // Log webhook event (deduplicate by gateway + eventId)
      String eventId =
          result.message() != null ? result.message().replace("Event validated: ", "") : "unknown";
      // RR-052 (2026-07-22, P1) — record the event as PROCESSING BEFORE business handling so the
      // admin audit status cannot claim PROCESSED for an event whose processing later throws. The
      // status is transitioned to PROCESSED/FAILED from the real outcome below.
      WebhookEventLog logEntry =
          recordWebhookReceived("STRIPE", eventId, result.eventType(), rawPayload);

      try {
        // Delegate event processing to BillingService
        billingService.processStripeWebhook(rawPayload, signature, result.eventType());
        markWebhookStatus(logEntry, "PROCESSED");
      } catch (Exception e) {
        markWebhookStatus(logEntry, "FAILED");
        // Log but return 200 — prevent Stripe from retrying; the raw payload is stored for
        // reprocess.
        log.error("Stripe webhook processing error (returning 200): {}", e.getMessage(), e);
      }
    } catch (Exception e) {
      // Log but return 200 — prevent Stripe from retrying
      log.error("Stripe webhook processing error (returning 200): {}", e.getMessage(), e);
    }

    return ResponseEntity.ok().build();
  }

  /**
   * RR-052 — persist the webhook as received/PROCESSING (or bump attempts + IGNORED on a duplicate)
   * BEFORE business processing. Returns the row whose status should reflect the processing outcome,
   * or {@code null} for a duplicate/persistence failure (no status to transition).
   */
  private WebhookEventLog recordWebhookReceived(
      String gateway, String eventId, String eventType, String rawPayload) {
    try {
      var existing = webhookEventLogRepo.findByGatewayAndEventId(gateway, eventId);
      if (existing.isPresent()) {
        var entry = existing.get();
        entry.setAttempts(entry.getAttempts() + 1);
        entry.setProcessingStatus("IGNORED");
        webhookEventLogRepo.save(entry);
        log.info("Duplicate webhook ignored: gateway={}, eventId={}", gateway, eventId);
        return null;
      }

      String payloadHash = sha256(rawPayload);
      return webhookEventLogRepo.save(
          WebhookEventLog.builder()
              .gateway(gateway)
              .eventId(eventId)
              .eventType(eventType)
              .payloadHash(payloadHash)
              .rawPayload(rawPayload)
              .processingStatus("PROCESSING")
              .build());
    } catch (Exception e) {
      log.error(
          "CRITICAL: Failed to persist webhook event gateway={} eventId={} — audit trail gap",
          gateway,
          eventId,
          e);
      return null;
    }
  }

  /** RR-052 — transition a recorded webhook row to its terminal processing status. */
  private void markWebhookStatus(WebhookEventLog logEntry, String status) {
    if (logEntry == null) return;
    try {
      logEntry.setProcessingStatus(status);
      if ("PROCESSED".equals(status)) {
        logEntry.setProcessedAt(Instant.now());
      }
      webhookEventLogRepo.save(logEntry);
    } catch (Exception e) {
      log.error(
          "Failed to update webhook event status gateway={} eventId={} status={}",
          logEntry.getGateway(),
          logEntry.getEventId(),
          status,
          e);
    }
  }

  private static String sha256(String input) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      return null;
    }
  }
}
