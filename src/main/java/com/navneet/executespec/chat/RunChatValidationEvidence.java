package com.navneet.executespec.chat;

import com.navneet.executespec.report.entity.ValidationReport;

/** Renders bounded validation evidence for the post-run Ask prompt. */
final class RunChatValidationEvidence {

  private RunChatValidationEvidence() {}

  static String render(ValidationReport report) {
    if (report == null) return "";

    StringBuilder result = new StringBuilder("## Validation report\n");
    if (report.getVerifiedScore() != null) {
      result
          .append("- verifiedScore: ")
          .append(report.getVerifiedScore())
          .append("/100 — internal summary score, not independent proof\n");
    }
    if (report.getAccuracyScore() != null) {
      result.append("- accuracyScore: ").append(report.getAccuracyScore()).append("/100");
      if (report.getAccuracyGrade() != null) {
        result.append(" (").append(report.getAccuracyGrade()).append(")");
      }
      result.append(" — LLM-assessed, treat as estimate\n");
    }
    if (report.getReviewerVerdict() != null) {
      result.append("- reviewerVerdict: ").append(report.getReviewerVerdict()).append('\n');
    }
    result.append("- finalBuildPassed: ").append(report.getFinalBuildPassed()).append('\n');
    if (report.getTestsTotal() != null && report.getTestsTotal() > 0) {
      result
          .append("- tests: ")
          .append(report.getTestsPassed())
          .append("/")
          .append(report.getTestsTotal())
          .append('\n');
    }

    appendScore(result, "codeQuality", report.getCodeQualityScore());
    appendScore(result, "testCoverage", report.getTestCoverageScore());
    appendScore(result, "architecture", report.getArchitectureScore());
    appendScore(result, "security", report.getSecurityScore());
    appendScore(result, "performance", report.getPerformanceScore());
    appendScore(result, "designCompliance", report.getDesignComplianceScore());

    String issuesJson = report.getIssuesJson();
    if (issuesJson != null && !issuesJson.isBlank() && !"[]".equals(issuesJson.trim())) {
      result.append("\n### Issues caught (raw JSON, up to 8)\n");
      result
          .append(
              issuesJson.length() > 4_000
                  ? issuesJson.substring(0, 4_000) + "\n…(truncated)"
                  : issuesJson)
          .append('\n');
    }
    return result.toString();
  }

  private static void appendScore(StringBuilder result, String label, Integer score) {
    if (score != null) {
      result.append("- ").append(label).append(": ").append(score).append("/100\n");
    }
  }
}
