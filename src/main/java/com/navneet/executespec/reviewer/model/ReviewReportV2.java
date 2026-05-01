package com.navneet.executespec.reviewer.model;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Review report produced by {@link com.navneet.executespec.reviewer.ReviewerAgentV2}.
 *
 * <p>V2 uses verdict-based flow (APPROVED/APPROVED_WITH_NOTES/NEEDS_FIXES/REJECTED) with built-in
 * auto-fix loop for CRITICAL and MINOR issues.
 *
 * <p>Includes backward-compatible fields ({@code overallConfidence}, {@code mergeBlockers}, {@code
 * postMergeSuggestions}) so downstream consumers (ApprovalGate, ValidationReportService) can
 * migrate incrementally from v1 ReviewReport.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewReportV2 {
  private ReviewVerdict verdict; // APPROVED, APPROVED_WITH_NOTES, NEEDS_FIXES, REJECTED
  private List<ReviewIssue> issues; // all issues found (flat list)
  private int criticalCount;
  private int minorCount;
  private int autoFixAttempted;
  private int autoFixSucceeded;
  private String summary; // human-readable summary paragraph
  private Duration reviewDuration;
  private String reviewerModel; // which model did the review
  private boolean readmeUpdated; // hard gate check

  // ── V1-compatible fields (populated by ReviewerAgentV2 for downstream consumers) ──
  private String overallConfidence; // HIGH | MEDIUM | LOW
  @Builder.Default private List<String> mergeBlockers = List.of();
  @Builder.Default private List<String> postMergeSuggestions = List.of();

  // ── Phase 3: Accuracy assessment (populated after fix phase) ──
  /** Structured accuracy report: spec adherence score, endpoint coverage, missing requirements. */
  private AccuracyReport accuracyReport;

  /**
   * Maps V2 verdict to V1-style recommendation string for backward compatibility. Legacy compat
   * path; no live caller as of V2 cutover.
   */
  public String getRecommendationCompat() {
    if (verdict == null) return "MERGE";
    return switch (verdict) {
      case APPROVED, APPROVED_WITH_NOTES -> "MERGE";
      case NEEDS_FIXES, REJECTED -> "REWORK";
    };
  }
}
