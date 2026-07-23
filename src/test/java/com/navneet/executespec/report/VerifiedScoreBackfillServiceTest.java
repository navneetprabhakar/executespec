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
import com.navneet.executespec.deployment.preview.verify.BootHealthEvaluator;
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
  void backfill_cannotInflateScoresAbovePersistedPartialRequirementTruth() {
    ValidationReportRepository repo = mock(ValidationReportRepository.class);
    ValidationReport row = backfillableRow();
    row.setId("vr-partial-gap");
    row.setRunId("run-partial-gap");
    row.setVerifiedScore(83);
    row.setRubricScore(83);
    row.setAccuracyJson(
        """
        {
          "specAdherenceScore": 83,
          "testCoverageLevel": "GOOD",
          "endpointCoverage": {"coveragePercent": 100},
          "missingRequirements": [],
          "partialRequirements": ["Schema migration is not implemented"]
        }
        """);
    when(repo.findByRunId("run-partial-gap")).thenReturn(Optional.of(row));

    VerifiedScoreBackfillService svc = new VerifiedScoreBackfillService(repo, scoringService());
    VerifiedScoreBackfillService.BackfillResult result =
        svc.backfill(
            new VerifiedScoreBackfillService.BackfillRequest("run-partial-gap", null, true));

    assertThat(result.changes()).hasSize(1);
    assertThat(result.changes().getFirst().newVerifiedScore()).isEqualTo(79);
    assertThat(result.changes().getFirst().newRubricScore()).isEqualTo(79);
    verify(repo, never()).save(any());
  }

  @Test
  void backfill_capsPersistedMissingRequirementAtSixtyNine() {
    String accuracyJson =
        """
        {
          "specAdherenceScore": 96,
          "missingRequirements": ["Required startup wiring is absent"],
          "partialRequirements": []
        }
        """;

    assertThat(
            VerifiedScoreBackfillService.capScoreAtAdmittedSpecGapsFromAccuracyJson(
                100, accuracyJson))
        .isEqualTo(69);
  }

  @Test
  void backfillLeavesUnknownOnlyAccuracyUncapped() {
    String accuracyJson =
        """
        {
          "specAdherenceScore": 20,
          "missingRequirements": [],
          "partialRequirements": [],
          "unknownRequirements": ["Could not inspect deployment evidence"]
        }
        """;

    assertThat(
            VerifiedScoreBackfillService.capScoreAtAdmittedSpecGapsFromAccuracyJson(
                94, accuracyJson))
        .isEqualTo(94);
  }

  /**
   * RR-1645 — the backfill used to pass {@code bootVerification=null}, so a run that compiled but
   * booted UNHEALTHY (honest BUILD slot 55) was re-scored with BUILD=100, upgrading the headline
   * above the run's own recorded evidence. The recompute must reproduce the score generation would
   * have produced from the same persisted row.
   */
  @Test
  void backfill_honoursPersistedBootVerdict() {
    ValidationReportRepository repo = mock(ValidationReportRepository.class);
    ValidationReport row = backfillableRow();
    row.setId("vr-boot");
    row.setRunId("run-boot");
    row.setBootVerdict(BootHealthEvaluator.Verdict.UNHEALTHY.name());
    row.setBootHealthy(false);
    row.setBootReason("started but /health never returned 200");
    when(repo.findByRunId("run-boot")).thenReturn(Optional.of(row));

    VerifiedScoreBackfillService svc = new VerifiedScoreBackfillService(repo, scoringService());
    VerifiedScoreBackfillService.BackfillResult result =
        svc.backfill(new VerifiedScoreBackfillService.BackfillRequest("run-boot", null, true));

    // BUILD slot reads the boot verdict (UNHEALTHY = 55) instead of the compile-only 100:
    //   (30*100 + 25*55 + 20*100 + 20*100 + 5*100) / 100 = 88.75 → 89 raw.
    // RR-296 — a non-HEALTHY boot must not read PASS: the trust cap bounds the headline at 84
    // (below the 85 PASS threshold), so a service that compiles + tests + reviews clean but fails
    // its health probe can no longer show a passing 89.
    assertThat(result.changes().getFirst().newVerifiedScore()).isEqualTo(84);
  }

  /** RR-1645 — the recompute must be a pure function of the persisted row, so it is idempotent. */
  @Test
  void backfill_isIdempotentOnAnUnhealthyBootRow() {
    ValidationReportRepository repo = mock(ValidationReportRepository.class);
    ValidationReport row = backfillableRow();
    row.setId("vr-boot-2");
    row.setRunId("run-boot-2");
    row.setBootVerdict(BootHealthEvaluator.Verdict.UNHEALTHY.name());
    row.setBootHealthy(false);
    // RR-296 — the recompute is a pure function of the persisted row; an UNHEALTHY boot lands at
    // the boot-aware trust cap of 84, so seed the already-correct value to prove idempotency.
    row.setVerifiedScore(84);
    row.setRubricScore(100);
    when(repo.findByRunId("run-boot-2")).thenReturn(Optional.of(row));

    VerifiedScoreBackfillService svc = new VerifiedScoreBackfillService(repo, scoringService());
    VerifiedScoreBackfillService.BackfillResult result =
        svc.backfill(new VerifiedScoreBackfillService.BackfillRequest("run-boot-2", null, false));

    assertThat(result.unchanged()).isEqualTo(1);
    assertThat(result.updated()).isZero();
    assertThat(row.getVerifiedScore()).isEqualTo(84);
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
