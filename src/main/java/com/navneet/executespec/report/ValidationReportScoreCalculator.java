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
      if (gamingGuards && endpointCoverage != null) {
        code = 50 + 0.5 * clamp(endpointCoverage, 0, 100);
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
      if (gamingGuards && coverageLevel != null) {
        tests =
            Math.min(
                tests,
                switch (coverageLevel) {
                  case "NONE" -> 30;
                  case "PARTIAL" -> 70;
                  default -> 100;
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
            coverageLevel));
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
    return (int)
        clamp(Math.round(100 * build * tests * reviewer * endpoint * criticalDamper), 0, 100);
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
      String coverage) {
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
    return Math.max(0, Math.min(100, Math.min(raw, cap)));
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
