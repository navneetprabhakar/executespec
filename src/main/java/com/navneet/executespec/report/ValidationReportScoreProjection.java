package com.navneet.executespec.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.model.AccuracyReport;

/**
 * Projects the persisted score through evidence discovered after the score was first written.
 *
 * <p>{@code validation_report.verified_score} is the deterministic score snapshot. The accuracy
 * evidence can be refreshed independently and may admit missing, partial, or unverified
 * requirements after that snapshot was written. Every customer-facing read model must therefore
 * apply the same conservative projection instead of exposing the stale optimistic value.
 */
public final class ValidationReportScoreProjection {

  private ValidationReportScoreProjection() {}

  public static Integer effectiveVerifiedScore(ValidationReport report, ObjectMapper objectMapper) {
    if (report == null) return null;
    return effectiveScore(report.getVerifiedScore(), report.getAccuracyJson(), objectMapper);
  }

  public static Integer effectiveRubricScore(ValidationReport report, ObjectMapper objectMapper) {
    if (report == null) return null;
    return effectiveScore(report.getRubricScore(), report.getAccuracyJson(), objectMapper);
  }

  public static Map<String, Integer> effectiveScores(
      List<Object[]> rows, ObjectMapper objectMapper) {
    Map<String, Integer> scores = new LinkedHashMap<>();
    for (Object[] row : rows) {
      if (row[0] == null || row[1] == null) continue;
      String accuracyJson = row.length > 2 && row[2] != null ? String.valueOf(row[2]) : null;
      scores.put(
          String.valueOf(row[0]),
          effectiveScore(((Number) row[1]).intValue(), accuracyJson, objectMapper));
    }
    return scores;
  }

  public static Integer effectiveScore(
      Integer persistedScore, String accuracyJson, ObjectMapper objectMapper) {
    if (persistedScore == null
        || accuracyJson == null
        || accuracyJson.isBlank()
        || objectMapper == null) {
      return persistedScore;
    }
    try {
      AccuracyReport accuracy = objectMapper.readValue(accuracyJson, AccuracyReport.class);
      return ValidationReportScorePolicy.capForAdmittedSpecGaps(
          persistedScore,
          accuracy.getSpecAdherenceScore(),
          accuracy.incompleteRequirementsCount(),
          accuracy.unknownRequirementsCount());
    } catch (Exception ignored) {
      // Preserve the historical fail-open projection for malformed legacy evidence. The report
      // service logs the malformed payload when it parses the full report.
      return persistedScore;
    }
  }
}
