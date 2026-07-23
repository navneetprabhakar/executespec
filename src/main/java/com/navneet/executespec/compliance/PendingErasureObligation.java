package com.navneet.executespec.compliance;

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
 * RR-090 — a durable, retryable GDPR (Article 17) erasure obligation.
 *
 * <p>When {@code DataRetentionService.eraseUserData} cannot confirm that every one of a user's live
 * runs reached a terminal status within the bounded settle window, it records the residual here
 * instead of dropping the incompleteness into a log line. The daily retention sweep re-attempts
 * erasure for each open obligation and deletes the row once no live run remains, so the user's data
 * cannot survive erasure indefinitely.
 *
 * <p>One row per user ({@code userId} is the id) keeps the obligation idempotent across a repeated
 * request and the scheduled retry.
 */
@Entity
@Table(name = "pending_erasure_obligation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingErasureObligation {

  @Id
  @Column(name = "user_id", length = 255)
  private String userId;

  /** When the very first erasure request for this user was made; preserved across retries. */
  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  /** When the most recent erasure attempt for this user ran. */
  @Column(name = "last_attempt_at", nullable = false)
  private Instant lastAttemptAt;

  /** Number of erasure attempts made so far (initial + retries). */
  @Column(nullable = false)
  private int attempts;

  /** Count of the user's runs still non-terminal after the last attempt's bounded settle window. */
  @Column(name = "live_runs_remaining", nullable = false)
  private long liveRunsRemaining;

  /** Short, secret-free diagnostic for why the last attempt did not complete. */
  @Column(name = "last_reason", length = 255)
  private String lastReason;
}
