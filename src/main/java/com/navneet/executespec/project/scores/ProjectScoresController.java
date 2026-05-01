package com.navneet.executespec.project.scores;

import java.util.List;

import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Pilot R6 — score trend feed for a single project. Consumed by {@code ProjectScoreTrend} on the
 * dashboard.
 */
@RestController
@RequiredArgsConstructor
public class ProjectScoresController {

  private final ValidationReportRepository reportRepo;
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
          r.getAccuracyScore(),
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
      @org.springframework.security.core.annotation.AuthenticationPrincipal
          com.navneet.executespec.auth.security.AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    List<ScoreEntry> out =
        reportRepo.findAllByProjectId(projectId).stream().map(ScoreEntry::from).toList();
    return ResponseEntity.ok(out);
  }
}
