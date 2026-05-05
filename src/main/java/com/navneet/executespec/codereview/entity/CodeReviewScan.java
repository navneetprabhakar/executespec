package com.navneet.executespec.codereview.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per whole-repo code-review scan. Two-stage: static (semgrep) → LLM (augmentation +
 * triage). Status flows QUEUED → CLONING → STATIC_RUNNING → LLM_RUNNING → COMPLETE / FAILED with
 * SSE events at every transition. See V172 migration for column semantics + paid-tier gating.
 *
 * <p>The {@link #reportMarkdown} column carries the human-readable mirror of the structured issues.
 * We treat it the same way {@link com.navneet.executespec.codeintel.entity.RepoArchitectureDoc} +
 * {@link com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc} treat their markdown — a
 * project artifact that the chat surface + a download endpoint can hand to the user verbatim.
 */
@Entity
@Table(name = "code_review_scan")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewScan {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 36)
  private String projectId;

  @Column(name = "repo_id")
  private UUID repoId;

  @Column(name = "repo_full_name", nullable = false)
  private String repoFullName;

  @Column(name = "scanned_sha", nullable = false, length = 40)
  private String scannedSha;

  /** QUEUED | CLONING | STATIC_RUNNING | LLM_RUNNING | COMPLETE | FAILED. */
  @Column(nullable = false, length = 24)
  private String status;

  /** MANUAL | SCHEDULED | POST_MERGE. */
  @Column(name = "trigger_source", nullable = false, length = 16)
  private String triggerSource;

  @Column(name = "total_files_scanned")
  @Builder.Default
  private Integer totalFilesScanned = 0;

  @Column(name = "total_loc")
  @Builder.Default
  private Integer totalLoc = 0;

  @Column(name = "static_findings_count")
  @Builder.Default
  private Integer staticFindingsCount = 0;

  @Column(name = "llm_findings_count")
  @Builder.Default
  private Integer llmFindingsCount = 0;

  @Column(name = "llm_filtered_out_count")
  @Builder.Default
  private Integer llmFilteredOutCount = 0;

  @Column(name = "cost_usd")
  @Builder.Default
  private Double costUsd = 0.0;

  @Column(name = "total_input_tokens")
  @Builder.Default
  private Long totalInputTokens = 0L;

  @Column(name = "total_output_tokens")
  @Builder.Default
  private Long totalOutputTokens = 0L;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  /**
   * Markdown rendering of the review — anchor for the chat surface + download. Generated at the end
   * of LLM_RUNNING. See {@link com.navneet.executespec.codereview.CodeReviewMarkdownRenderer}.
   */
  @Column(name = "report_markdown", columnDefinition = "TEXT")
  private String reportMarkdown;

  @Column(name = "started_at", nullable = false)
  @Builder.Default
  private Instant startedAt = Instant.now();

  @Column(name = "finished_at")
  private Instant finishedAt;

  /** V173 widened from VARCHAR(36) to VARCHAR(255). See PrReview entity comment. */
  @Column(name = "created_by_user_id", length = 255)
  private String createdByUserId;
}
