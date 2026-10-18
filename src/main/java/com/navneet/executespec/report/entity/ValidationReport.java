package com.navneet.executespec.report.entity;

import java.time.Instant;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  /**
   * SHA-256 of the canonical Git tree generation measured by this report. Null only for historical
   * rows that predate V361; null must never be presented as a current-workspace binding.
   */
  @Column(name = "workspace_generation", length = 64)
  private String workspaceGeneration;

  /** V402: last admitted asynchronous test execution for this exact report generation. */
  @Column(name = "concrete_test_execution_id")
  private String concreteTestExecutionId;

  @Column(name = "concrete_test_started_at")
  private Instant concreteTestStartedAt;

  /** Runtime surface bound at report generation; null only for historical rows. */
  @Column(name = "runtime_surface", length = 24)
  private String runtimeSurface;

  /** V362 deterministic workflow/config parser evidence, including exact paths and content SHAs. */
  @Column(name = "workflow_config_evidence_json", columnDefinition = "TEXT")
  private String workflowConfigEvidenceJson;

  @Column(name = "build_command", columnDefinition = "TEXT")
  private String buildCommand;

  @Column(name = "build_working_directory", columnDefinition = "TEXT")
  private String buildWorkingDirectory;

  @Column(name = "build_exit_code")
  private Integer buildExitCode;

  @Column(name = "build_output", columnDefinition = "TEXT")
  private String buildOutput;

  @Column(name = "test_command", columnDefinition = "TEXT")
  private String testCommand;

  @Column(name = "test_working_directory", columnDefinition = "TEXT")
  private String testWorkingDirectory;

  @Column(name = "test_exit_code")
  private Integer testExitCode;

  @Column(name = "test_output", columnDefinition = "TEXT")
  private String testOutput;

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

  // Phase 3: Accuracy assessment fields (V74)

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

  // Pilot R6: Multi-dimension review scoring (V83)

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

  // v2.5.10 (V106): verified signals for trustworthy scoring

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

  /**
   * Tests the runner reported as skipped/ignored inside {@link #testsTotal}. Kept separate from
   * passes so a green exit with explicit skips is not misreported as a failed suite.
   */
  @Column(name = "tests_skipped", nullable = false)
  @Builder.Default
  private Integer testsSkipped = 0;

  /**
   * V317 / RR-1660 — three-valued execution-provenance token for {@link #testsPassed} / {@link
   * #testsTotal}.
   *
   * <p>{@code TRUE} only when the producing runner proved the counts belong to this run's own
   * execution: report directories reset before launch, exit status checked, every parsed report
   * postdating launch. {@code FALSE} when the producer explicitly could not prove it. {@code null}
   * when no provenance signal was recorded at all — including every row written before V317.
   *
   * <p><b>null is unproven, never proven-false.</b> TRUE permits a deterministic execution label,
   * whose exact scope is then selected from {@link #testEvidenceScope}; both other states keep the
   * conservative {@code UNVERIFIED_COUNT}/MEDIUM label.
   */
  @Column(name = "test_execution_verified")
  private Boolean testExecutionVerified;

  /**
   * V344 — boundary exercised by the proven test population. A fresh workspace/unit suite is
   * deterministic test evidence, but only LIVE_PREVIEW_FUNCTIONAL can prove product-level journeys.
   * Null is legacy/unknown and must not be upgraded to functional proof.
   */
  @Column(name = "test_evidence_scope", length = 40)
  private String testEvidenceScope;

  /** ReviewerResult.Overall enum name: CLEAN | FIXED_ISSUES | UNRESOLVED_ISSUES | REVIEW_FAILED. */
  @Column(name = "reviewer_verdict", length = 32)
  private String reviewerVerdict;

  /**
   * 0-100 internal verification summary produced by the active additive or legacy score policy. LLM
   * judgments may tighten a positive score but cannot create zero; a zero is reserved for an exact
   * deterministic gate persisted in {@link #scoreZeroCauseJson}.
   */
  @Column(name = "verified_score")
  private Integer verifiedScore;

  /**
   * V366 — schema-versioned deterministic cause captured at score-generation time when {@link
   * #verifiedScore} is zero. Null for nonzero and historical/unattributed rows; readers must never
   * reverse-infer a cause from nearby evidence.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "score_zero_cause_json", columnDefinition = "jsonb")
  private String scoreZeroCauseJson;

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

  /**
   * Deterministic preview/boot verification captured while generating the report (V270). These
   * fields deliberately persist the verifier's verdict and reason instead of relying on the
   * in-memory {@code ReportInput}; later rescoring must not silently discard the BUILD evidence.
   */
  @Column(name = "boot_verdict", length = 32)
  private String bootVerdict;

  @Column(name = "boot_healthy")
  private Boolean bootHealthy;

  @Column(name = "boot_reason", columnDefinition = "TEXT")
  private String bootReason;

  /** V273 deterministic executable-verification contract. */
  @Column(name = "executable_tests_run")
  private Boolean executableTestsRun;

  @Column(name = "executable_tests_passed")
  private Boolean executableTestsPassed;

  @Column(name = "executable_verification_status", length = 40)
  private String executableVerificationStatus;

  @Column(name = "execution_failure_stage", length = 40)
  private String executionFailureStage;

  @Column(name = "executable_evidence_json", columnDefinition = "TEXT")
  private String executableEvidenceJson;

  /**
   * Preview URL captured at report generation time. Null if preview expired or was never started.
   */
  @Column(name = "preview_url", length = 512)
  private String previewUrl;

  /**
   * Rolling-schema compatibility only. V294 hashes historical values and clears this column; new
   * application code must never populate or expose it.
   */
  @Column(name = "share_token", length = 64, unique = true)
  @com.fasterxml.jackson.annotation.JsonIgnore
  @lombok.ToString.Exclude
  private String legacyShareToken;

  @Column(name = "share_token_hash", length = 64, unique = true)
  @com.fasterxml.jackson.annotation.JsonIgnore
  @lombok.ToString.Exclude
  private String shareTokenHash;

  @Column(name = "share_token_prefix", length = 12)
  private String shareTokenPrefix;

  @Column(name = "share_expires_at")
  private Instant shareExpiresAt;

  @Column(name = "share_revoked_at")
  private Instant shareRevokedAt;

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
