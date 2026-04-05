package com.navneet.executespec.billing.security;

import com.navneet.executespec.billing.config.RazorpayConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookSignatureVerifier {

    private final RazorpayConfig config;

    /**
     * Verify Razorpay webhook signature.
     * X-Razorpay-Signature = HMAC-SHA256(raw_payload, webhook_secret)
     */
    public boolean verifyWebhookSignature(String rawPayload, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            String expected = hmacSha256(rawPayload, config.getWebhookSecret());
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Webhook signature verification failed", e);
            return false;
        }
    }

    /**
     * Verify Razorpay payment signature after subscription checkout.
     * Signature = HMAC-SHA256(subscription_id + "|" + payment_id, key_secret)
     */
    public boolean verifyPaymentSignature(String subscriptionId, String paymentId, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            String payload = subscriptionId + "|" + paymentId;
            String expected = hmacSha256(payload, config.getKeySecret());
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("Payment signature verification failed", e);
            return false;
        }
    }

    /**
     * Verify Razorpay order payment signature (one-time payments / top-ups).
     * Signature = HMAC-SHA256(order_id + "|" + payment_id, key_secret)
     */
    public boolean verifyOrderPaymentSignature(String orderId, String paymentId, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            String payload = orderId + "|" + paymentId;
            String expected = hmacSha256(payload, config.getKeySecret());
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8)
            );
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
