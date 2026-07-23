package com.navneet.executespec.report;

import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.BootVerification;

/**
 * Pure, provider-independent score calculator used by validation-report orchestration.
 *
 * <p>This class deliberately has no Spring, persistence, or LLM dependencies. Keeping score
 * arithmetic here makes the scoring contract reusable by replay/backfill jobs and independently
 * testable without constructing the report service.
 */
final class ValidationReportScoreCalculator {

  /**
   * CODE_DELIVERED credit when the anti-gaming guards are on but no deterministic endpoint evidence
   * exists (RR-1648). Midpoint of the guarded range {@code [50, 100]} — the same credit a 50%
   * coverage reading earns — so an unmeasured slot is neither zeroed nor treated as full delivery.
   */
  private static final double CODE_DELIVERED_UNMEASURED = 75;

  /**
   * TESTS-slot cap when the guards are on and the coverage judgment is missing or unrecognised
   * (RR-1648). Below the full 100 so a trivially-passing suite cannot reach the top of the slot on
   * an absent judgment, above the explicit PARTIAL cap because absence is not a negative finding.
   */
  private static final double TESTS_UNMEASURED_CAP = 85;

  private ValidationReportScoreCalculator() {}

  static int additive(
      Boolean buildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdict,
      int unfixedCriticals,
      String coverageLevel,
      int filesGenerated,
      Integer endpointCoverage,
      boolean gamingGuards,
      BootVerification bootVerification) {
    if (filesGenerated >= 0 && filesGenerated == 0) return 0;

    double weighted = 0;
    double weight = 0;
    if (filesGenerated >= 0) {
      double code = 100;
      if (gamingGuards) {
        // RR-1648 — the endpoint tie-in used to fire only when endpoint evidence existed, so any
        // run the accuracy pass classified non-HTTP (or for which it emitted no endpoint block)
        // collected the full 30-point slot at 100 purely for having written files. Absent evidence
        // is not evidence of delivery: an unmeasured slot gets the midpoint of the measured range
        // (the same value a 50% coverage reading would earn) instead of full credit.
        code =
            endpointCoverage != null
                ? 50 + 0.5 * clamp(endpointCoverage, 0, 100)
                : CODE_DELIVERED_UNMEASURED;
      }
      weighted += 30 * code;
      weight += 30;
    }
    if (bootVerification != null) {
      weighted += 25 * ValidationReportScorePolicy.bootSlotScore(bootVerification.verdict());
      weight += 25;
    } else if (buildPassed != null) {
      weighted += 25 * (Boolean.TRUE.equals(buildPassed) ? 100 : 0);
      weight += 25;
    }
    if (testsTotal > 0) {
      double tests = 100 * clamp((double) testsPassed / Math.max(1, testsTotal), 0, 1);
      if (gamingGuards) {
        // RR-1648 — an unknown / unrecognised coverage judgment used to fall through to an
        // uncapped 100, so an always-passing trivial suite scored full marks whenever the LLM
        // failed to emit a level it recognised. Only an explicit GOOD/COMPREHENSIVE judgment
        // lifts the cap; everything else (including null and unrecognised values) is unmeasured.
        tests =
            Math.min(
                tests,
                coverageLevel == null
                    ? TESTS_UNMEASURED_CAP
                    : switch (coverageLevel) {
                      case "NONE" -> 30;
                      case "PARTIAL" -> 70;
                      case "GOOD", "COMPREHENSIVE" -> 100;
                      default -> TESTS_UNMEASURED_CAP;
                    });
      }
      weighted += 20 * tests;
      weight += 20;
    } else if (coverageLevel != null) {
      Double tests =
          switch (coverageLevel) {
            case "GOOD", "COMPREHENSIVE" -> 70.0;
            case "PARTIAL" -> 40.0;
            case "UNKNOWN" -> 50.0;
            case "NONE" -> 10.0;
            default -> null;
          };
      if (tests != null) {
        weighted += 20 * tests;
        weight += 20;
      }
    }
    if (reviewerVerdict != null) {
      weighted += 20 * reviewerScore(reviewerVerdict);
      weight += 20;
    }
    weighted += 5 * Math.max(0, 100 - 20 * Math.max(0, unfixedCriticals));
    weight += 5;
    if (weight <= 0) return 0;
    int raw = (int) clamp(Math.round(weighted / weight), 0, 100);
    return Math.min(
        raw,
        trustCap(
            raw,
            buildPassed,
            testsPassed,
            testsTotal,
            reviewerVerdict,
            unfixedCriticals,
            coverageLevel,
            bootVerification));
  }

