package com.navneet.executespec.util;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tiny utility for HMAC-SHA256 hex digests — the most common shape for inbound webhook signature
 * verification (GitHub, Slack legacy, Confluence). Constant-time comparison is the caller's
 * responsibility (use {@link java.security.MessageDigest#isEqual}).
 *
 * <p>Audit 2026-05-12 line-by-line review item C — shared helper added so each webhook adapter
 * doesn't redo the Mac/spec ceremony.
 */
public final class HmacUtil {

  private HmacUtil() {}

  /** Returns lowercase hex SHA-256 HMAC of {@code body} under {@code secret}. */
  public static String hexSha256(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] raw = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(raw.length * 2);
      for (byte b : raw) {
        sb.append(String.format("%02x", b & 0xff));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
