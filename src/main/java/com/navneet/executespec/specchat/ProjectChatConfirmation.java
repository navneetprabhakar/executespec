package com.navneet.executespec.specchat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable, one-shot authority for one explicit Project Chat action. The bearer secret is returned
 * once and only its SHA-256 digest is persisted. Target JSON is canonical, bounded and
 * action-specific; it may contain identifiers/branch/doc type, never credentials or prompts.
 */
@Entity
@Table(name = "project_chat_confirmation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectChatConfirmation {

  public enum Status {
    PENDING,
    CONSUMED,
    EXPIRED
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false)
  private String projectId;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "actor_user_id", nullable = false)
  private String actorUserId;

  @Column(name = "action", nullable = false, length = 48)
  private String action;

  @Column(name = "capability", nullable = false, length = 96)
  private String capability;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "model", nullable = false, length = 160)
  private String model;

  @Column(name = "target_json", nullable = false, columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String targetJson;

  @Column(name = "target_hash", nullable = false, length = 64)
  private String targetHash;

  @Column(name = "scope_hash", nullable = false, length = 64)
  private String scopeHash;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  @Builder.Default
  private Status status = Status.PENDING;

  @Column(name = "issued_at", nullable = false)
  @Builder.Default
  private Instant issuedAt = Instant.now();

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;
}
