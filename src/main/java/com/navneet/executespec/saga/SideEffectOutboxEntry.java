package com.navneet.executespec.saga;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "side_effect_outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SideEffectOutboxEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "run_id", nullable = false)
  private String runId;

  @Column(name = "effect_type", nullable = false)
  private String effectType;

  @Column(columnDefinition = "JSONB", nullable = false)
  private String payload; // JSON string

  @Column(nullable = false)
  private String status; // PENDING | PROCESSING | COMPLETED | FAILED

  @Column(nullable = false)
  private int attempt;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "handler_contract_version", nullable = false, length = 32)
  private String handlerContractVersion;

  @Column(name = "next_retry_at", nullable = false)
  private Instant nextRetryAt;

  @Column(name = "last_error")
  private String lastError;

  @Column(name = "lease_owner", length = 160)
  private String leaseOwner;

  @Column(name = "lease_token", nullable = false)
  private long leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "last_attempt_started_at")
  private Instant lastAttemptStartedAt;

  @Column(name = "last_attempt_completed_at")
  private Instant lastAttemptCompletedAt;

  @Column(name = "last_attempt_evidence", columnDefinition = "JSONB")
  private String lastAttemptEvidence;

  @Column(name = "terminal_evidence", columnDefinition = "JSONB")
  private String terminalEvidence;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) createdAt = Instant.now();
    if (idempotencyKey == null) idempotencyKey = "side-effect:" + UUID.randomUUID();
  }
}
