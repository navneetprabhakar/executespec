package com.navneet.executespec.prreview.entity;

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
 * One row per (repo, PR, head_sha) review the PR Review Agent has produced. See V172 migration for
 * column semantics + paid-tier gating.
 *
 * <p>The {@link #status} column tracks the agent run lifecycle (QUEUED → RUNNING → COMPLETE /
 * FAILED). The {@link #verdict} column carries the agent's verdict (APPROVE / REQUEST_CHANGES /
 * COMMENT) once the run reaches COMPLETE. {@link #postedToGithubAt} non-null = mirrored upstream
 * via GitHub's Reviews API; null = ExecuteSpec-internal-only review.
 */
@Entity
@Table(name = "pr_review")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrReview {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 36)
  private String projectId;

  @Column(name = "repo_full_name", nullable = false)
  private String repoFullName;

  @Column(name = "pr_number", nullable = false)
  private Integer prNumber;

  @Column(name = "head_sha", nullable = false, length = 40)
  private String headSha;

  /** QUEUED | RUNNING | COMPLETE | FAILED. */
  @Column(nullable = false, length = 16)
  private String status;

  /** APPROVE | REQUEST_CHANGES | COMMENT. Null while in flight or on FAILED. */
  @Column(length = 32)
  private String verdict;

  /** Agent's overall summary of the review. */
  @Column(columnDefinition = "TEXT")
  private String summary;

  @Column(name = "posted_to_github_at")
  private Instant postedToGithubAt;

  @Column(name = "github_review_id")
  private Long githubReviewId;

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

  @Column(name = "started_at", nullable = false)
  @Builder.Default
  private Instant startedAt = Instant.now();

  @Column(name = "finished_at")
  private Instant finishedAt;

  /**
   * 2026-05-05 — V173 widened this from VARCHAR(36) to VARCHAR(255) for parity with every other
   * table that references {@code app_user.id}. The seeded super-admin id is 42 chars; the legacy
   * 36-char width truncated on insert and surfaced as a 500 to the controller.
   */
  @Column(name = "created_by_user_id", length = 255)
  private String createdByUserId;
}
