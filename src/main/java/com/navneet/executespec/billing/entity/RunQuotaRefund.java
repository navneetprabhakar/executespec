package com.navneet.executespec.billing.entity;

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
@Table(name = "run_quota_refund")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunQuotaRefund {

  @Id
  @Column(name = "run_id", length = 255)
  private String runId;

  @Column(name = "user_id", nullable = false, length = 255)
  private String userId;

  @Column(nullable = false)
  private int credits;

  @Column(nullable = false, length = 16)
  private String status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "applied_at")
  private Instant appliedAt;
}
