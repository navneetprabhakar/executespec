package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-826 — no publication without verification. These tests pin the DENYLIST semantics of the
 * BUILD-mode auto-push eligibility decision: a run whose terminal reason means verification did not
 * happen or failed hard must not auto-publish, while a run that was genuinely measured and merely
 * partial keeps auto-pushing. The gate is a denylist, not "push only if fully verified".
 */
class AutoPushEligibilityPolicyTest {

  @Test
  void unverifiableRunDoesNotAutoPush() {
    // The RR-826 core: report generation could not produce the verified-score artifact.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("VALIDATION_REPORT_UNAVAILABLE")).isFalse();
  }

  @Test
  void hardVerificationFailuresDoNotAutoPush() {
    // The build IS the verification and it is red.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("FINAL_BUILD_FAILED")).isFalse();
    // The reviewer verification crashed / could not complete.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("REVIEWER_FAILED")).isFalse();
    // The reviewer produced no evidence at all.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("NO_REVIEW_RESULT")).isFalse();
    // Configured verifiers that could not run.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("STANDARDS_ENFORCEMENT_UNAVAILABLE"))
        .isFalse();
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("INVARIANT_ENFORCEMENT_UNAVAILABLE"))
        .isFalse();
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("VALIDATION_EVIDENCE_FAILED")).isFalse();
  }

  @Test
  void measuredButPartialRunStillAutoPushes() {
    // Clean outcomes.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("CLEAN_COMPLETION")).isTrue();
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("CLEAN_WITH_INTERMEDIATE_WARNINGS"))
        .isTrue();
    // Tests ran and some failed — measured, merely partial.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("TESTS_FAILED:2_OF_7")).isTrue();
    // The reviewer ran and flagged unresolved issues — measured, distinct from REVIEWER_FAILED.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("REVIEWER_UNRESOLVED")).isTrue();
    // The validation report WAS produced and scored below the bar — measured, not absent.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("VALIDATION_REPORT_LOW_QUALITY")).isTrue();
    // Governance checks that ran and found a violation — measured.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("STANDARDS_BLOCKING_VIOLATION")).isTrue();
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("ARCHITECTURE_INVARIANT_VIOLATION"))
        .isTrue();
  }

  @Test
  void anyUnavailableVerifierReasonIsDeniedByMeaning() {
    // Future-proofing: a not-yet-enumerated "<VERIFIER>_UNAVAILABLE" reason still fails closed.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("SOME_FUTURE_VERIFIER_UNAVAILABLE"))
        .isFalse();
  }

  @Test
  void missingReasonFailsClosed() {
    // A terminal COMPLETE/PARTIAL run is always stamped with a concrete reason; a missing one is a
    // verification-provenance gap, and publication is irreversible.
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush(null)).isFalse();
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("")).isFalse();
    assertThat(AutoPushEligibilityPolicy.allowsAutoPush("   ")).isFalse();
  }
}
