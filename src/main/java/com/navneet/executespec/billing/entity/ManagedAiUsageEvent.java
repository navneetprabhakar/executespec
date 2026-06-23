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
@Table(name = "managed_ai_usage_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ManagedAiUsageEvent {

  @Id private String id;

  @Column(name = "period_id", nullable = false)
  private String periodId;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "org_id")
  private String orgId;

  @Column(name = "run_id")
  private String runId;

  @Column(name = "agent_session_id")
  private String agentSessionId;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType;

  @Column(length = 32)
  private String provider;

  @Column(length = 128)
  private String model;

  @Column(name = "model_class", length = 32)
  private String modelClass;

  @Column(name = "agent_role", length = 80)
  private String agentRole;

  @Column(name = "step_id", length = 120)
  private String stepId;

  @Column(name = "input_tokens", nullable = false)
  private long inputTokens;

  @Column(name = "output_tokens", nullable = false)
  private long outputTokens;

  @Column(name = "cache_read_tokens", nullable = false)
  private long cacheReadTokens;

  @Column(name = "cache_write_tokens", nullable = false)
  private long cacheWriteTokens;

  @Column(name = "reasoning_tokens", nullable = false)
  private long reasoningTokens;

  @Column(name = "raw_cost_usd", nullable = false)
  private double rawCostUsd;

  @Column(name = "retail_multiplier", nullable = false)
  private double retailMultiplier;

  @Column(name = "ai_credits_charged", nullable = false)
  private long aiCreditsCharged;

  @Column(name = "routing_state_before", nullable = false, length = 32)
  private String routingStateBefore;

  @Column(name = "routing_state_after", nullable = false, length = 32)
  private String routingStateAfter;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
