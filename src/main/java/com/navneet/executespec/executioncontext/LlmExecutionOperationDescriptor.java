package com.navneet.executespec.executioncontext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable identity for one independently leased operation inside a shared LLM context. */
public record LlmExecutionOperationDescriptor(
    String operationKey, String operationKind, String fingerprint, int maxAttempts) {

  public LlmExecutionOperationDescriptor {
    operationKey = requireText(operationKey, "operationKey", 192);
    operationKind =
        requireText(operationKind, "operationKind", 40).toUpperCase(java.util.Locale.ROOT);
    if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("fingerprint must be a lowercase SHA-256 digest");
    }
    if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts must be positive");
  }

  /** Builds a byte-stable descriptor without exposing callers to ad-hoc hash formatting. */
  public static LlmExecutionOperationDescriptor of(
      String operationKey, String operationKind, String immutableIdentity, int maxAttempts) {
    return new LlmExecutionOperationDescriptor(
        operationKey,
        operationKind,
        sha256(requireText(immutableIdentity, "immutableIdentity", 16_384)),
        maxAttempts);
  }

  private static String requireText(String value, String field, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " required");
    String normalized = value.trim();
    if (normalized.length() > maxLength) {
      throw new IllegalArgumentException(field + " exceeds " + maxLength + " characters");
    }
    return normalized;
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
