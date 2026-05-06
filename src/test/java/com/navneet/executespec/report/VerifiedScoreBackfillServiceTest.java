package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * P1.2-followup (2026-05-06) — pins the JSON-parsing helpers used by the verified-score
 * backfill. The {@code backfillAll} top-level method's per-row mutation behavior is hard to
 * unit-test without a real DB; the parsing helpers carry the bulk of the risk and are unit-
 * testable directly.
 */
class VerifiedScoreBackfillServiceTest {

  // ── countUnfixedCriticalFromIssuesJson ─────────────────────────────────────

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
        "["
            + "{\"id\":\"SYNTH-BUILD-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false},"
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
      sb.append("{\"id\":\"SYNTH-ACC-MISS-").append(i)
        .append("\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}");
    }
    for (int i = 1; i <= 5; i++) {
      sb.append(",{\"id\":\"SYNTH-ACC-EP-").append(i)
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
        "["
            + "{\"id\":\"SYNTH-BUILD-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false},"
            + "{\"id\":\"SYNTH-ACC-MISS-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false},"
            + "{\"id\":\"SYNTH-ACC-EP-1\",\"severity\":\"CRITICAL\",\"autoFixed\":false,\"fixSucceeded\":false}"
            + "]";
    assertThat(VerifiedScoreBackfillService.countUnfixedCriticalFromIssuesJson(json)).isEqualTo(1);
  }

  // ── endpointCoverageFromAccuracyJson ───────────────────────────────────────

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
}
