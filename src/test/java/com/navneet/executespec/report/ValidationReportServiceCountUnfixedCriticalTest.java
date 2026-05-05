package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.List;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.model.ReportInput;
import com.navneet.executespec.reviewer.model.IssueCategory;
import com.navneet.executespec.reviewer.model.IssueSeverity;
import com.navneet.executespec.reviewer.model.ReviewIssue;
import com.navneet.executespec.reviewer.model.ReviewReportV2;
import com.navneet.executespec.reviewer.model.ReviewVerdict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P1.2 (GA-readiness, 2026-05-06) — pins the synthesised-issue filter on {@link
 * com.navneet.executespec.report.ValidationReportService#countUnfixedCritical}.
 *
 * <p>The audit on run {@code 0444c95f} found a -25 gap between the documented {@code
 * computeVerifiedScore} formula (75) and the persisted value (50). The cause: {@link
 * com.navneet.executespec.v2.integration.RunOrchestrator#synthesiseReviewIssues} translates the
 * accuracy report's {@code missingRequirements} + {@code endpointCoverage.missing} into CRITICAL
 * {@code SYNTH-ACC-MISS-*} / {@code SYNTH-ACC-EP-*} ReviewIssues, which then penalised the
 * verifiedScore at 5 points each. Because those issues come from the LLM's read-budget bug, they
 * shouldn't drag the headline number down. This test pins that filter.
 */
class ValidationReportServiceCountUnfixedCriticalTest {

  private ValidationReportService svc;
  private Method countUnfixedCritical;

  @BeforeEach
  void setUp() throws Exception {
    svc =
        new ValidationReportService(null, null, null, null, null, null, mock(FeatureFlagService.class));
    countUnfixedCritical =
        ValidationReportService.class.getDeclaredMethod("countUnfixedCritical", ReportInput.class);
    countUnfixedCritical.setAccessible(true);
  }

  private int count(ReviewIssue... issues) throws Exception {
    ReviewReportV2 review = new ReviewReportV2();
    review.setVerdict(ReviewVerdict.APPROVED_WITH_NOTES);
    review.setIssues(List.of(issues));
    ReportInput input = new ReportInput(null, List.of(), review, null, List.of());
    return (int) countUnfixedCritical.invoke(svc, input);
  }

  private static ReviewIssue critical(String id, IssueCategory cat) {
    return ReviewIssue.builder()
        .id(id)
        .severity(IssueSeverity.CRITICAL)
        .category(cat)
        .description("test issue")
        .autoFixed(false)
        .fixSucceeded(false)
        .build();
  }

  @Test
  void emptyIssueList_zero() throws Exception {
    assertThat(count()).isZero();
  }

  @Test
  void realReviewerCriticals_counted() throws Exception {
    // SYNTH-BUILD-* and SYNTH-REV-* are real signals (build failure / reviewer verdict)
    // — they MUST count toward the penalty.
    assertThat(count(critical("SYNTH-BUILD-1", IssueCategory.LOGIC_ERROR))).isEqualTo(1);
    assertThat(count(critical("SYNTH-BUILD-2", IssueCategory.DEPLOYABILITY))).isEqualTo(1);
    assertThat(count(critical("SYNTH-REV-1", IssueCategory.LOGIC_ERROR))).isEqualTo(1);
  }

  @Test
  void llmFlaggedReviewerCriticals_counted() throws Exception {
    // Real LLM-flagged issues (no SYNTH prefix, an arbitrary id) — they MUST count.
    assertThat(count(critical("R-001", IssueCategory.LOGIC_ERROR))).isEqualTo(1);
    assertThat(count(critical("issue-abc", IssueCategory.SECURITY))).isEqualTo(1);
  }

  @Test
  void synthAccMissingRequirementCriticals_filtered() throws Exception {
    // P1.2 — these come from AccuracyReport.missingRequirements, derived from LLM read budget.
    // Must NOT count.
    assertThat(count(critical("SYNTH-ACC-MISS-1", IssueCategory.MISSING_FEATURE))).isZero();
    assertThat(count(critical("SYNTH-ACC-MISS-5", IssueCategory.MISSING_FEATURE))).isZero();
  }

  @Test
  void synthAccEndpointMissingCriticals_filtered() throws Exception {
    // P1.2 — these come from AccuracyReport.endpointCoverage.missing, derived from LLM read budget.
    // Must NOT count.
    assertThat(count(critical("SYNTH-ACC-EP-1", IssueCategory.MISSING_ENDPOINT))).isZero();
    assertThat(count(critical("SYNTH-ACC-EP-5", IssueCategory.MISSING_ENDPOINT))).isZero();
  }

  @Test
  void mixedRealAndSynthAcc_onlyRealCounts() throws Exception {
    // The exact run-0444c95f shape: 0 real reviewer criticals (FIXED_ISSUES verdict means the
    // reviewer didn't flag any unresolved problems) + 5 synthesised SYNTH-ACC-* criticals from
    // the LLM's read budget. Only the build/reviewer signal should count → 0 unfixed critical.
    assertThat(
            count(
                critical("SYNTH-ACC-MISS-1", IssueCategory.MISSING_FEATURE),
                critical("SYNTH-ACC-MISS-2", IssueCategory.MISSING_FEATURE),
                critical("SYNTH-ACC-MISS-3", IssueCategory.MISSING_FEATURE),
                critical("SYNTH-ACC-EP-1", IssueCategory.MISSING_ENDPOINT),
                critical("SYNTH-ACC-EP-2", IssueCategory.MISSING_ENDPOINT)))
        .isZero();
  }

  @Test
  void mixedRealAndSynthAcc_realCountsThroughFilter() throws Exception {
    // 1 real reviewer critical + 3 synthesised accuracy criticals → only the real one counts.
    assertThat(
            count(
                critical("SYNTH-BUILD-1", IssueCategory.LOGIC_ERROR),
                critical("SYNTH-ACC-MISS-1", IssueCategory.MISSING_FEATURE),
                critical("SYNTH-ACC-MISS-2", IssueCategory.MISSING_FEATURE),
                critical("SYNTH-ACC-EP-1", IssueCategory.MISSING_ENDPOINT)))
        .isEqualTo(1);
  }

  @Test
  void autoFixedSucceeded_excluded() throws Exception {
    ReviewIssue fixed =
        ReviewIssue.builder()
            .id("R-001")
            .severity(IssueSeverity.CRITICAL)
            .category(IssueCategory.LOGIC_ERROR)
            .description("fixed")
            .autoFixed(true)
            .fixSucceeded(true)
            .build();
    assertThat(count(fixed)).isZero();
  }
}
