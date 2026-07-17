package com.navneet.executespec.project.scores;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.ValidationReportValidityService;
import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Pilot R6 — score trend feed for a single project. Consumed by {@code ProjectScoreTrend} on the
 * dashboard.
 */
@RestController
@RequiredArgsConstructor
public class ProjectScoresController {

  static final int MAX_TREND_DAYS = 366;
  static final int MAX_TREND_POINTS = 500;

  private final ValidationReportRepository reportRepo;
  private final ValidationReportValidityService reportValidityService;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

  public record ScoreEntry(
      String runId,
      Integer overallScore,
      Integer codeQualityScore,
      Integer testCoverageScore,
      Integer architectureScore,
      Integer securityScore,
      Integer performanceScore,
      Integer designComplianceScore,
      String createdAt) {
    static ScoreEntry from(ValidationReport r) {
      return new ScoreEntry(
          r.getRunId(),
          r.getVerifiedScore(),
          r.getCodeQualityScore(),
          r.getTestCoverageScore(),
          r.getArchitectureScore(),
          r.getSecurityScore(),
          r.getPerformanceScore(),
          r.getDesignComplianceScore(),
          r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
    }
  }

  @GetMapping("/api/projects/{projectId}/scores")
  public ResponseEntity<List<ScoreEntry>> scoresForProject(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "366") int sinceDays,
      @RequestParam(defaultValue = "200") int limit,
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    int safeSinceDays = Math.min(Math.max(sinceDays, 1), MAX_TREND_DAYS);
    int safeLimit = Math.min(Math.max(limit, 1), MAX_TREND_POINTS);
    List<ScoreEntry> newestFirst =
        reportRepo
            .findRecentByProjectId(
                projectId,
                Instant.now().minusSeconds((long) safeSinceDays * 86_400L),
                org.springframework.data.domain.PageRequest.of(0, safeLimit * 3))
            .stream()
            .filter(reportValidityService::isCanonical)
            .map(ScoreEntry::from)
            .limit(safeLimit)
            .toList();
    List<ScoreEntry> oldestFirst = new ArrayList<>(newestFirst);
    Collections.reverse(oldestFirst);
    return ResponseEntity.ok()
        .header("X-Max-Points", Integer.toString(safeLimit))
        .body(List.copyOf(oldestFirst));
  }
}
