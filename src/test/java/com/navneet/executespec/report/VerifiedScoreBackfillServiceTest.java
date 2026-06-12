package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;

import org.junit.jupiter.api.Test;

/**
 * P1.2-followup (2026-05-06) — pins the JSON-parsing helpers used by the verified-score backfill.
 * The {@code backfillAll} top-level method's per-row mutation behavior is hard to unit-test without
 * a real DB; the parsing helpers carry the bulk of the risk and are unit- testable directly.
 */
class VerifiedScoreBackfillServiceTest {

  @Test
  void backfill_dryRunReportsChangesWithoutSaving() {
    ValidationReportRepository repo = mock(ValidationReportRepository.class);
    ValidationReport row = backfillableRow();
    when(repo.findByRunId("run-1")).thenReturn(Optional.of(row));

    VerifiedScoreBackfillService svc = new VerifiedScoreBackfillService(repo, scoringService());
    VerifiedScoreBackfillService.BackfillResult result =
        svc.backfill(new VerifiedScoreBackfillService.BackfillRequest("run-1", null, true));

    assertThat(result.dryRun()).isTrue();
    assertThat(result.total()).isEqualTo(1);
    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.unchanged()).isZero();
    assertThat(result.changes()).hasSize(1);
    assertThat(result.changes().getFirst().oldVerifiedScore()).isEqualTo(10);
    assertThat(result.changes().getFirst().newVerifiedScore()).isEqualTo(100);
    assertThat(result.changes().getFirst().oldRubricScore()).isEqualTo(20);
    assertThat(result.changes().getFirst().newRubricScore()).isEqualTo(100);
    assertThat(row.getVerifiedScore()).isEqualTo(10);
    assertThat(row.getRubricScore()).isEqualTo(20);
    verify(repo, never()).save(any());
  }

  @Test
  void backfill_mutatesVerifiedScoreAndRubricScore() {
    ValidationReportRepository repo = mock(ValidationReportRepository.class);
    ValidationReport row = backfillableRow();
    when(repo.findByRunId("run-1")).thenReturn(Optional.of(row));

    VerifiedScoreBackfillService svc = new VerifiedScoreBackfillService(repo, scoringService());
    VerifiedScoreBackfillService.BackfillResult result =
        svc.backfill(new VerifiedScoreBackfillService.BackfillRequest("run-1", null, false));

    assertThat(result.dryRun()).isFalse();
    assertThat(result.updated()).isEqualTo(1);
    assertThat(row.getVerifiedScore()).isEqualTo(100);
    assertThat(row.getRubricScore()).isEqualTo(100);
    verify(repo).save(row);
  }

  @Test
  void backfill_appliesCurrentTrustCaps() {
    ValidationReportRepository repo = mock(ValidationReportRepository.class);
    ValidationReport row =
        ValidationReport.builder()
            .id("vr-capped")
            .runId("run-capped")
            .filesGenerated(5)
            .pipelineResult(PipelineResult.NEEDS_ATTENTION)
            .finalBuildPassed(true)
            .testsPassed(12)
            .testsTotal(12)
            .reviewerVerdict("REVIEW_FAILED")
            .verifiedScore(95)
            .rubricScore(80)
            .issuesJson("[]")
            .accuracyJson(
                """
                {
                  "testCoverageLevel": "GOOD",
                  "endpointCoverage": {"coveragePercent": 100}
                }
                """)
            .build();
    when(repo.findByRunId("run-capped")).thenReturn(Optional.of(row));

    VerifiedScoreBackfillService svc = new VerifiedScoreBackfillService(repo, scoringService());
    VerifiedScoreBackfillService.BackfillResult result =
        svc.backfill(new VerifiedScoreBackfillService.BackfillRequest("run-capped", null, true));

    assertThat(result.updated()).isEqualTo(1);
    assertThat(result.changes()).hasSize(1);
    assertThat(result.changes().getFirst().oldVerifiedScore()).isEqualTo(95);
    assertThat(result.changes().getFirst().newVerifiedScore()).isEqualTo(70);
    assertThat(row.getVerifiedScore()).isEqualTo(95);
    verify(repo, never()).save(any());
  }

  @Test
  void countUnfixedCritical_emptyOrNullJson_zero() {
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(null)).isZero();
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson("")).isZero();
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson("[]")).isZero();
  }

  @Test
  void countUnfixedCritical_realReviewerCriticals_counted() {
    String json =
        "[{\"id\":\"R-001\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isEqualTo(1);
  }

  @Test
  void countUnfixedCritical_synthBuildAndRev_counted() {
    String json =
        "[{\"id\":\"SYNTH-BUILD-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false},"
            + "{\"id\":\"SYNTH-REV-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isEqualTo(2);
  }

  @Test
  void countUnfixedCritical_synthAccFiltered() {
    // The exact run-0444c95f shape: 5 SYNTH-ACC-MISS-* + 5 SYNTH-ACC-EP-*. Old backfill
    // would have counted them all → 25-point penalty. New backfill filters them out.
    StringBuilder sb = new StringBuilder("[");
    for (int i = 1; i <= 5; i++) {
      if (i > 1) sb.append(',');
      sb.append("{\"id\":\"SYNTH-ACC-MISS-")
          .append(i)
          .append("\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}");
    }
    for (int i = 1; i <= 5; i++) {
      sb.append(",{\"id\":\"SYNTH-ACC-EP-")
          .append(i)
          .append("\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}");
    }
    sb.append(']');
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(sb.toString()))
        .isZero();
  }

  @Test
  void countUnfixedCritical_minorIssuesIgnored() {
    String json =
        "[{\"id\":\"R-002\",\"severity\":\"MINOR\",\"autoFixed\":false,\"fixSucceeded\":false}]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isZero();
  }

  @Test
  void countUnfixedCritical_autoFixedSucceeded_excluded() {
    String json =
        "[{\"id\":\"R-001\",\"severity\":\"CRITICAL\",\"autoFixed\":true,\"fixSucceeded\":true}]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isZero();
  }

  @Test
  void countUnfixedCritical_autoFixedButNotSucceeded_counted() {
    String json =
        "[{\"id\":\"R-001\",\"severity\":\"CRITICAL\",\"autoFixed\":true,\"fixSucceeded\":false}]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isEqualTo(1);
  }

  @Test
  void countUnfixedCritical_malformedJson_returnsZero() {
    // Intentionally bad JSON — service must not crash, just attribute zero unfixed.
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson("not json"))
        .isZero();
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson("[{ malformed"))
        .isZero();
  }

  @Test
  void countUnfixedCritical_mixedRealAndSynth_onlyRealCounts() {
    String json =
        "[{\"id\":\"SYNTH-BUILD-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false},"
            + "{\"id\":\"SYNTH-ACC-MISS-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false},"
            + "{\"id\":\"SYNTH-ACC-EP-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}"
            + "]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isEqualTo(1);
  }

  @Test
  void endpointCoverage_nullOrEmpty_null() {
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson(null)).isNull();
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson("")).isNull();
  }

  @Test
  void endpointCoverage_noEndpointBlock_null() {
    String json = "{\"specAdherenceScore\":75}";
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson(json)).isNull();
  }

  @Test
  void endpointCoverage_emptyEndpointBlock_null() {
    String json = "{\"endpointCoverage\":null}";
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson(json)).isNull();
  }

  @Test
  void endpointCoverage_present_returnsValue() {
    String json = "{\"endpointCoverage\":{\"coveragePercent\":100}}";
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson(json)).isEqualTo(100);
  }

  @Test
  void endpointCoverage_intermediate_returnsValue() {
    String json = "{\"endpointCoverage\":{\"coveragePercent\":73}}";
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson(json)).isEqualTo(73);
  }

  @Test
  void endpointCoverage_malformed_null() {
    assertThat(VerifiedScoreBackfillService.endpointCoverageFromAccuracyJson("not json")).isNull();
  }

  @Test
  void testCoverageLevel_extractsUppercaseLevel() {
    String json = "{\"testCoverageLevel\":\"partial\"}";
    assertThat(VerifiedScoreBackfillService.testCoverageLevelFromAccuracyJson(json))
        .isEqualTo("PARTIAL");
  }

  private static ValidationReport backfillableRow() {
    return ValidationReport.builder()
        .id("vr-1")
        .runId("run-1")
        .filesGenerated(3)
        .pipelineResult(PipelineResult.PASSED)
        .finalBuildPassed(true)
        .testsPassed(1)
        .testsTotal(1)
        .reviewerVerdict("CLEAN")
        .verifiedScore(10)
        .rubricScore(20)
        .issuesJson("[]")
        .accuracyJson(
            """
            {
              "testCoverageLevel": "GOOD",
              "endpointCoverage": {"coveragePercent": 100}
            }
            """)
        .build();
  }

  private static ValidationReportService scoringService() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.isEnabled(any())).thenReturn(true);
    return new ValidationReportService(null, null, new ObjectMapper(), null, null, null, flags);
  }
}
