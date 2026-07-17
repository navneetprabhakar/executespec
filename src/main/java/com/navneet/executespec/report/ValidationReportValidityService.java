package com.navneet.executespec.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.reviewer.model.AccuracyReport;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Canonical fail-closed predicate for project-level quality aggregates. */
@Service
@RequiredArgsConstructor
public class ValidationReportValidityService {

  private final ObjectMapper objectMapper;

  public Validity assess(ValidationReport report) {
    if (report == null) return Validity.invalid("report is absent");
    if (!"COMPLETE".equals(report.getStatus())) return Validity.invalid("report is not complete");
    Integer score = report.getVerifiedScore();
    if (score == null || score < 0 || score > 100)
      return Validity.invalid("verified score is invalid");
    if (report.getPipelineResult() == null || report.getPipelineResult() == PipelineResult.FAILED) {
      return Validity.invalid("pipeline result is not successful");
    }
    if (report.getFilesGenerated() <= 0) return Validity.invalid("no approved product diff");
    if (!Boolean.TRUE.equals(report.getFinalBuildPassed())) {
      return Validity.invalid("final build was not deterministically proven");
    }
    if ("FAILED".equals(report.getExecutableVerificationStatus())
        || "UNAVAILABLE".equals(report.getExecutableVerificationStatus())) {
      return Validity.invalid(
          "executable verification is " + report.getExecutableVerificationStatus().toLowerCase());
    }
    if ("REVIEW_FAILED".equals(report.getReviewerVerdict())
        || "UNRESOLVED_ISSUES".equals(report.getReviewerVerdict())) {
      return Validity.invalid("review evidence is unresolved");
    }
    AccuracyReport accuracy = parseAccuracy(report.getAccuracyJson());
    if (accuracy == null) return Validity.invalid("spec-adherence evidence is absent or malformed");
    if (ValidationReportService.scoreEvidenceFailure(report.getFilesGenerated(), accuracy)
        != null) {
      return Validity.invalid("spec or provenance evidence failed");
    }
    return new Validity(true, null);
  }

  public boolean isCanonical(ValidationReport report) {
    return assess(report).valid();
  }

  public Aggregate aggregate(java.util.List<ValidationReport> reports) {
    java.util.List<ValidationReport> safe = reports == null ? java.util.List.of() : reports;
    java.util.List<ValidationReport> canonical = safe.stream().filter(this::isCanonical).toList();
    double average =
        canonical.stream()
            .map(ValidationReport::getVerifiedScore)
            .filter(java.util.Objects::nonNull)
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);
    return new Aggregate(canonical, average, safe.size() - canonical.size());
  }

  private AccuracyReport parseAccuracy(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return objectMapper.readValue(json, AccuracyReport.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  public record Validity(boolean valid, String reason) {
    static Validity invalid(String reason) {
      return new Validity(false, reason);
    }
  }

  public record Aggregate(
      java.util.List<ValidationReport> canonicalReports, double averageScore, int excludedCount) {}
}
