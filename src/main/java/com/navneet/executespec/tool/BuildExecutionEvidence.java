package com.navneet.executespec.tool;

import com.navneet.executespec.smoketest.BuildVerificationResult;

/** Structured build execution evidence used by deterministic acceptance, never by an LLM parser. */
public record BuildExecutionEvidence(
    Disposition disposition,
    BuildVerificationResult verification,
    String output,
    String detail,
    String executionBoundary,
    boolean retryable) {

  public enum Disposition {
    EXECUTED,
    UNAVAILABLE,
    POLICY_DENIED,
    TIMED_OUT,
    LAUNCH_FAILED,
    MALFORMED_EVIDENCE
  }

  public boolean environmentFailure() {
    return disposition != Disposition.EXECUTED;
  }
}
