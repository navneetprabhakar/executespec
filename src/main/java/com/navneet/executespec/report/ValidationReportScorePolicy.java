package com.navneet.executespec.report;

import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator;
import com.navneet.executespec.reviewer.model.ReviewVerdict;

/**
 * Pure score/report mappings shared by {@link ValidationReportService}.
 *
 * <p>This class deliberately has no Spring dependencies or persistence access. Keeping these small
 * policy functions together makes their contracts independently testable while the service remains
 * responsible for orchestration and feature-flag selection.
 */
final class ValidationReportScorePolicy {

  /** A score at or above 85 is rendered as passing; unverified requirements cannot cross it. */
  static final int UNVERIFIED_REQUIREMENTS_SCORE_CAP = 84;

  private ValidationReportScorePolicy() {}

  static int capForAdmittedSpecGaps(
      int score, int specAdherenceScore, int incompleteRequirements, int unknownRequirements) {
    int capped = Math.max(0, Math.min(100, score));
    if (incompleteRequirements > 0 || unknownRequirements > 0) {
      capped = Math.min(capped, Math.max(0, Math.min(100, specAdherenceScore)));
    }
    if (unknownRequirements > 0) {
      capped = Math.min(capped, UNVERIFIED_REQUIREMENTS_SCORE_CAP);
    }
    return capped;
  }

  static double testFactorFromCoverageLevel(String testCoverageLevel) {
    if (testCoverageLevel == null) return 0.5;
    return switch (testCoverageLevel.toUpperCase()) {
      case "COMPREHENSIVE", "GOOD" -> 1.0;
      case "PARTIAL" -> 0.7;
      case "NONE" -> 0.3;
      default -> 0.5;
    };
  }

  static int rubricTestPointsFromCoverageLevel(String testCoverageLevel) {
    return (int) Math.round(20.0 * testFactorFromCoverageLevel(testCoverageLevel));
  }

  /**
   * Maps endpoint coverage to the rubric's 15-point dimension.
   *
   * <p>A missing endpoint signal is not proof that the dimension is irrelevant. It is an unmeasured
   * claim, so it receives the neutral midpoint rather than the former full N/A credit. This keeps
   * unknown evidence from inflating a shared/public score while retaining partial credit for
   * non-HTTP or otherwise unmeasurable work.
   */
  static int rubricEndpointPoints(Integer endpointCoveragePercent) {
    if (endpointCoveragePercent == null) return 8;
    double ratio = Math.max(0.0, Math.min(1.0, endpointCoveragePercent / 100.0));
    return (int) Math.round(15.0 * ratio);
  }

  static String mapVerdictToV2Name(ReviewVerdict verdict) {
    if (verdict == null) return null;
    return switch (verdict) {
      case APPROVED -> "CLEAN";
      case APPROVED_WITH_NOTES -> "FIXED_ISSUES";
      case NEEDS_FIXES -> "UNRESOLVED_ISSUES";
      case REJECTED -> "REVIEW_FAILED";
    };
  }

  static boolean isAccuracyHallucinationDescription(String description) {
    if (description == null) return false;
    String normalized = description.stripLeading();
    return normalized.startsWith("Spec requirement not implemented:")
        || normalized.startsWith("Spec requirement only partially implemented:")
        || normalized.startsWith("Endpoint from spec not implemented:")
        || normalized.startsWith("Endpoint exists but");
  }

  static double bootSlotScore(BootHealthEvaluator.Verdict verdict) {
    return switch (verdict) {
      case HEALTHY -> 100.0;
      case UNHEALTHY -> 55.0;
      case TIMED_OUT -> 35.0;
      case DID_NOT_START -> 15.0;
      case BUILD_FAILED -> 0.0;
    };
  }
}
