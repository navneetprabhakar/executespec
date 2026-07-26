package com.navneet.executespec.harness.entity;

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

/** Durable, fenced continuation intent staged after a plan-gate admission decision. */
@Entity
@Table(name = "plan_gate_resume_outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanGateResumeOutbox {

  @Id
  @Column(name = "run_id", nullable = false, length = 255)
  private String runId;

  @Column(name = "gate_key", nullable = false, unique = true, length = 255)
  private String gateKey;

  /** Exact continuation bytes. Null is supported only for pre-V346 historical rows. */
  @Column(columnDefinition = "TEXT")
  private String payload;

  @Column(name = "payload_sha256", length = 64)
  private String payloadSha256;

  @Column(name = "handler_contract_version", nullable = false)
  private int handlerContractVersion;

  /** WAITING | READY | PROCESSING | COMPLETED | CANCELLED | DEAD. */
  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "next_retry_at", nullable = false)
  private Instant nextRetryAt;

  @Column(name = "last_error", columnDefinition = "TEXT")
  private String lastError;

  @Column(name = "lease_owner", length = 160)
  private String leaseOwner;

  @Column(name = "lease_token", columnDefinition = "uuid")
  private UUID leaseToken;

  @Column(name = "lease_expires_at")
  private Instant leaseExpiresAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
  private Instant updatedAt;

  @Column(name = "completed_at")
  private Instant completedAt;
}
