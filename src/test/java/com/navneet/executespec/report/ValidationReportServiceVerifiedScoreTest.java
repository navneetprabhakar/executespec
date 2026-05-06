package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

/**
 * R-132 regression guard. Pins the verified-score formula behaviour the strict UX review
 * (2026-04-27) and greenfield redesign §4.5 depend on.
 *
 * <p>The bug: previously, when {@code testsTotal == 0}, the formula awarded a "neutral" 10 points
 * on the test axis. That gave runs with NO test coverage a free 10 points, surfacing scores like
 * {@code verifiedScore=65} for runs whose own LLM accuracy probe said {@code accuracyScore=5}. The
 * fix: zero on the test axis when no tests exist; the v3 UI also gates the badge display on {@code
 * testsTotal>0}.
 */
class ValidationReportServiceVerifiedScoreTest {

  private final ValidationReportService svc =
      new ValidationReportService(
          null, null, null, null, null, null, mock(FeatureFlagService.class));

  @Test
  void zero_tests_earns_zero_test_bonus() {
    // base=40 + buildBonus=20 + testBonus=0 + verdictBonus(CLEAN)=20 - penalty=0 = 80
    int score =
        svc.computeVerifiedScore(
            /*build*/ true, /*passed*/ 0, /*total*/ 0, "CLEAN", /*unfixedCritical*/ 0);
    assertEquals(80, score, "no tests must not earn a free 10 points");
  }

  @Test
  void all_tests_pass_earns_full_test_bonus() {
    // base=40 + buildBonus=20 + testBonus=20 + verdictBonus(CLEAN)=20 = 100
    int score = svc.computeVerifiedScore(true, 73, 73, "CLEAN", 0);
    assertEquals(100, score);
  }

  @Test
  void half_tests_pass_earns_half_test_bonus() {
    // base=40 + buildBonus=20 + testBonus=10 + verdictBonus(CLEAN)=20 = 90
    int score = svc.computeVerifiedScore(true, 5, 10, "CLEAN", 0);
    assertEquals(90, score);
  }

  @Test
  void build_failed_kills_build_bonus() {
    // base=40 + buildBonus=0 + testBonus=0 + verdictBonus(CLEAN)=20 = 60
    int score = svc.computeVerifiedScore(false, 0, 0, "CLEAN", 0);
    assertEquals(60, score);
  }

  @Test
  void unfixed_critical_penalty_caps_at_zero() {
    // base=40 + buildBonus=0 + testBonus=0 + verdictBonus(REVIEW_FAILED)=0 - penalty(20*5=100) =
    // -60 → 0
    int score = svc.computeVerifiedScore(false, 0, 0, "REVIEW_FAILED", 20);
    assertEquals(0, score);
  }

  @Test
  void score_caps_at_one_hundred() {
    int score = svc.computeVerifiedScore(true, 100, 100, "CLEAN", 0);
    assertEquals(100, score);
  }

  @Test
  void null_verdict_yields_neutral_ten_points() {
    // base=40 + buildBonus=20 + testBonus=20 + verdictBonus(null)=10 = 90
    int score = svc.computeVerifiedScore(true, 5, 5, null, 0);
    assertEquals(90, score);
  }

  @Test
  void run_e48399bf_regression_case() {
    // The exact case the strict review caught: build passed, no tests,
    // CLEAN reviewer. Old formula returned 90 (40+20+10+20). New formula
    // returns 80 (40+20+0+20). The v3 UI further gates the badge to
    // hide it entirely when testsTotal==0; this test pins the backend
    // half of the contract.
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0);
    assertEquals(80, score);
  }

  // ── P1.2 (GA-readiness 2026-05-06): endpoint coverage overload ──────────

  @Test
  void endpoint_coverage_at_100_earns_ten_point_bonus() {
    // base=40 + buildBonus=20 + testBonus=0 + verdictBonus(FIXED_ISSUES)=15 + endpointBonus=10 = 85
    int score = svc.computeVerifiedScore(true, 0, 0, "FIXED_ISSUES", 0, 100);
    assertEquals(85, score);
  }

  @Test
  void endpoint_coverage_below_threshold_no_bonus() {
    // 94% < 95% threshold → no bonus
    int score = svc.computeVerifiedScore(true, 0, 0, "FIXED_ISSUES", 0, 94);
    assertEquals(75, score);
  }

  @Test
  void endpoint_coverage_at_threshold_earns_bonus() {
    // 95% == threshold → bonus
    int score = svc.computeVerifiedScore(true, 0, 0, "FIXED_ISSUES", 0, 95);
    assertEquals(85, score);
  }

  @Test
  void endpoint_coverage_null_no_bonus() {
    // Non-API project (no endpointCoverage) → no bonus
    int score = svc.computeVerifiedScore(true, 0, 0, "FIXED_ISSUES", 0, null);
    assertEquals(75, score);
  }

  @Test
  void endpoint_coverage_zero_no_bonus() {
    // Endpoint coverage block exists but 0% delivered (legitimate failure) → no bonus
    int score = svc.computeVerifiedScore(false, 0, 0, "FIXED_ISSUES", 0, 0);
    assertEquals(55, score);
  }

  @Test
  void run_0444c95f_audit_regression_case() {
    // The audit's mystery -25 gap reproduction. Run 0444c95f had:
    // - finalBuildPassed=true (+20)
    // - testsTotal=0 (+0)
    // - reviewerVerdict=FIXED_ISSUES (+15)
    // - endpointCoverage.coveragePercent=100 (+10)
    // - unfixedCritical=0 (synthesised accuracy issues filtered out by countUnfixedCritical)
    // Expected: 40 + 20 + 0 + 15 + 10 - 0 = 85.
    // Pre-fix the run scored 50 because the LLM's read-budget bug emitted 5 SYNTH-ACC-MISS-* /
    // SYNTH-ACC-EP-* CRITICAL issues that penalised 5 × 5 = 25 points.
    int score = svc.computeVerifiedScore(true, 0, 0, "FIXED_ISSUES", 0, 100);
    assertEquals(85, score);
  }

  @Test
  void perfect_run_caps_at_100_with_endpoint_bonus() {
    // base=40 + build=20 + tests=20 + CLEAN=20 + endpointBonus=10 = 110 → clamp 100
    int score = svc.computeVerifiedScore(true, 50, 50, "CLEAN", 0, 100);
    assertEquals(100, score);
  }
}
