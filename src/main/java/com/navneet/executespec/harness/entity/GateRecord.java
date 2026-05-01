package com.navneet.executespec.harness.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Durable persistence for approval gate decisions (plan, merge, clarification).
 *
 * <p>Redis is the primary signal path; this table is the fallback so that a Redis restart or
 * eviction does not permanently lose a gate decision.
 *
 * <p>Written alongside Redis on every {@code setPlanGate} / {@code setMergeGate} call. On Redis
 * {@link org.springframework.data.redis.RedisConnectionFailureException}, the polling loop in
 * {@code GateStore} falls back to a DB read.
 */
@Entity
@Table(name = "gate_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GateRecord {

  /** Matches the Redis key format: {@code gate:plan:{runId}}, {@code gate:merge:{runId}}, etc. */
  @Id
  @Column(name = "gate_key", nullable = false, length = 255)
  private String gateKey;

  @Column(name = "run_id", nullable = false, length = 255)
  private String runId;

  /** PLAN | MERGE | CLARIFICATION */
  @Column(name = "gate_type", nullable = false, length = 30)
  private String gateType;

  /**
   * Gate status / resolution value.
   *
   * <ul>
   *   <li>PENDING — gate is open, awaiting decision
   *   <li>APPROVED / REJECTED — plan gate outcome (maps to boolean true/false)
   *   <li>{@link com.navneet.executespec.harness.MergeDecision} name — merge gate outcome
   *   <li>Clarification text — for CLARIFICATION gates
   * </ul>
   */
  @Column(name = "status", nullable = false, length = 30)
  private String status;

  @Column(name = "comment")
  private String comment;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  // ── Factory helpers ──────────────────────────────────────────────────────────

  public static GateRecord pending(String gateKey, String runId, String gateType) {
    GateRecord r = new GateRecord();
    r.gateKey = gateKey;
    r.runId = runId;
    r.gateType = gateType;
    r.status = "PENDING";
    r.createdAt = Instant.now();
    return r;
  }

  public boolean isResolved() {
    return resolvedAt != null;
  }
}
