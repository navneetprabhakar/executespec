package com.navneet.executespec.run;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Canonical SHA-256 fingerprint of a run submission.
 *
 * <p>Two submissions share an idempotency claim only when they request the same work. The
 * fingerprint therefore covers the fields that determine what gets built and what it costs; it
 * deliberately excludes transport-level noise. A changed field yields a different hash, which the
 * claim resolves as a conflict rather than silently returning the earlier run.
 */
public final class RunRequestFingerprint {

  private static final char UNIT_SEPARATOR = '\u001F';

  private RunRequestFingerprint() {}

  /** Fingerprint an ordered list of the submission's semantically significant values. */
  public static String of(List<String> canonicalValues) {
    StringBuilder canonical = new StringBuilder();
    for (String value : canonicalValues) {
      // Length-prefixed so ("a","bc") and ("ab","c") cannot collide.
      String v = value == null ? "" : value;
      canonical.append(v.length()).append(':').append(v).append(UNIT_SEPARATOR);
    }
    return sha256(canonical.toString());
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the Java platform; an absence here is unrecoverable, not ignorable.
      throw new IllegalStateException("SHA-256 unavailable for run idempotency fingerprinting", e);
    }
  }
}
