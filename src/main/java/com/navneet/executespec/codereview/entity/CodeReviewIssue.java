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
 * One issue surfaced by a {@link CodeReviewScan}. {@link #source} = STATIC means the issue came
 * from semgrep verbatim; LLM = the augmentation pass found it; HYBRID = static surfaced it but the
 * LLM rewrote the message + suggested a fix. {@link #fingerprint} dedupes across re-scans so the
 * same issue on the same line doesn't appear twice when the dashboard rolls up open issues across
 * historical scans.
 */
@Entity
@Table(name = "code_review_issue")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeReviewIssue {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "scan_id", nullable = false)
  private UUID scanId;

  /** sha256(rule || file || normalized_line || msg_prefix), 64 hex chars. */
  @Column(nullable = false, length = 64)
  private String fingerprint;

  /** CRITICAL | MAJOR | MINOR | INFO. */
  @Column(nullable = false, length = 16)
  private String severity;

  /** BUG | SECURITY | PERFORMANCE | STYLE | LOGIC | TEST_GAP | DUPLICATION | etc. */
  @Column(nullable = false, length = 32)
  private String category;

  /** STATIC | LLM | HYBRID. */
  @Column(nullable = false, length = 16)
  private String source;

  @Column(name = "rule_id", length = 128)
  private String ruleId;

  @Column(name = "file_path", nullable = false, length = 500)
  private String filePath;

  @Column(name = "line_start", nullable = false)
  private Integer lineStart;

  @Column(name = "line_end", nullable = false)
  private Integer lineEnd;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String message;

  @Column(name = "suggested_fix", columnDefinition = "TEXT")
  private String suggestedFix;

  /** OPEN | RESOLVED | DISMISSED | FALSE_POSITIVE. */
  @Column(nullable = false, length = 16)
  @Builder.Default
  private String status = "OPEN";

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  /** V173 widened from VARCHAR(36) to VARCHAR(255). See PrReview entity comment. */
  @Column(name = "resolved_by_user_id", length = 255)
  private String resolvedByUserId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
