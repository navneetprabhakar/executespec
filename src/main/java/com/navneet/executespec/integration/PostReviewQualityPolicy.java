package com.navneet.executespec.integration;

import java.util.List;

import com.navneet.executespec.execution.model.GoalDonenessDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.ReviewerResult;

/** Pure quality-boundary decisions used before post-review continuation is considered. */
final class PostReviewQualityPolicy {

  private PostReviewQualityPolicy() {}

  static boolean reviewerQualityFailed(ReviewerResult reviewResult, boolean reviewerErrored) {
    if (reviewerErrored) {
      return true;
    }
    if (reviewResult == null || reviewResult.overall() == null) {
      return false;
    }
    return Boolean.FALSE.equals(reviewResult.finalBuildPassed())
        || reviewResult.overall() == ReviewerResult.Overall.UNRESOLVED_ISSUES
        || reviewResult.overall() == ReviewerResult.Overall.REVIEW_FAILED;
  }

  static boolean validationReportQualityFailed(
      ValidationReport report, double requiredQualityThreshold) {
    if (report == null) {
      return false;
    }
    if (report.getPipelineResult() == PipelineResult.FAILED
        || report.getPipelineResult() == PipelineResult.NEEDS_ATTENTION) {
      return true;
    }
    Integer verifiedScore = report.getVerifiedScore();
    return verifiedScore != null
        && verifiedScore < sanitizeRequiredQualityThreshold(requiredQualityThreshold);
  }

  static GoalDonenessDecision validationReportLowQualityGoalDecision(
      ValidationReport report, double requiredQualityThreshold) {
    int score =
        report == null || report.getVerifiedScore() == null ? -1 : report.getVerifiedScore();
    String result =
        report == null || report.getPipelineResult() == null
            ? "UNKNOWN"
            : report.getPipelineResult().name();
    double threshold = sanitizeRequiredQualityThreshold(requiredQualityThreshold);
    String scoreText = score < 0 ? "unknown" : String.valueOf(score);
    return new GoalDonenessDecision(
        com.navneet.executespec.execution.model.GoalDonenessState.FAILED,
        RunContinuationDecision.STOP_FAILED,
        "final validation report quality is below threshold: verifiedScore="
            + scoreText
            + ", requiredQualityThreshold="
            + threshold
            + ", pipelineResult="
            + result,
        "final validation report",
        List.of(),
        List.of("validation report did not prove acceptable output quality"),
        List.of("verifiedScore=" + scoreText, "pipelineResult=" + result),
        List.of("a stronger model/provider may be needed to improve final quality evidence"),
        false,
        true);
  }

  static GoalDonenessDecision reviewerLowQualityGoalDecision(
      ReviewerResult reviewResult, boolean reviewerErrored) {
    String verdict =
        reviewerErrored
            ? "REVIEWER_EXCEPTION"
            : reviewResult != null && Boolean.FALSE.equals(reviewResult.finalBuildPassed())
                ? "FINAL_BUILD_FAILED"
                : reviewResult == null || reviewResult.overall() == null
                    ? "UNKNOWN"
                    : reviewResult.overall().name();
    return new GoalDonenessDecision(
        com.navneet.executespec.execution.model.GoalDonenessState.FAILED,
        RunContinuationDecision.STOP_FAILED,
        "final reviewer produced low-quality evidence: " + verdict,
        "final BUILD-mode reviewer",
        List.of(),
        List.of("reviewer did not prove the implementation is acceptable"),
        List.of("reviewerVerdict=" + verdict),
        List.of("a stronger model/provider may be needed to finish or repair the implementation"),
        false,
        true);
  }

  private static double sanitizeRequiredQualityThreshold(double threshold) {
    return threshold <= 0.0 ? 70.0 : Math.min(100.0, threshold);
  }
}
