package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

/**
 * PR-E Phase A (2026-05-13) — pins the additive {@code computeRubricScore} contract.
 *
 * <p>The user reported: "scoring is broken, every run shows 0/100." Root cause was the
 * multiplicative {@code verifiedScore} cliff: any single zero factor (failed build, REVIEW_FAILED)
 * collapsed the whole headline. The rubric awards points dimension-by-dimension so a partial win
 * still reads as a partial score.
 *
 * <p>Each test documents the points breakdown so the formula stays transparent under future
 * re-tuning. The rubric runs alongside {@code verifiedScore}; the dashboard headline (Phase B)
 * picks whichever is more informative per run.
 */
class ValidationReportServiceRubricScoreTest {

  private final ValidationReportService svc =
      new ValidationReportService(
          null, null, null, null, null, null, mock(FeatureFlagService.class));

  @Test
  void perfect_run_scores_100() {
    // build=25 + tests=20 + reviewer=20 + endpoints=15 + damper=10 + quality=10 = 100
    int score = svc.computeRubricScore(true, 50, 50, "CLEAN", 0, 100);
    assertEquals(100, score);
  }

  // Build-failure cliff (the bug we're fixing)

  @Test
  void build_failed_does_not_collapse_to_zero() {
    // Pre-PR-E, verifiedScore = 0 on this exact input (build=0.0 → product = 0).
    // Rubric: build=0 + tests=20 + reviewer=20 + endpoints=15 + damper=10 + quality=10 = 75
    int score = svc.computeRubricScore(false, 50, 50, "CLEAN", 0, 100);
    assertEquals(75, score);
    assertTrue(score > 50, "Build-failure-only run still gets ≥50 points in the rubric");
  }

  @Test
  void review_failed_does_not_collapse_to_zero() {
    // Rubric: build=25 + tests=20 + reviewer=0 + endpoints=15 + damper=10 + quality=0 = 70
    int score = svc.computeRubricScore(true, 50, 50, "REVIEW_FAILED", 0, 100);
    assertEquals(70, score);
  }

  @Test
  void no_tests_captured_gives_neutral_10_points() {
    // testsTotal=0 → 10 (half of 20). Same logic as the multiplicative formula's 0.5 neutral.
    int score = svc.computeRubricScore(true, 0, 0, "CLEAN", 0, 100);
    // build=25 + tests=10 + reviewer=20 + endpoints=15 + damper=10 + quality=10 = 90
    assertEquals(90, score);
  }

  @Test
  void half_tests_passed_gives_10_points() {
    // 5/10 → 10 pts (20 × 0.5)
    int score = svc.computeRubricScore(true, 5, 10, "CLEAN", 0, 100);
    // build=25 + tests=10 + reviewer=20 + endpoints=15 + damper=10 + quality=10 = 90
    assertEquals(90, score);
  }

  @Test
  void unfixed_criticals_drop_damper_one_per_critical() {
    // 3 criticals → damper = max(0, 10 - 3) = 7
    int score = svc.computeRubricScore(true, 50, 50, "CLEAN", 3, 100);
    // build=25 + tests=20 + reviewer=20 + endpoints=15 + damper=7 + quality=10 = 97
    assertEquals(97, score);
  }

  @Test
  void many_criticals_floor_at_zero_damper() {
    // 50 criticals → damper = 0 (floor)
    int score = svc.computeRubricScore(true, 50, 50, "CLEAN", 50, 100);
    // build=25 + tests=20 + reviewer=20 + endpoints=15 + damper=0 + quality=10 = 90
    assertEquals(90, score);
  }

  @Test
  void null_endpoint_coverage_gives_full_15_points() {
    // Non-HTTP spec (CLI / library) → coverage signal n/a → 15 pts
    int score = svc.computeRubricScore(true, 50, 50, "CLEAN", 0, null);
    assertEquals(100, score);
  }

  @Test
  void zero_endpoint_coverage_gives_zero_points() {
    // 0% coverage → 0 pts of 15
    int score = svc.computeRubricScore(true, 50, 50, "CLEAN", 0, 0);
    // build=25 + tests=20 + reviewer=20 + endpoints=0 + damper=10 + quality=10 = 85
    assertEquals(85, score);
  }

  @Test
  void reviewer_fixed_issues_gives_15_plus_10_quality() {
    int score = svc.computeRubricScore(true, 50, 50, "FIXED_ISSUES", 0, 100);
    // build=25 + tests=20 + reviewer=15 + endpoints=15 + damper=10 + quality=10 = 95
    assertEquals(95, score);
  }

  @Test
  void reviewer_unresolved_issues_gives_5_plus_10_quality() {
    int score = svc.computeRubricScore(true, 50, 50, "UNRESOLVED_ISSUES", 0, 100);
    // build=25 + tests=20 + reviewer=5 + endpoints=15 + damper=10 + quality=10 = 85
    assertEquals(85, score);
  }

  @Test
  void null_reviewer_gives_neutral_10_plus_10_quality() {
    int score = svc.computeRubricScore(true, 50, 50, null, 0, 100);
    // build=25 + tests=20 + reviewer=10 + endpoints=15 + damper=10 + quality=10 = 90
    assertEquals(90, score);
  }

  @Test
  void worst_case_floors_at_zero() {
    // Build failed, no tests, REVIEW_FAILED, 0% endpoints, 100 criticals
    int score = svc.computeRubricScore(false, 0, 100, "REVIEW_FAILED", 100, 0);
    // build=0 + tests=0 + reviewer=0 + endpoints=0 + damper=0 + quality=0 = 0
    assertEquals(0, score);
  }
}
