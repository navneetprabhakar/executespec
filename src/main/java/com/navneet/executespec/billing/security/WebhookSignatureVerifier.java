package com.navneet.executespec.billing.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.navneet.executespec.billing.config.RazorpayConfig;

import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookSignatureVerifier {

  private final RazorpayConfig config;

  /**
   * Verify Razorpay webhook signature. X-Razorpay-Signature = HMAC-SHA256(raw_payload,
   * webhook_secret)
   */
  public boolean verifyWebhookSignature(String rawPayload, String signature) {
    if (signature == null || signature.isBlank()) return false;
    try {
      String expected = hmacSha256(rawPayload, config.getWebhookSecret());
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.error("Webhook signature verification failed", e);
      return false;
    }
  }

  /**
   * Verify Razorpay payment signature after subscription checkout. Signature =
   * HMAC-SHA256(payment_id + "|" + subscription_id, key_secret) Note: Razorpay places payment_id
   * FIRST for subscription verification.
   */
  public boolean verifyPaymentSignature(String subscriptionId, String paymentId, String signature) {
    if (signature == null || signature.isBlank()) return false;
    try {
      String payload = paymentId + "|" + subscriptionId;
      String expected = hmacSha256(payload, config.getKeySecret());
      boolean match =
          MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.UTF_8),
              signature.getBytes(StandardCharsets.UTF_8));
      if (!match) {
        log.warn(
            "Payment signature mismatch: subId={}, paymentId={}, expected={}, actual={}",
            subscriptionId,
            paymentId,
            expected.substring(0, Math.min(8, expected.length())) + "...",
            signature.substring(0, Math.min(8, signature.length())) + "...");
      }
      return match;
    } catch (Exception e) {
      log.error("Payment signature verification failed", e);
      return false;
    }
  }

  /**
   * Verify Razorpay order payment signature (one-time payments / top-ups). Signature =
   * HMAC-SHA256(order_id + "|" + payment_id, key_secret)
   */
  public boolean verifyOrderPaymentSignature(String orderId, String paymentId, String signature) {
    if (signature == null || signature.isBlank()) return false;
    try {
      String payload = orderId + "|" + paymentId;
      String expected = hmacSha256(payload, config.getKeySecret());
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.error("Order payment signature verification failed", e);
      return false;
    }
  }

  private String hmacSha256(String data, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Hex.encodeHexString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
  }
}
