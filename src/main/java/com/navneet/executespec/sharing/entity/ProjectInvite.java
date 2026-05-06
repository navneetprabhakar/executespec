package com.navneet.executespec.sharing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * V175 — pending invitation to join a project as an external member. Created by a project admin via
 * {@code POST /api/projects/{id}/invites}; consumed by the recipient via {@code POST
 * /api/invites/accept?token=...}.
 *
 * <p>The invite is addressed to an email — the recipient may or may not have an account at invite
 * time. On accept: if the email matches an existing {@link
 * com.navneet.executespec.auth.entity.AppUser}, a {@link
 * com.navneet.executespec.auth.entity.ProjectMember} row is created and the invite is marked
 * ACCEPTED. If not, the recipient is sent through the standard signup flow with the invite token
 * preserved; the invite is consumed once they verify their email + the new account is linked.
 *
 * <p>Status transitions: PENDING → ACCEPTED (recipient accepted) | REVOKED (admin revoked) |
 * EXPIRED (past expires_at, swept by a future scheduled job). Tokens are 32-byte URL-safe random
 * strings.
 */
@Entity
@Table(name = "project_invite")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectInvite {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 255)
  private String projectId;

  @Column(nullable = false, length = 255)
  private String email;

  /** PROJECT_ADMIN | PROJECT_DEVELOPER | PROJECT_VIEWER. */
  @Column(name = "project_role", nullable = false, length = 32)
  @Builder.Default
  private String projectRole = "PROJECT_VIEWER";

  @Column(nullable = false, length = 64)
  private String token;

  @Column(name = "invited_by_user_id", nullable = false, length = 255)
  private String invitedByUserId;

  /** Org id of the inviter at invite time — preserved so accept can validate even if it changes. */
  @Column(name = "invited_org_id", length = 255)
  private String invitedOrgId;

  /** PENDING | ACCEPTED | REVOKED | EXPIRED. */
  @Column(nullable = false, length = 16)
  @Builder.Default
  private String status = "PENDING";

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "accepted_by_user_id", length = 255)
  private String acceptedByUserId;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_by_user_id", length = 255)
  private String revokedByUserId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
