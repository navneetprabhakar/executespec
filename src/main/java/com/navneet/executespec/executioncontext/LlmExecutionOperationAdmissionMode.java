package com.navneet.executespec.executioncontext;

import java.util.Locale;

/** Rollout mode for operation-fenced physical LLM-attempt admission. */
public enum LlmExecutionOperationAdmissionMode {
  /** Preserve version-0 write-ahead intent behavior; atomic admission remains inactive. */
  LEGACY,

  /**
   * Use atomic version-1 admission when authority is present, while temporarily allowing a
   * diagnostic version-0 fallback for durable callers whose roots are not wired yet.
   */
  SHADOW,

  /** Require operation authority and atomic version-1 admission for every durable request. */
  ENFORCED;

  static LlmExecutionOperationAdmissionMode parse(String configured) {
    String value = configured == null || configured.isBlank() ? LEGACY.name() : configured.trim();
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(
          "executespec.llm.operation-admission.mode must be LEGACY, SHADOW, or ENFORCED", invalid);
    }
  }
}
