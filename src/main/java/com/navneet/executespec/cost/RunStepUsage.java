package com.navneet.executespec.cost;

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
@Table(name = "run_step_usage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunStepUsage {

  @Id private String id;

  @Column(name = "run_id", nullable = false, length = 96)
  private String runId;

  @Column(name = "task_id", length = 160)
  private String taskId;

  @Column(name = "agent_role", length = 80)
  private String agentRole;

  @Column(name = "step_id", length = 120)
  private String stepId;

  @Column(name = "retry_of", length = 120)
  private String retryOf;

  @Column(name = "provider", length = 32)
  private String provider;

  @Column(name = "model", length = 128)
  private String model;

  @Column(name = "input_tokens", nullable = false)
  private long inputTokens;

  @Column(name = "output_tokens", nullable = false)
  private long outputTokens;

  @Column(name = "cache_write_tokens", nullable = false)
  private long cacheWriteTokens;

  @Column(name = "cache_read_tokens", nullable = false)
  private long cacheReadTokens;

  @Column(name = "reasoning_tokens", nullable = false)
  private long reasoningTokens;

  @Column(name = "cost_usd", nullable = false)
  private double costUsd;

  @Column(name = "call_index", nullable = false)
  private int callIndex;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
