package com.navneet.executespec.specchat;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit row for the CLI/Plugin chat endpoint ({@code POST /api/spec/chat}). One row per
 * client-generated session — NO message content is stored (decision #2 in {@code
 * docs/design/cli-plugin-spec-shape-b-2026-05-28.md}: chat persistence is LOCAL-ONLY).
 *
 * <p>Used for: usage / cost / provider-mix telemetry, forensic revoke of a compromised PAT session,
 * and enterprise per-session billing. V218 (RL-2026-05-052).
 */
@Entity
@Table(name = "spec_chat_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecChatAudit {

  @Id private String id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "project_id")
  private String projectId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "started_at", nullable = false)
  @Builder.Default
  private Instant startedAt = Instant.now();

  @Column(name = "last_turn_at", nullable = false)
  @Builder.Default
  private Instant lastTurnAt = Instant.now();

  @Column(name = "turn_count", nullable = false)
  @Builder.Default
  private int turnCount = 0;

  @Column(name = "cost_usd", nullable = false, precision = 12, scale = 4)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  @Column(name = "model_used", length = 128)
  private String modelUsed;

  @Column(name = "ip_hash", length = 64)
  private String ipHash;

  @Column(name = "closed_at")
  private Instant closedAt;
}
