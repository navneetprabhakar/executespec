package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict;

import org.junit.jupiter.api.Test;

/**
 * RL-2026-05-066 (verifyBoot) — pins that a deterministic boot verification feeds the BUILD scoring
 * slot. When a {@code bootVerification} is present it supersedes the compile-only signal (a
 * booting, health-responding app is a stronger BUILD signal than "compiles"; a failed boot is
 * honest BUILD information). A null {@code bootVerification} (the default — flag OFF) must
 * reproduce today's compile-only behaviour exactly, so production scores stay byte-identical until
 * verifyBoot is on.
 */
class ValidationReportServiceBootVerificationTest {

  private ValidationReportService svcAdditiveOn() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq("scoring.additive-v2"))).thenReturn(true);
    return new ValidationReportService(null, null, null, null, null, null, flags);
  }

  /** Additive OFF → the legacy multiplicative scorer runs. */
  private ValidationReportService svcMultiplicative() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(eq("scoring.additive-v2"))).thenReturn(false);
    return new ValidationReportService(null, null, null, null, null, null, flags);
  }

  private static BootVerification boot(Verdict v) {
    return new BootVerification(v, v == Verdict.HEALTHY, v.name());
  }

  // Scenario held constant across these cases: CODE delivered (4 files), reviewer CLEAN, no
  // criticals, no tests measured. Only the BUILD slot's source (compile vs boot verdict) varies.
  // weights: CODE 30 + BUILD 25 + REVIEWER 20 + CRIT 5 = 80 (TESTS slot dropped — none measured).
  // The evidence-safe scorer caps otherwise-perfect runs at 90 when the TESTS slot is unmeasured.

  @Test
  void null_boot_reproduces_compile_only_behaviour() {
    ValidationReportService svc = svcAdditiveOn();
    // No boot signal → finalBuildPassed drives the BUILD slot, exactly as before verifyBoot.
    assertEquals(90, svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4));
    assertEquals(69, svc.computeVerifiedScore(false, 0, 0, "CLEAN", 0, null, null, 4));
  }

  @Test
  void healthy_boot_gives_full_build_credit() {
    ValidationReportService svc = svcAdditiveOn();
    assertEquals(
        90, svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.HEALTHY)));
  }

  @Test
  void failed_boot_supersedes_a_green_compile() {
    ValidationReportService svc = svcAdditiveOn();
    // finalBuildPassed=true (it compiled) but the app never builds an image / never boots →
    // BUILD slot collapses to 0, identical to a red compile (69), NOT the green-compile 100.
    int score =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.BUILD_FAILED));
    assertEquals(69, score);
    assertEquals(score, svc.computeVerifiedScore(false, 0, 0, "CLEAN", 0, null, null, 4));
  }

  @Test
  void verdicts_grade_monotonically_down() {
    ValidationReportService svc = svcAdditiveOn();
    int healthy =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.HEALTHY));
    int unhealthy =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.UNHEALTHY));
    int timedOut =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.TIMED_OUT));
    int didNotStart =
        svc.computeVerifiedScore(
            true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.DID_NOT_START));
    int buildFailed =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.BUILD_FAILED));
    assertTrue(
        healthy > unhealthy
            && unhealthy > timedOut
            && timedOut > didNotStart
            && didNotStart > buildFailed,
        "boot verdicts must grade the BUILD slot monotonically down: "
            + healthy
            + " > "
            + unhealthy
            + " > "
            + timedOut
            + " > "
            + didNotStart
            + " > "
            + buildFailed);
    assertEquals(90, healthy);
    assertEquals(69, buildFailed);
  }

  @Test
  void unhealthy_boot_cannot_read_pass_even_with_everything_else_green() {
    // RR-296 — the exact reported gap: CODE delivered, compile green, ALL tests passing, CLEAN
    // review, no criticals, but the health probe fails (UNHEALTHY). Before the boot-aware trust
    // cap this computed to 89 — a passing headline above the 85 PASS threshold for a service that
    // does not actually come up. The cap now bounds any non-HEALTHY boot below PASS.
    ValidationReportService svc = svcAdditiveOn();
    int unhealthy =
        svc.computeVerifiedScore(true, 5, 5, "CLEAN", 0, null, null, 4, boot(Verdict.UNHEALTHY));
    assertTrue(unhealthy < 85, "UNHEALTHY boot must not read PASS (>=85); was " + unhealthy);
    // A HEALTHY boot on the same otherwise-perfect run keeps its full passing headline.
    int healthy =
        svc.computeVerifiedScore(true, 5, 5, "CLEAN", 0, null, null, 4, boot(Verdict.HEALTHY));
    assertTrue(healthy >= 85, "HEALTHY boot on an all-green run should still read PASS");
    assertTrue(healthy > unhealthy);
  }

  @Test
  void multiplicative_path_is_also_boot_aware() {
    ValidationReportService svc = svcMultiplicative();
    // A failed boot zeroes buildFactor → the whole multiplicative product collapses to 0, even
    // though the compile passed. Healthy boot keeps the green-compile score.
    int healthy =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.HEALTHY));
    int failed =
        svc.computeVerifiedScore(true, 0, 0, "CLEAN", 0, null, null, 4, boot(Verdict.BUILD_FAILED));
    assertEquals(0, failed, "BUILD_FAILED boot → buildFactor 0 → multiplicative score 0");
    assertTrue(healthy > failed, "healthy boot must outscore a failed boot on the legacy path too");
  }
}
