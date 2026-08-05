package com.navneet.executespec.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.navneet.executespec.integration.PostReviewQualityPolicy.FinalVerification;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

/**
 * RR-826 remainder — "no report exists" and "the report scored badly" are different facts. Both are
 * non-success, but only one of them is evidence about the output, and only one of them can be
 * improved by re-running the work on a stronger model.
 */
class PostReviewQualityPolicyFinalVerificationTest {

  private static ValidationReport report(PipelineResult result, Integer score) {
    ValidationReport r = new ValidationReport();
    r.setPipelineResult(result);
    r.setVerifiedScore(score);
    r.setExecutableVerificationStatus("PASSED");
    return r;
  }

  @Test
  void absent_report_is_unavailable_not_low_quality() {
    assertEquals(
        FinalVerification.UNAVAILABLE, PostReviewQualityPolicy.finalVerification(null, 70));
  }

  @Test
  void low_score_report_is_low_quality() {
    assertEquals(
        FinalVerification.LOW_QUALITY,
        PostReviewQualityPolicy.finalVerification(report(PipelineResult.PASSED, 40), 70));
  }

  @Test
  void failed_pipeline_result_is_low_quality_even_with_a_high_score() {
    assertEquals(
        FinalVerification.LOW_QUALITY,
        PostReviewQualityPolicy.finalVerification(report(PipelineResult.FAILED, 95), 70));
  }

  @Test
  void good_report_passes() {
    assertEquals(
        FinalVerification.PASSED,
        PostReviewQualityPolicy.finalVerification(report(PipelineResult.PASSED, 95), 70));
  }

  @Test
  void absent_report_still_fails_the_boolean_quality_gate() {
    // The RR-826 fail-closed behaviour is unchanged: a null report must never read as "not failed".
    // The new enum only stops it being MISLABELLED as measured low quality.
    org.junit.jupiter.api.Assertions.assertTrue(
        PostReviewQualityPolicy.validationReportQualityFailed(null, 70));
  }

  @Test
  void buildOnlyReportIsLowQualityEvenWithHighScore() {
    ValidationReport r = report(PipelineResult.PASSED, 95);
    r.setExecutableVerificationStatus("BUILD_ONLY");
    assertEquals(FinalVerification.LOW_QUALITY, PostReviewQualityPolicy.finalVerification(r, 70));
  }

  @Test
  void reviewerProtocolFailureIsUnavailableForAutomaticWorkerMutation() {
    ValidationReport r = report(PipelineResult.NEEDS_ATTENTION, 60);
    r.setReviewerVerdict("REVIEW_FAILED");

    org.junit.jupiter.api.Assertions.assertTrue(
        PostReviewQualityPolicy.reviewerProtocolUnavailable(r));
  }
}
