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
 * One line-anchored comment from the PR Review Agent's output. {@link #side} mirrors GitHub's
 * Reviews-API convention: LEFT = comment on the BASE side of the diff (a removed line), RIGHT =
 * comment on the HEAD side (an added or unchanged line being commented on).
 *
 * <p>{@link #postedToGithubAt} is non-null when the comment has been mirrored to GitHub via the
 * Reviews API; null while the review is internal-only.
 */
@Entity
@Table(name = "pr_review_comment")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrReviewComment {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "pr_review_id", nullable = false)
  private UUID prReviewId;

  @Column(name = "file_path", nullable = false, length = 500)
  private String filePath;

  @Column(name = "line_number", nullable = false)
  private Integer lineNumber;

  /** LEFT | RIGHT. */
  @Column(nullable = false, length = 8)
  private String side;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  /** BUG | SECURITY | PERFORMANCE | STYLE | LOGIC | TEST_GAP — free-form for now. */
  @Column(length = 32)
  private String category;

  /** CRITICAL | MAJOR | MINOR | INFO. */
  @Column(length = 16)
  private String severity;

  @Column(name = "posted_to_github_at")
  private Instant postedToGithubAt;

  @Column(name = "github_comment_id")
  private Long githubCommentId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
