package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

/**
 * 2026-05-08 — multiplicative re-shape of {@code computeVerifiedScore}. Pins the new conjunctive
 * shape that replaced the additive weighted-sum formula. The shape change is substantive: a failed
 * build now collapses the headline number (was: −20 off a base 100; now: 0). A run with no tests
 * captured is strongly damped (was: −20 off; now: ×0.5). A cliff at 95% endpoint coverage is gone
 * (was: 0/+10; now: linear 0–1.0).
 *
 * <p>Each test documents the factor breakdown so the formula stays transparent under future tuning.
 * Persisted historical scores are NOT migrated — old runs read whatever the additive formula
 * produced; new runs use the new shape. The frontend's VerdictPill (Plan 1 #3) is the
 * customer-facing summary; the numeric score is now diagnostic detail for ops + API consumers.
 */
class ValidationReportServiceVerifiedScoreTest {

  private final ValidationReportService svc =
      new ValidationReportService(
          null, null, null, null, null, null, mock(FeatureFlagService.class));

  // ── Multiplicative factor tests ────────────────────────────────────────

  @Test
  void perfect_run_caps_at_100() {
    // All factors = 1.0 → 100 * 1 = 100
    int score = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0);
    assertEquals(100, score);
  }

  @Test
  void perfect_run_with_full_endpoint_coverage_still_100() {
    // 1 * 1 * 1 * 1 * 1 = 1 → 100
    int score = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 100);
    assertEquals(100, score);
  }

  @Test
  void zero_tests_damps_score_to_half() {
    // build=1.0, test=0.5 (neutral, no tests captured), reviewer=1.0 (CLEAN), endpoints=1.0,
    // damper=1.0 → 100 * 0.5 = 50. Down from the additive formula's 80 — patch-mode runs
    // and product runs that don't ship tests now read more honestly.
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0);
    assertEquals(50, score);
  }

  @Test
  void all_tests_pass_no_dampening_from_test_axis() {
    // 1 * 1 * 1 * 1 * 1 → 100
    int score = svc.computeVerifiedScore(true, 73, 73, "CLEAN", 0);
    assertEquals(100, score);
  }

  @Test
  void half_tests_pass_halves_score() {
    // 1 * 0.5 * 1 * 1 * 1 → 50. Half the tests broken means half the work is broken;
    // this is what the multiplicative shape gets right that the additive formula missed
    // (additive only knocked 10 points off here, surfacing 90/100 for half-broken work).
    int score = svc.computeVerifiedScore(true, 5, 10, "CLEAN", 0);
    assertEquals(50, score);
  }

  @Test
  void build_failure_collapses_score_to_zero() {
    // 0 * anything = 0. Was the central concern that motivated the multiplicative shape:
    // the old formula returned 60 for a CLEAN reviewer + failed build. A failed build
    // means literally nothing works; the score now reflects that.
    int score = svc.computeVerifiedScore(false, 0, 0, "CLEAN", 0);
    assertEquals(0, score);
  }

  @Test
  void build_failure_collapses_to_zero_even_with_passing_tests_and_endpoints() {
    // 0 * 1 * 1 * 1 * 1 → 0. No combination of other-axis perfection rescues a build fail.
    int score = svc.computeVerifiedScore(false, 100, 100, "CLEAN", 0, 100);
    assertEquals(0, score);
  }

  @Test
  void review_failed_strongly_damps_score() {
    // 1 * 1 * 0.2 (REVIEW_FAILED) * 1 * 1 → 20. Reviewer agent crashed, we know
    // nothing about issue state; the score reflects that uncertainty.
    int score = svc.computeVerifiedScore(true, 50, 50, "REVIEW_FAILED", 0);
    assertEquals(20, score);
  }

  @Test
  void unresolved_issues_significantly_damps_score() {
    // 1 * 1 * 0.4 (UNRESOLVED_ISSUES) * 1 * 1 → 40. Stronger damper than additive formula
    // which only knocked 15 points off a base 100 here.
    int score = svc.computeVerifiedScore(true, 50, 50, "UNRESOLVED_ISSUES", 0);
    assertEquals(40, score);
  }

  @Test
  void fixed_issues_lightly_damps_score() {
    // 1 * 1 * 0.85 (FIXED_ISSUES) * 1 * 1 → 85. Reviewer fixed problems; the run
    // is fundamentally sound but earned a small damper for needing reviewer intervention.
    int score = svc.computeVerifiedScore(true, 50, 50, "FIXED_ISSUES", 0);
    assertEquals(85, score);
  }

  @Test
  void null_verdict_neutral_half_credit() {
    // Reviewer didn't run / unknown verdict — half-credit. Was 90 in additive; now 50.
    // Honesty: when we don't know, we don't credit.
    int score = svc.computeVerifiedScore(true, 5, 5, null, 0);
    assertEquals(50, score);
  }

  // ── Endpoint coverage scaling (no more cliff) ──────────────────────────

  @Test
  void endpoint_coverage_linear_no_cliff_at_95() {
    // 100% → 1.0 factor; 95% → 0.95; 94% → 0.94. Identical formula, no jump.
    int s100 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 100);
    int s95 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 95);
    int s94 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 94);
    int s50 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 50);
    int s0 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 0);

    // 100%: 1*1*1*1.0*1 = 100
    assertEquals(100, s100);
    // 95%: 1*1*1*0.95*1 = 95
    assertEquals(95, s95);
    // 94%: 1*1*1*0.94*1 = 94 (no cliff — this is the whole point)
    assertEquals(94, s94);
    // 50%: 1*1*1*0.50*1 = 50
    assertEquals(50, s50);
    // 0%: factor 0 → score 0 (build/tests/reviewer all perfect, but no endpoints
    // delivered means the spec is unfulfilled — multiplicative collapse is correct)
    assertEquals(0, s0);

    // Pin the no-cliff property explicitly:
    assertTrue(
        Math.abs(s95 - s94) <= 2,
        "95% vs 94% endpoint coverage must NOT have a >2pt jump (was 0→10 in additive)");
  }

  @Test
  void endpoint_coverage_null_treated_as_not_applicable() {
    // CLI / library spec — no HTTP endpoints to count. Factor = 1.0 (no damping).
    int score = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, null);
    assertEquals(100, score);
  }

  // ── Critical-issue damper (replaces 5×N additive penalty) ──────────────

  @Test
  void unfixed_critical_lightly_damps_floored_at_half() {
    // 1 critical → damper 0.95; 5 → 0.75; 10 → 0.5 (floor); 20 → still 0.5.
    int s0 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0);
    int s1 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 1);
    int s5 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 5);
    int s10 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 10);
    int s20 = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 20);

    assertEquals(100, s0);
    assertEquals(95, s1);
    assertEquals(75, s5);
    assertEquals(50, s10);
    // Floor at 50 — beyond 10 unfixed criticals, the verdict / build factors should be
    // dragging the score anyway; this damper isn't meant to bottom out the number.
    assertEquals(50, s20);
  }

  @Test
  void clamped_to_zero_for_failed_build_plus_review_failed() {
    // Defence in depth — even with a 100-critical penalty in the old formula, the new
    // shape's primary factor (buildFactor=0) already collapses to 0 first.
    int score = svc.computeVerifiedScore(false, 0, 0, "REVIEW_FAILED", 100);
    assertEquals(0, score);
  }

  // ── Audit-flagged regression cases (now read sensibly) ─────────────────

  @Test
  void run_e48399bf_regression_case() {
    // The strict review case: build pass, no tests, CLEAN reviewer.
    // Additive: 80 (40+20+0+20). Multiplicative: 50 (1×0.5×1×1×1).
    // The multiplicative read is more honest — a CLEAN reviewer + no tests ≠ "verified
    // 80%". The v3 UI's testsTotal>0 headline gate hides the badge for this shape;
    // the API still returns a number for telemetry.
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0);
    assertEquals(50, score);
  }

  @Test
  void run_0444c95f_audit_regression_case_no_tests_no_credit() {
    // Run 0444c95f: build pass + no tests + FIXED_ISSUES + 100% endpoints + 0 critical.
    // Additive: 85 (40+20+0+15+10).
    // Multiplicative: 1×0.5×0.85×1.0×1 = 0.425 → 43. The "no tests" damper does the
    // honest work here — endpoint coverage at 100% can't compensate for the run not
    // verifying the implementation behaves correctly.
    int score = svc.computeVerifiedScore(true, 0, 0, "FIXED_ISSUES", 0, 100);
    assertEquals(43, score);
  }
}
