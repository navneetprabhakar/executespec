package com.navneet.executespec.report.entity;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "validation_report")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationReport {

  @Id private String id;

  @Column(name = "run_id", nullable = false)
  private String runId;

  @Column(name = "files_generated", nullable = false)
  @Builder.Default
  private int filesGenerated = 0;

  @Column(name = "languages", nullable = false)
  @Builder.Default
  private String languages = "";

  @Enumerated(EnumType.STRING)
  @Column(name = "pipeline_result", nullable = false, length = 30)
  @Builder.Default
  private PipelineResult pipelineResult = PipelineResult.PASSED;

  @Column(name = "issues_json", columnDefinition = "TEXT", nullable = false)
  @Builder.Default
  private String issuesJson = "[]";

  @Column(name = "checks_passed", nullable = false)
  @Builder.Default
  private String checksPassed = "";

  @Column(name = "report_markdown", columnDefinition = "TEXT")
  private String reportMarkdown;

  @Column(name = "model_used", length = 50)
  private String modelUsed;

  @Column(name = "input_tokens")
  private Integer inputTokens;

  @Column(name = "output_tokens")
  private Integer outputTokens;

  @Column(name = "generation_time_ms")
  private Long generationTimeMs;

  @Column(name = "generated_after_improvements", nullable = false)
  @Builder.Default
  private boolean generatedAfterImprovements = false;

  /**
   * Lifecycle marker: PENDING while generation is in flight, COMPLETE when the report is readable,
   * FAILED if generation errored out. Default COMPLETE so backfilled rows (V80 migration) render
   * correctly.
   */
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private String status = "COMPLETE";

  // ── Phase 3: Accuracy assessment fields (V74) ──────────────────────────────────────────────

  /** Spec adherence score 0-100 from AccuracyAssessmentService. Null if assessment was skipped. */
  @Column(name = "accuracy_score")
  private Integer accuracyScore;

  /** Letter grade: A/B/C/D/F. Null if assessment was skipped. */
  @Column(name = "accuracy_grade", length = 2)
  private String accuracyGrade;

  /**
   * Full AccuracyReport JSON including implemented/missing requirements, endpoint coverage, schema
   * accuracy, test coverage level, and grading notes.
   */
  @Column(name = "accuracy_json", columnDefinition = "TEXT")
  private String accuracyJson;

  // ── Pilot R6: Multi-dimension review scoring (V83) ─────────────────────────────────────────

  @Column(name = "code_quality_score")
  private Integer codeQualityScore;

  @Column(name = "test_coverage_score")
  private Integer testCoverageScore;

  @Column(name = "architecture_score")
  private Integer architectureScore;

  @Column(name = "security_score")
  private Integer securityScore;

  @Column(name = "performance_score")
  private Integer performanceScore;

  /** Weighted when the run provides a {@code figmaUrl}. Null otherwise. */
  @Column(name = "design_compliance_score")
  private Integer designComplianceScore;

  // ── v2.5.10 (V106): verified signals for trustworthy scoring ──────────────────────────────

  /**
   * True when ReviewerResult.finalBuildPassed at report time. Null for rows written before V106.
   */
  @Column(name = "final_build_passed")
  private Boolean finalBuildPassed;

  /** Count of TestResult rows with status=PASS for this run at report time. */
  @Column(name = "tests_passed")
  private Integer testsPassed;

  /** Count of all TestResult rows for this run at report time. 0 when test generation never ran. */
  @Column(name = "tests_total")
  private Integer testsTotal;

  /** ReviewerResult.Overall enum name: CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED. */
  @Column(name = "reviewer_verdict", length = 32)
  private String reviewerVerdict;

  /**
   * 0-100 deterministic score derived from final_build_passed + tests_passed/tests_total +
   * reviewer_verdict - unfixed_critical_penalty. Primary display when the {@code
   * scoring.v2-verified} feature flag is on; accuracy_score remains available as secondary
   * "estimated requirement coverage".
   */
  @Column(name = "verified_score")
  private Integer verifiedScore;

  /**
   * V194 / PR-E Phase A (2026-05-13) — additive rubric companion to {@link #verifiedScore}. 0-100
   * where each dimension contributes independently:
   *
   * <ul>
   *   <li>Build passed: 25 pts
   *   <li>Tests passed (linear): 20 pts
   *   <li>Reviewer verdict: 20 pts (CLEAN/FIXED_ISSUES/UNRESOLVED/FAILED → 20/15/5/0)
   *   <li>Endpoint coverage (linear): 15 pts
   *   <li>Critical-issue damper: 10 pts (10 − 1 per critical, floored at 0)
   *   <li>Code quality (no review fail): 10 pts
   * </ul>
   *
   * <p>Fixes the verifiedScore=0 cliff for runs that landed with one dimension missing — the rubric
   * still rewards every dimension that did succeed. Frontend headline picks the more informative of
   * the two (Phase B). Null on pre-V194 rows.
   */
  @Column(name = "rubric_score")
  private Integer rubricScore;

  // ──────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Preview URL captured at report generation time. Null if preview expired or was never started.
   */
  @Column(name = "preview_url", length = 512)
  private String previewUrl;

  @Column(name = "share_token", length = 64, unique = true)
  private String shareToken;

  @Column(name = "shared_at")
  private Instant sharedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    var now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
