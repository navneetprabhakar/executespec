package com.navneet.executespec.sharing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * V175 — public read-only share link to a specific project surface (overview / run / code review /
 * pr review / validation report / architecture doc). Token is the public ID — anyone with the URL
 * can read until the link is revoked or expires. Tier-gated for active-link count.
 *
 * <p>The {@link Surface} enum spells out which surface the link points at; {@link #scopeId} is the
 * sub-id when the surface needs one (e.g. RUN_DETAIL → runId, CODE_REVIEW → scanId, PR_REVIEW →
 * prReviewId).
 */
@Entity
@Table(name = "project_share_link")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectShareLink {

  /**
   * What the link points at. Each value carries scoping rules + which controller serves the public
   * GET.
   */
  public enum Surface {
    /** Project overview — dashboard rollup, no sub-id. */
    PROJECT_OVERVIEW,
    /** Specific run detail page. {@code scopeId} = runId. */
    RUN_DETAIL,
    /** Specific whole-repo code review scan. {@code scopeId} = scanId. */
    CODE_REVIEW,
    /** Specific PR review. {@code scopeId} = pr_review.id. */
    PR_REVIEW,
    /** Validation report for a run. {@code scopeId} = runId. */
    VALIDATION_REPORT,
    /** Latest architecture doc for the project. No sub-id. */
    ARCHITECTURE_DOC
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 255)
  private String projectId;

  /** 32-byte URL-safe random string; the public path uses this. */
  @Column(nullable = false, length = 64)
  private String token;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Surface surface;

  /** Sub-id when the surface needs one (run id, scan id, etc). Null for project-wide surfaces. */
  @Column(name = "scope_id", length = 255)
  private String scopeId;

  /** Optional human-readable label so the dashboard can label "Q4 demo for Acme". */
  @Column(length = 255)
  private String label;

  @Column(name = "created_by_user_id", nullable = false, length = 255)
  private String createdByUserId;

  /** Null = no expiry; admin-revocation only. */
  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_by_user_id", length = 255)
  private String revokedByUserId;

  @Column(nullable = false)
  @Builder.Default
  private Long views = 0L;

  @Column(name = "last_viewed_at")
  private Instant lastViewedAt;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  /** True if the link is still usable (not revoked + not past expiry). */
  public boolean isActive() {
    if (revokedAt != null) return false;
    return expiresAt == null || Instant.now().isBefore(expiresAt);
  }
}
