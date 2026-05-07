package com.navneet.executespec.prreview.inbox;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inbox row for a {@code pull_request_review_comment} webhook event GitHub fired against a PR
 * ExecuteSpec opened.
 *
 * <p>Distinct from {@link com.navneet.executespec.prreview.entity.PrReviewComment} (V172) which
 * holds OUTGOING ExecuteSpec→PR reviews from the PrReviewAgent. This is the INBOUND mirror — humans
 * leaving feedback on our PRs.
 *
 * <p>Lifecycle: PENDING (default on insert) → ADDRESSED (set by Phase P2's ApplyService flow when
 * the user clicks "Address with chat") OR → DISMISSED (set when the user clicks Dismiss). Status
 * transitions are one-way; once a comment is ADDRESSED or DISMISSED it stays that way.
 */
@Entity
@Table(name = "pr_comment_inbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrCommentInbox {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false, length = 64)
  private String runId;

  @Column(name = "repo_full_name", nullable = false, length = 255)
  private String repoFullName;

  @Column(name = "pr_number", nullable = false)
  private Integer prNumber;

  /**
   * GitHub's numeric review-comment id. Unique per PR via the {@code (repo_full_name, pr_number,
   * comment_id)} composite constraint — a webhook retry won't insert duplicates.
   */
  @Column(name = "comment_id", nullable = false)
  private Long commentId;

  /** File path the comment is anchored to. Null for PR-level (not file-anchored) comments. */
  @Column(name = "file_path", columnDefinition = "TEXT")
  private String filePath;

  /** 1-based line number. Null for PR-level / multi-line comments. */
  @Column(name = "line_number")
  private Integer lineNumber;

  /** Full comment text. Rendering / truncation is the UI's responsibility. */
  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  /** GitHub login of the comment author. */
  @Column(nullable = false, length = 255)
  private String author;

  /** Direct link to the comment thread on github.com. */
  @Column(name = "github_url", nullable = false, columnDefinition = "TEXT")
  private String githubUrl;

  /** PENDING | ADDRESSED | DISMISSED. */
  @Column(nullable = false, length = 16)
  @Builder.Default
  private String status = "PENDING";

  /** Phase P2 — runId of the apply-suggestion run that addressed this comment. */
  @Column(name = "addressed_run_id", length = 64)
  private String addressedRunId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @PrePersist
  void onInsert() {
    if (createdAt == null) createdAt = Instant.now();
    if (updatedAt == null) updatedAt = Instant.now();
    if (status == null) status = "PENDING";
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = Instant.now();
  }
}