  static int multiplicative(
      Boolean buildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdict,
      int unfixedCriticals,
      Integer endpointCoverage,
      String coverageLevel,
      BootVerification bootVerification) {
    double build =
        bootVerification != null
            ? ValidationReportScorePolicy.bootSlotScore(bootVerification.verdict()) / 100.0
            : (Boolean.TRUE.equals(buildPassed) ? 1 : 0);
    double tests =
        testsTotal <= 0
            ? ValidationReportScorePolicy.testFactorFromCoverageLevel(coverageLevel)
            : clamp((double) testsPassed / Math.max(1, testsTotal), 0, 1);
    double reviewer = reviewerVerdict == null ? .5 : reviewerFactor(reviewerVerdict);
    // RR-1650 — an unmeasured endpoint signal is deliberately neutral (factor 1.0) rather than
    // scored. This path is multiplicative and has no denominator to drop the slot from, so any
    // value below 1.0 would penalise every non-HTTP spec for a dimension that does not apply to
    // it. That mirrors the additive path, which likewise drops the slot when the signal is absent.
    // The neutral factor is safe only because the trust cap below now constrains the result.
    double endpoint = endpointCoverage == null ? 1 : clamp(endpointCoverage / 100.0, 0, 1);
    if ("CLEAN".equals(reviewerVerdict) || "FIXED_ISSUES".equals(reviewerVerdict)) {
      endpoint = Math.max(endpoint, reviewer);
    }
    if (testsTotal <= 0
        && ("CLEAN".equals(reviewerVerdict) || "FIXED_ISSUES".equals(reviewerVerdict))
        && endpointCoverage != null
        && endpointCoverage >= 95) {
      tests = Math.max(tests, reviewer);
    }
    double criticalDamper = Math.max(.5, 1 - .05 * Math.max(0, unfixedCriticals));
    int raw =
        (int) clamp(Math.round(100 * build * tests * reviewer * endpoint * criticalDamper), 0, 100);
    // RL-2026-06-026 trust caps applied to the legacy path too (RR-1650). This formula is the
    // documented rollback for `scoring.additive-v2`; without the cap, flipping that flag off also
    // silently removed every "this headline is not credible above N" constraint.
    return Math.min(
        raw,
        trustCap(
            raw,
            buildPassed,
            testsPassed,
            testsTotal,
            reviewerVerdict,
            unfixedCriticals,
            coverageLevel,
            bootVerification));
  }

  static int rubric(
      Boolean buildPassed,
      int testsPassed,
      int testsTotal,
      String reviewerVerdict,
      int unfixedCriticals,
      Integer endpointCoverage,
      String coverageLevel) {
    int total = Boolean.TRUE.equals(buildPassed) ? 25 : 0;
    total +=
        testsTotal <= 0
            ? ValidationReportScorePolicy.rubricTestPointsFromCoverageLevel(coverageLevel)
            : (int) Math.round(20 * clamp((double) testsPassed / Math.max(1, testsTotal), 0, 1));
    total += reviewerVerdict == null ? 10 : (int) (reviewerScore(reviewerVerdict) * .2);
    total += ValidationReportScorePolicy.rubricEndpointPoints(endpointCoverage);
    total += Math.max(0, 10 - Math.max(0, unfixedCriticals));
    if (!"REVIEW_FAILED".equals(reviewerVerdict)) total += 10;
    return (int) clamp(total, 0, 100);
  }

  private static double reviewerScore(String verdict) {
    return switch (verdict) {
      case "CLEAN" -> 100;
      case "FIXED_ISSUES" -> 85;
      case "UNRESOLVED_ISSUES" -> 40;
      case "REVIEW_FAILED" -> 10;
      default -> 50;
    };
  }

  private static double reviewerFactor(String verdict) {
    return switch (verdict) {
      case "CLEAN" -> 1.0;
      case "FIXED_ISSUES" -> .85;
      case "UNRESOLVED_ISSUES" -> .4;
      case "REVIEW_FAILED" -> .2;
      default -> .5;
    };
  }

  private static int trustCap(
      int raw,
      Boolean build,
      int passed,
      int total,
      String reviewer,
      int criticals,
      String coverage,
      BootVerification bootVerification) {
    int cap = 100;
    if ("REVIEW_FAILED".equals(reviewer)) cap = Math.min(cap, 70);
    else if ("UNRESOLVED_ISSUES".equals(reviewer)) cap = Math.min(cap, 80);
    if (criticals >= 3) cap = Math.min(cap, 70);
    else if (criticals >= 1) cap = Math.min(cap, 85);
    if (Boolean.FALSE.equals(build)) cap = Math.min(cap, 75);
    if (total > 0 && passed < total) cap = Math.min(cap, passed <= 0 ? 70 : 85);
    else if (total <= 0) {
      if ("NONE".equalsIgnoreCase(coverage)) cap = Math.min(cap, 75);
      else if ("UNKNOWN".equalsIgnoreCase(coverage) || coverage == null) cap = Math.min(cap, 90);
    }
    // RR-296 — the BUILD slot already scores a non-healthy boot below full (UNHEALTHY=55 etc.), but
    // the trust cap only saw the compile bit, so a service that compiled + tested + reviewed clean
    // could show a passing 89 headline while its own health probe failed. Apply the cap from the
    // strongest available BUILD evidence: ANY non-HEALTHY boot bounds the headline below the 85
    // PASS threshold. The more-broken verdicts already drive the BUILD slot low enough that this
    // cap
    // does not bite them; it exists to stop the borderline UNHEALTHY case reading PASS. Inert on
    // default runs (verify-boot flag off ⇒ bootVerification == null), tighten-only when present.
    if (bootVerification != null
        && bootVerification.verdict()
            != com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator.Verdict
                .HEALTHY) {
      cap = Math.min(cap, 84);
    }
    return Math.max(0, Math.min(100, Math.min(raw, cap)));
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
