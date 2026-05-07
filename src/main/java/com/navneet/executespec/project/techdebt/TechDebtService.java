package com.navneet.executespec.project.techdebt;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * v2.5.2 — Tech Debt rollup for the Project Overview tab. Reads exclusively from {@link
 * ValidationReport} data (issues_json + dimension scores); no new LLM calls. {@code since} limits
 * the lookback window.
 *
 * <p>Issue shape: every validation report stores a JSON array of {@code {category, severity, file,
 * description}} objects. Category + severity taxonomies follow ReviewerAgent's contract:
 *
 * <ul>
 *   <li>Categories: LOGIC / SECURITY / TEST / STYLE / PERFORMANCE / ARCHITECTURE / WIRING
 *   <li>Severities: BLOCKER / MAJOR / MINOR / INFO
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechDebtService {

  private final ValidationReportRepository reportRepo;
  private final ObjectMapper objectMapper;

  public record FileHotspot(String filePath, int issueCount) {}

  public record DimensionPoint(String dimension, int score, Instant at) {}

  public record Report(
      Map<String, Integer> issuesByCategory,
      Map<String, Integer> issuesBySeverity,
      List<FileHotspot> topFiles,
      List<DimensionPoint> scoreTrend,
      double averageAccuracyScore,
      int totalRunsAnalyzed) {}

  public Report build(String projectId, Integer sinceDays) {
    Instant cutoff =
        sinceDays == null || sinceDays <= 0
            ? Instant.EPOCH
            : Instant.now().minus(Duration.ofDays(sinceDays));

    List<ValidationReport> reports =
        reportRepo.findAllByProjectId(projectId).stream()
            .filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().isBefore(cutoff))
            .toList();

    Map<String, Integer> byCategory = new HashMap<>();
    Map<String, Integer> bySeverity = new HashMap<>();
    Map<String, Integer> byFile = new HashMap<>();
    List<DimensionPoint> trend = new ArrayList<>();
    double accSum = 0;
    int accCount = 0;

    for (ValidationReport r : reports) {
      if (r.getAccuracyScore() != null) {
        accSum += r.getAccuracyScore();
        accCount++;
      }
      trendPoint(trend, "codeQuality", r.getCodeQualityScore(), r.getCreatedAt());
      trendPoint(trend, "testCoverage", r.getTestCoverageScore(), r.getCreatedAt());
      trendPoint(trend, "architecture", r.getArchitectureScore(), r.getCreatedAt());
      trendPoint(trend, "security", r.getSecurityScore(), r.getCreatedAt());
      aggregateIssues(r.getIssuesJson(), byCategory, bySeverity, byFile);
    }

    List<FileHotspot> top =
        byFile.entrySet().stream()
            .map(e -> new FileHotspot(e.getKey(), e.getValue()))
            .sorted(Comparator.comparingInt(FileHotspot::issueCount).reversed())
            .limit(5)
            .toList();

    double avgAccuracy = accCount == 0 ? 0.0 : (accSum / accCount);
    trend.sort(Comparator.comparing(DimensionPoint::at));

    return new Report(byCategory, bySeverity, top, trend, avgAccuracy, reports.size());
  }

  private void trendPoint(List<DimensionPoint> trend, String dim, Integer score, Instant at) {
    if (score != null && at != null) trend.add(new DimensionPoint(dim, score, at));
  }

  private void aggregateIssues(
      String issuesJson,
      Map<String, Integer> byCategory,
      Map<String, Integer> bySeverity,
      Map<String, Integer> byFile) {
    if (issuesJson == null || issuesJson.isBlank() || "[]".equals(issuesJson.trim())) return;
    try {
      List<Map<String, Object>> issues =
          objectMapper.readValue(issuesJson, new TypeReference<List<Map<String, Object>>>() {});
      for (Map<String, Object> issue : issues) {
        Object cat = issue.get("category");
        Object sev = issue.get("severity");
        Object file = issue.get("file");
        if (cat instanceof String s && !s.isBlank()) {
          byCategory.merge(s.toUpperCase(), 1, Integer::sum);
        }
        if (sev instanceof String s && !s.isBlank()) {
          bySeverity.merge(s.toUpperCase(), 1, Integer::sum);
        }
        if (file instanceof String s && !s.isBlank()) {
          byFile.merge(s, 1, Integer::sum);
        }
      }
    } catch (Exception e) {
      // Some legacy rows have non-array shapes; tolerate silently.
      log.debug("aggregateIssues parse failed: {}", e.getMessage());
      tryAlternateShape(issuesJson, byCategory, bySeverity, byFile);
    }
  }

  /** Some older reports store {@code {issues: [...]}} rather than a bare array. */
  private void tryAlternateShape(
      String json,
      Map<String, Integer> byCategory,
      Map<String, Integer> bySeverity,
      Map<String, Integer> byFile) {
    try {
      JsonNode node = objectMapper.readTree(json);
      JsonNode arr = node.isArray() ? node : node.get("issues");
      if (arr == null || !arr.isArray()) return;
      for (JsonNode issue : arr) {
        String cat = issue.path("category").asText(null);
        String sev = issue.path("severity").asText(null);
        String file = issue.path("file").asText(null);
        if (cat != null && !cat.isBlank()) byCategory.merge(cat.toUpperCase(), 1, Integer::sum);
        if (sev != null && !sev.isBlank()) bySeverity.merge(sev.toUpperCase(), 1, Integer::sum);
        if (file != null && !file.isBlank()) byFile.merge(file, 1, Integer::sum);
      }
    } catch (Exception ignore) {
      /* give up */
    }
  }
}
