package com.navneet.executespec.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.reviewer.model.ReviewVerdict;

import org.junit.jupiter.api.Test;

/**
 * RR-290 — the CODE_REVIEW badge used to be granted whenever the unused V1 {@code mergeBlockers}
 * list was empty (which kickoff never populates), so an UNRESOLVED_ISSUES / REJECTED verdict — or a
 * reviewer that never ran (RR-289, now persisted as REJECTED) — still showed a passing check
 * alongside its own failed verdict. The badge is now derived from a single chokepoint that reads
 * the actual verdict AND unresolved structured issues. These pins prove the badge is tighten-only:
 * it passes only for an APPROVED / APPROVED_WITH_NOTES verdict with no unresolved CRITICAL issue.
 */
class ValidationReportServiceReviewCheckTest {

  private static ReviewIssue critical(boolean autoFixed, boolean fixSucceeded) {
    return ReviewIssue.builder()
        .id("C1")
        .severity(IssueSeverity.CRITICAL)
        .category(IssueCategory.LOGIC_ERROR)
        .description("critical")
        .autoFixed(autoFixed)
        .fixSucceeded(fixSucceeded)
        .build();
  }

  private static ReviewReport review(ReviewVerdict verdict, List<ReviewIssue> issues) {
    ReviewReport r = new ReviewReport();
    r.setVerdict(verdict);
    r.setIssues(issues);
    return r;
  }

  @Test
  void null_review_never_passes() {
    assertFalse(ValidationReportService.reviewCheckPassed(null));
  }

  @Test
  void approved_with_no_issues_passes() {
    assertTrue(
        ValidationReportService.reviewCheckPassed(review(ReviewVerdict.APPROVED, List.of())));
    assertTrue(
        ValidationReportService.reviewCheckPassed(
            review(ReviewVerdict.APPROVED_WITH_NOTES, List.of())));
  }

  @Test
  void failing_verdicts_never_pass() {
    // NEEDS_FIXES and REJECTED (the latter is also how RR-289 records "reviewer did not run") must
    // never show a passing code-review check.
    assertFalse(
        ValidationReportService.reviewCheckPassed(review(ReviewVerdict.NEEDS_FIXES, List.of())));
    assertFalse(
        ValidationReportService.reviewCheckPassed(review(ReviewVerdict.REJECTED, List.of())));
  }

  @Test
  void approved_verdict_with_unresolved_critical_does_not_pass() {
    assertFalse(
        ValidationReportService.reviewCheckPassed(
            review(ReviewVerdict.APPROVED, List.of(critical(false, false)))));
  }

  @Test
  void approved_verdict_with_only_autofixed_critical_passes() {
    assertTrue(
        ValidationReportService.reviewCheckPassed(
            review(ReviewVerdict.APPROVED, List.of(critical(true, true)))));
  }
}
