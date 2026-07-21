package com.navneet.executespec.agent;

/**
 * Thrown when a pure-BYOK (OWN_KEYS) run reaches an LLM dispatch with no customer key available and
 * would otherwise fall back to platform credentials (BYOK deep-dive §7 #2, 2026-07-17). It fails
 * the run closed instead of silently spending on the platform's keys, preserving the customer's
 * cost and data boundary. This is a permanent, non-retryable condition — it must NOT trigger
 * cross-provider failover or a platform-key retry.
 */
public class ByokContextUnavailableException extends RuntimeException {
  public ByokContextUnavailableException(String message) {
    super(message);
  }
}
