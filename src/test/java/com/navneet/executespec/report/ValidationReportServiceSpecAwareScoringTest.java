package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

/**
 * PR-R (2026-05-14) — pins the spec-aware scoring contract on both formulas.
 *
 * <p>The bug we're fixing: pre-PR-R, every run with {@code testsTotal=0} got a hardcoded test
 * factor of 0.5 (multiplicative) or 10 points (rubric), which capped a perfect-otherwise score at
 * 50 / 90 on specs that legitimately required no tests. User-reported case: smoke-test "hello.py"
 * run with build=PASSED + reviewer=CLEAN + accuracy testCoverageLevel=GOOD got verifiedScore=50
 * even though every concrete signal said "perfect."
 *
 * <p>The fix anchors the test factor on the AccuracyAssessment {@code testCoverageLevel} signal
 * when no concrete tests were captured. GOOD/COMPREHENSIVE means "coverage matches what the spec
 * implied was needed" — full credit. NONE means tests were needed but not written — real damper.
 * Legacy null behaviour preserved (existing tests pass unchanged).
 */
class ValidationReportServiceSpecAwareScoringTest {

  private final ValidationReportService svc =
      new ValidationReportService(
          null, null, null, null, null, null, mock(FeatureFlagService.class));

  @Test
  void testFactor_comprehensive_givesFullCredit() {
    assertEquals(1.0, ValidationReportService.testFactorFromCoverageLevel("COMPREHENSIVE"));
  }

  @Test
  void testFactor_good_givesFullCredit() {
    assertEquals(1.0, ValidationReportService.testFactorFromCoverageLevel("GOOD"));
  }

  @Test
  void testFactor_partial_givesPartial() {
    assertEquals(0.7, ValidationReportService.testFactorFromCoverageLevel("PARTIAL"));
  }

  @Test
  void testFactor_none_givesRealDamper() {
    assertEquals(0.3, ValidationReportService.testFactorFromCoverageLevel("NONE"));
  }

  @Test
  void testFactor_null_givesLegacyNeutral() {
    assertEquals(0.5, ValidationReportService.testFactorFromCoverageLevel(null));
  }

  @Test
  void testFactor_unknownValue_givesLegacyNeutral() {
    // Forward-compat: a new value the AccuracyAssessment prompt invents falls back to neutral
    // instead of throwing or being silently dropped to 0.
    assertEquals(0.5, ValidationReportService.testFactorFromCoverageLevel("STELLAR"));
  }

  @Test
  void testFactor_caseInsensitive() {
    assertEquals(1.0, ValidationReportService.testFactorFromCoverageLevel("good"));
    assertEquals(1.0, ValidationReportService.testFactorFromCoverageLevel("Good"));
    assertEquals(0.3, ValidationReportService.testFactorFromCoverageLevel("none"));
  }

  // Multiplicative — the headline bug fix

  @Test
  void verifiedScore_helloWorld_spec_now_scores_100() {
    // The exact shape of the user-reported smoke run that motivated PR-R:
    //   build=PASS, testsTotal=0, reviewer=CLEAN, endpoints=null,
    //   accuracy.testCoverageLevel="GOOD" (spec didn't need tests)
    // Pre-PR-R: 100 * 1 * 0.5 * 1 * 1 * 1 = 50. Now: 100 * 1 * 1 * 1 * 1 * 1 = 100.
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "GOOD");
    assertEquals(100, score, "Hello-world spec with GOOD coverage should now score 100, not 50");
  }

  @Test
  void verifiedScore_helloWorld_spec_with_comprehensive_coverage_also_100() {
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "COMPREHENSIVE");
    assertEquals(100, score);
  }

  @Test
  void verifiedScore_spec_needed_tests_but_none_written_drops_score() {
    // Same other signals but testCoverageLevel=NONE → real damper.
    // 100 * 1 * 0.3 * 1 * 1 * 1 = 30
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "NONE");
    assertEquals(30, score);
  }

  @Test
  void verifiedScore_partial_coverage_70_factor() {
    // 100 * 1 * 0.7 * 1 * 1 * 1 = 70
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "PARTIAL");
    assertEquals(70, score);
  }

  @Test
  void verifiedScore_legacy_null_signal_preserves_pre_PR_R_behaviour() {
    // Existing test suite (pre-PR-R) relied on null defaulting to 0.5 — preserve that.
    int score = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null);
    assertEquals(50, score);
  }

  @Test
  void verifiedScore_testsTotal_positive_ignores_coverageLevel() {
    // When concrete tests ARE captured, the linear ratio wins — coverageLevel is only the
    // fallback. Avoids "GOOD coverage" gaslighting a flaky 3/10 test pass into 100.
    int score = svc.computeVerifiedScore(true, 3, 10, "CLEAN", 0, null, "COMPREHENSIVE");
    // 100 * 1 * 0.3 * 1 * 1 * 1 = 30 (testsRatio wins)
    assertEquals(30, score);
  }

  // Rubric — same fix, additive shape

  @Test
  void rubric_helloWorld_spec_now_scores_100() {
    // build=25 + tests=20 (GOOD via PR-R) + reviewer=20 + endpoints=15 + damper=10 + quality=10
    // = 100
    int score = svc.computeRubricScore(true, 0, 0, "CLEAN", 0, null, "GOOD");
    assertEquals(100, score);
  }

  @Test
  void rubric_spec_needed_tests_drops_test_slot() {
    // testCoverageLevel=NONE → rubricTestPoints = round(20 * 0.3) = 6
    // build=25 + tests=6 + reviewer=20 + endpoints=15 + damper=10 + quality=10 = 86
    int score = svc.computeRubricScore(true, 0, 0, "CLEAN", 0, null, "NONE");
    assertEquals(86, score);
  }

  @Test
  void rubric_legacy_null_preserves_neutral_10_pts() {
    int score = svc.computeRubricScore(true, 0, 0, "CLEAN", 0, null, null);
    // build=25 + tests=10 (neutral, 0.5 × 20) + reviewer=20 + endpoints=15 + damper=10 + quality=10
    // = 90
    assertEquals(90, score);
  }

  @Test
  void rubric_testsTotal_positive_ignores_coverageLevel() {
    // Same protection as multiplicative: concrete tests captured → ratio wins
    int score = svc.computeRubricScore(true, 3, 10, "CLEAN", 0, null, "COMPREHENSIVE");
    // build=25 + tests=6 (3/10 × 20) + reviewer=20 + endpoints=15 + damper=10 + quality=10 = 86
    assertEquals(86, score);
  }

  // Reality check: the user's exact smoke-run inputs

  @Test
  void smoke_run_anthropic_hello_py_scores_100_after_PR_R() {
    // The literal shape of the Anthropic smoke run that produced verifiedScore=50:
    //   finalBuildPassed=true, testsPassed=null/0, testsTotal=null/0,
    //   reviewerVerdict="CLEAN", endpointCoverage=null, unfixedCritical=0,
    //   testCoverageLevel="GOOD"
    int verified = svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "GOOD");
    int rubric = svc.computeRubricScore(true, 0, 0, "CLEAN", 0, null, "GOOD");
    assertEquals(100, verified, "Pre-PR-R the user saw 50 here; post-PR-R must be 100");
    assertEquals(100, rubric, "Rubric must also hit 100 on the same input");
    assertTrue(verified == rubric, "Both formulas agree on the perfect case");
  }
}
