package com.navneet.executespec.agentsession.entity;

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
 * General usage ledger for open-ended agent sessions.
 *
 * <p>Run billing stays on {@code pipeline_run}; this table gives CLI/VS Code conversation usage a
 * separate meter so future open-ended turns cannot silently consume run credits.
 */
@Entity
@Table(name = "agent_usage_ledger")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentUsageLedger {

  @Id private String id;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType;

  @Column(name = "source_id", nullable = false)
  private String sourceId;

  @Column(length = 32)
  private String provider;

  @Column(length = 128)
  private String model;

  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private long inputTokens = 0;

  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private long outputTokens = 0;

  @Column(name = "reasoning_tokens", nullable = false)
  @Builder.Default
  private long reasoningTokens = 0;

  @Column(name = "cache_read_tokens", nullable = false)
  @Builder.Default
  private long cacheReadTokens = 0;

  @Column(name = "cost_usd", nullable = false)
  @Builder.Default
  private double costUsd = 0.0;

  @Column(name = "billable_units", nullable = false)
  @Builder.Default
  private long billableUnits = 0;

  @Column(name = "billing_policy", nullable = false, length = 32)
  @Builder.Default
  private String billingPolicy = "INCLUDED";

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
