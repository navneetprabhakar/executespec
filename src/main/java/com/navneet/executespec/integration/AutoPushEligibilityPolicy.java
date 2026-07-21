package com.navneet.executespec.integration;

import java.util.Set;

/**
 * RR-826 — decides whether a terminal BUILD-mode run may auto-publish to the customer's SCM.
 *
 * <p>This is a DENYLIST, not an inverted gate. The platform's default is to auto-push a terminal
 * {@code COMPLETE}/{@code PARTIAL} run to the linked repository, and several legitimate PARTIAL
 * outcomes rely on that: intermediate warnings, tests that ran and partially failed, a validation
 * report that was produced but scored below the bar, and measured governance violations. Flipping
 * the gate to "push only if fully verified" would break those. So we do NOT invert it — we exclude
 * exactly the terminal reasons that mean the run's verification did not happen or failed hard.
 * Publishing on those states is the RR-826 bug: opening a PR while the terminal reason says "we
 * could not verify this run".
 *
 * <p>The classification is by MEANING, so triage the next reason you add against two questions:
 *
 * <ol>
 *   <li><b>Was the deliverable/verification actually measured?</b> If a check ran and merely
 *       reported a partial / failing / below-bar result — {@code TESTS_FAILED:*}, {@code
 *       REVIEWER_UNRESOLVED}, {@code VALIDATION_REPORT_LOW_QUALITY}, {@code
 *       STANDARDS_BLOCKING_VIOLATION}, {@code ARCHITECTURE_INVARIANT_VIOLATION}, {@code
 *       CLEAN_COMPLETION}, {@code CLEAN_WITH_INTERMEDIATE_WARNINGS} — the run is
 *       measured-but-partial and keeps its current auto-push behaviour → ALLOW.
 *   <li><b>Did verification not happen, or did the primary deliverable fail hard?</b> A configured
 *       verifier that could not run ({@code *_UNAVAILABLE}), a reviewer that crashed ({@code
 *       REVIEWER_FAILED}), no reviewer evidence at all ({@code NO_REVIEW_RESULT}), or a red final
 *       build ({@code FINAL_BUILD_FAILED}) → DENY. The central case is {@code
 *       VALIDATION_REPORT_UNAVAILABLE}: the deterministic verified-score artifact the product's
 *       central claim rests on was never produced.
 * </ol>
 *
 * <p>Only the automatic SCM push is gated. The workspace, the run result, "iterate as new run", and
 * the manual push endpoints (which keep their own gates) are untouched — the customer can still
 * choose to publish an unverified run themselves.
 *
 * <p>Note on {@code LOCAL_EXECUTION}: local-hands runs are handled upstream (their server workspace
 * is empty and the BUILD auto-push path is not taken), so that reason is intentionally not part of
 * this decision and is left to its own handling.
 */
final class AutoPushEligibilityPolicy {

  private AutoPushEligibilityPolicy() {}

  /**
   * Terminal {@code status_reason} values that mean verification did not happen, a verifier
   * crashed, or the primary build deliverable failed hard. A run stamped with one of these must not
   * auto-publish, because the platform cannot stand behind output it never verified.
   */
  private static final Set<String> UNVERIFIED_TERMINAL_REASONS =
      Set.of(
          // RR-826 core — the deterministic verified-score artifact was never produced (report
          // generation threw, the run row vanished, or persistence failed).
          "VALIDATION_REPORT_UNAVAILABLE",
          // Report provenance/evidence check failed (carries FAILED status, so the COMPLETE/PARTIAL
          // gate would not fire anyway; listed for defence-in-depth).
          "VALIDATION_EVIDENCE_FAILED",
          // The primary deliverable does not build — the build IS the verification and it is red.
          "FINAL_BUILD_FAILED",
          // The reviewer verification crashed / could not complete (materialized REVIEW_FAILED).
          // Distinct from REVIEWER_UNRESOLVED, where the reviewer DID run and flagged issues.
          "REVIEWER_FAILED",
          // The reviewer produced no evidence at all — the review step never established anything.
          "NO_REVIEW_RESULT",
          // A configured standards verifier could not run (vs STANDARDS_BLOCKING_VIOLATION, where
          // it ran and found a violation).
          "STANDARDS_ENFORCEMENT_UNAVAILABLE",
          // A configured architecture-invariant verifier could not run (vs
          // ARCHITECTURE_INVARIANT_VIOLATION, where it ran and found a violation).
          "INVARIANT_ENFORCEMENT_UNAVAILABLE");

  /**
   * @param statusReason the terminal {@code status_reason} the run was stamped with (may be null)
   * @return {@code true} when auto-push may proceed for this reason; {@code false} when the run's
   *     verification is absent or hard-failed and must not be published automatically
   */
  static boolean allowsAutoPush(String statusReason) {
    if (statusReason == null || statusReason.isBlank()) {
      // A terminal COMPLETE/PARTIAL run is always stamped with a concrete reason
      // (resolveTerminalStatus guarantees it), so a missing reason here is itself a
      // verification-provenance gap. Publication is irreversible — fail closed.
      return false;
    }
    String reason = statusReason.trim();
    if (UNVERIFIED_TERMINAL_REASONS.contains(reason)) {
      return false;
    }
    // Future-proofing by meaning: any "<VERIFIER>_UNAVAILABLE" reason says a verifier could not
    // run.
    // Deny the whole family so a newly-introduced "unavailable" reason is fail-closed even before
    // it
    // is added to the explicit set above.
    return !reason.endsWith("_UNAVAILABLE");
  }
}
