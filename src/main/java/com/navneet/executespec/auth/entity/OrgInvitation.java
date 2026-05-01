package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "org_invitation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgInvitation {

  @Id private String id;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "invited_email", nullable = false)
  private String invitedEmail;

  @Column(name = "invited_by", nullable = false)
  private String invitedBy;

  /** ORG_ADMIN | DEVELOPER | BILLING_ADMIN */
  @Column(name = "org_role", nullable = false)
  @Builder.Default
  private String orgRole = "DEVELOPER";

  @Column(nullable = false, unique = true)
  private String token;

  /** PENDING | ACCEPTED | REJECTED | EXPIRED | ESCALATED */
  @Column(nullable = false)
  @Builder.Default
  private String status = "PENDING";

  @Column(name = "escalation_note", columnDefinition = "TEXT")
  private String escalationNote;

  /** Comma-separated project IDs to auto-assign on accept */
  @Column(name = "project_ids", columnDefinition = "TEXT")
  private String projectIds;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "resolved_at")
  private Instant resolvedAt;
}
