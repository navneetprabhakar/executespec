package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;

/**
 * 2026-05-19 — additive scoring rebuild. Pins the weighted-additive shape that replaces the
 * 2026-05-08 multiplicative formula.
 *
 * <p>The weight table (total = 100):
 *
 * <ul>
 *   <li>CODE_DELIVERED (30) — primary factor; 0 forces verifiedScore = 0
 *   <li>BUILD (25) — 100 / 0
 *   <li>TESTS (20) — pass/total ratio or damped LLM-judged grade
 *   <li>REVIEWER (20) — CLEAN=100, FIXED_ISSUES=85, UNRESOLVED=40, REVIEW_FAILED=10
 *   <li>CRITICALS (5) — 100 − 20×N, floor 0
 * </ul>
 *
 * <p>When a signal is "not measured", it's dropped from both the numerator and denominator so the
 * headline reflects only what fired. Tests below document each branch.
 */
class ValidationReportServiceAdditiveScoreTest {

  /**
   * Build a service with the additive flag forced ON via a Mockito stub. The other constructor args
   * don't matter for the pure-function tests in this class.
   */
  private ValidationReportService svcAdditiveOn() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq("scoring.additive-v2"))).thenReturn(true);
    return new ValidationReportService(null, null, null, null, null, null, flags);
  }

  /** Additive + the RL-2026-05-041 anti-gaming guards (A+B+E) both ON. */
  private ValidationReportService svcGuardsOn() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq("scoring.additive-v2"))).thenReturn(true);
    when(flags.isEnabled(eq("scoring.gaming-guards"))).thenReturn(true);
    return new ValidationReportService(null, null, null, null, null, null, flags);
  }

  // RL-2026-05-041 anti-gaming guards (audit #7)

  @Test
  void guards_tighten_a_gamed_run_trivial_tests_plus_partial_endpoints() {
    // All-passing tests but the LLM judged coverage NONE (classic "assert-true" gaming), and only
    // half the spec's endpoints are actually exposed. Clean build + CLEAN review.
    int unguarded = svcAdditiveOn().computeVerifiedScore(true, 5, 5, "CLEAN", 0, 50, "NONE", 4);
    int guarded = svcGuardsOn().computeVerifiedScore(true, 5, 5, "CLEAN", 0, 50, "NONE", 4);
    assertEquals(100, unguarded, "without guards a gamed run still reads 100");
    assertTrue(
        guarded < unguarded,
        "guards must tighten the gamed score (TESTS capped at NONE + CODE blended with endpoints)."
            + " Got guarded="
            + guarded);
    assertEquals(
        79,
        guarded,
        "expected 79 = CODE 75 + BUILD 100 + TESTS 30 + REVIEWER 100 + CRIT 100"
            + " weighted over 100. Got: "
            + guarded);
  }

  @Test
  void guards_do_not_touch_a_clean_run() {
    // Endpoints fully covered + COMPREHENSIVE test coverage + all pass — a legitimately strong run.
    // Guards are tighten-only, so this must stay 100.
    int guarded =
        svcGuardsOn().computeVerifiedScore(true, 10, 10, "CLEAN", 0, 100, "COMPREHENSIVE", 8);
    assertEquals(
        100, guarded, "a clean run with full endpoint coverage must be unaffected by guards");
  }

  @Test
  void no_files_generated_floors_score_to_zero() {
    int score = svcAdditiveOn().computeVerifiedScore(true, 50, 50, "CLEAN", 0, null, null, 0);
    assertEquals(0, score, "When the agent shipped 0 files the headline must be 0.");
  }

  @Test
  void files_generated_unknown_does_not_apply_floor() {
    // -1 filesGenerated = unknown (legacy 7-arg call sites). Should compute normally.
    int score = svcAdditiveOn().computeVerifiedScore(true, 50, 50, "CLEAN", 0, null, null, -1);
    assertTrue(score >= 90, "Unknown filesGenerated must not zero the score. Got: " + score);
  }

  // Reference run: build OK, full tests, CLEAN reviewer, 0 criticals

  @Test
  void perfect_run_is_100() {
    // weights: 30 * 100 (code) + 25 * 100 (build) + 20 * 100 (tests) + 20 * 100 (reviewer) + 5 *
    // 100 (criticals)
    //        = 10000 / 100 = 100
    int score = svcAdditiveOn().computeVerifiedScore(true, 50, 50, "CLEAN", 0, null, null, 4);
    assertEquals(100, score);
  }

  // BUILD failure no longer cliffs to 0

  @Test
  void failed_build_keeps_partial_credit() {
    // Multiplicative: this would be 0 (build factor = 0). Additive: build slot earns 0/25 but
    // other slots still count. With CLEAN reviewer + tests pass + 0 criticals:
    //   30*100 (code) + 25*0 (build) + 20*100 (tests) + 20*100 (reviewer) + 5*100 (criticals)
    //   = 3000 + 0 + 2000 + 2000 + 500 = 7500 / 100 = 75
    int score = svcAdditiveOn().computeVerifiedScore(false, 50, 50, "CLEAN", 0, null, null, 4);
    assertEquals(75, score);
  }

  @Test
  void half_tests_pass_dampens_test_slot_and_caps_headline() {
    // build OK + 5/10 tests + CLEAN + 0 crit + 4 files:
    //   30*100 + 25*100 + 20*50 + 20*100 + 5*100 = 3000+2500+1000+2000+500 = 9000/100 = 90
    // Trust cap: any concrete test failure caps the headline at 85.
    int score = svcAdditiveOn().computeVerifiedScore(true, 5, 10, "CLEAN", 0, null, null, 4);
    assertEquals(85, score);
  }

  // TESTS: no real tests, LLM grades

  @Test
  void testsTotal_zero_with_GOOD_coverage_caps_test_slot_at_70() {
    // Without real tests, LLM grade GOOD = 70 (capped, not 100). Build OK + CLEAN + 0 crit:
    //   30*100 + 25*100 + 20*70 + 20*100 + 5*100 = 3000+2500+1400+2000+500 = 9400/100 = 94
    int score = svcAdditiveOn().computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "GOOD", 4);
    assertEquals(94, score);
  }

  @Test
  void testsTotal_zero_with_PARTIAL_coverage_uses_40() {
    // LLM grade PARTIAL = 40. Build OK + CLEAN + 0 crit:
    //   30*100 + 25*100 + 20*40 + 20*100 + 5*100 = 3000+2500+800+2000+500 = 8800/100 = 88
    int score = svcAdditiveOn().computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "PARTIAL", 4);
    assertEquals(88, score);
  }

  @Test
  void testsTotal_zero_with_no_coverage_signal_drops_test_slot() {
    // testsTotal=0 AND testCoverageLevel=null → drop test slot entirely.
    // Denominator becomes 30 + 25 + 20 + 5 = 80. Numerator: 30*100 + 25*100 + 20*100 + 5*100 =
    // 8000.
    // 8000 / 80 = 100.
    int score = svcAdditiveOn().computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4);
    assertEquals(100, score, "Unmeasured tests must not penalize.");
  }

  @Test
  void testsTotal_zero_with_UNKNOWN_coverage_damps_to_50_not_dropped() {
    // RL-2026-05-003: UNKNOWN must NOT drop the slot (which would give 100 on a run
    // with no confirmed tests). It damps to 50:
    //   30*100 + 25*100 + 20*50 + 20*100 + 5*100 = 3000+2500+1000+2000+500 = 9000/100 = 90
    int score = svcAdditiveOn().computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "UNKNOWN", 4);
    assertEquals(90, score, "UNKNOWN coverage damps the slot; it must not be dropped.");
  }

  @Test
  void review_failed_caps_headline() {
    // Multiplicative: REVIEW_FAILED → 0.2 × everything → ~20. Additive: only the 20-pt reviewer
    // slot earns 10/100 of its weight = 20 weighted contribution out of 2000 max.
    //   30*100 + 25*100 + 20*100 + 20*10 + 5*100 = 3000+2500+2000+200+500 = 8200/100 = 82
    // Trust cap: a failed reviewer pass cannot credibly headline above 70.
    int score =
        svcAdditiveOn().computeVerifiedScore(true, 50, 50, "REVIEW_FAILED", 0, null, null, 4);
    assertEquals(70, score);
  }

  @Test
  void unresolved_issues_caps_headline() {
    int score =
        svcAdditiveOn().computeVerifiedScore(true, 50, 50, "UNRESOLVED_ISSUES", 0, null, null, 4);
    assertEquals(80, score);
  }

  @Test
  void single_critical_caps_headline() {
    // 1 critical → criticals slot scores 80 (100 − 20).
    //   30*100 + 25*100 + 20*100 + 20*100 + 5*80 = 3000+2500+2000+2000+400 = 9900/100 = 99
    // Trust cap: any unresolved critical caps the headline at 85.
    int score = svcAdditiveOn().computeVerifiedScore(true, 50, 50, "CLEAN", 1, null, null, 4);
    assertEquals(85, score);
  }

  @Test
  void five_criticals_zero_criticals_slot_and_caps_headline() {
    // 5 criticals → 100 − 100 = 0 for the criticals slot.
    //   30*100 + 25*100 + 20*100 + 20*100 + 5*0 = 3000+2500+2000+2000+0 = 9500/100 = 95
    // Trust cap: 3+ unresolved criticals cap the headline at 70.
    int score = svcAdditiveOn().computeVerifiedScore(true, 50, 50, "CLEAN", 5, null, null, 4);
    assertEquals(70, score);
  }

  @Test
  void explicit_no_test_coverage_caps_headline() {
    int score = svcAdditiveOn().computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "NONE", 4);
    assertEquals(75, score);
  }

  @Test
  void unknown_test_coverage_caps_headline_at_90() {
    int score = svcAdditiveOn().computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, "UNKNOWN", 4);
    assertEquals(90, score);
  }

  @Test
  void flag_off_falls_back_to_multiplicative() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq("scoring.additive-v2"))).thenReturn(false);
    ValidationReportService svc =
        new ValidationReportService(null, null, null, null, null, null, flags);
    // Multiplicative: failed build → 0.
    int score = svc.computeVerifiedScore(false, 50, 50, "CLEAN", 0, null, null, 4);
    assertEquals(0, score);
  }
}
