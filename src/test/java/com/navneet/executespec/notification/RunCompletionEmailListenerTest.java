package com.navneet.executespec.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link RunCompletionEmailListener#friendlyFailureReason} — the guard that keeps a raw
 * exception / stack trace out of the customer failure email. Regression cover for 2026-05-29, when
 * a JDK spawn-helper IOException stack trace leaked into the "Status reason" line of the email.
 */
class RunCompletionEmailListenerTest {

  private static final String GENERIC = "an internal error on our end.";

  @Test
  void rawExceptionTextCollapsesToGenericMessage() {
    // The exact leak from 2026-05-29 — must never reach the customer.
    String leaked =
        "unexpected: RuntimeException: Workspace init failed for run 599db766: Cannot run program"
            + " \"git\": Failed to exec spawn helper ... JDK version mismatch ... Restart JVM";
    assertThat(RunCompletionEmailListener.friendlyFailureReason(leaked))
        .startsWith(GENERIC)
        .doesNotContain("git", "spawn helper", "JDK", "RuntimeException", "Workspace");
  }

  @Test
  void internalErrorTokenIsGeneric() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("INTERNAL_ERROR"))
        .startsWith(GENERIC);
  }

  @Test
  void knownFailureTokensMapToFriendlyText() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("FINAL_BUILD_FAILED"))
        .isEqualTo("the generated code didn't compile.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("ALL_TASKS_FAILED"))
        .isEqualTo("the run couldn't complete its tasks.");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("REVIEWER_FAILED"))
        .isEqualTo("the automated review couldn't be completed.");
  }

  @Test
  void testsFailedTokenIsParsedIntoCounts() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason("TESTS_FAILED:2_OF_7"))
        .isEqualTo("some generated tests failed (2 of 7).");
    assertThat(RunCompletionEmailListener.friendlyFailureReason("TESTS_FAILED"))
        .isEqualTo("some generated tests failed.");
  }

  @Test
  void nullBlankAndLegacyUnknownOmitTheLine() {
    assertThat(RunCompletionEmailListener.friendlyFailureReason(null)).isNull();
    assertThat(RunCompletionEmailListener.friendlyFailureReason("   ")).isNull();
    assertThat(RunCompletionEmailListener.friendlyFailureReason("LEGACY_UNKNOWN")).isNull();
  }
}
