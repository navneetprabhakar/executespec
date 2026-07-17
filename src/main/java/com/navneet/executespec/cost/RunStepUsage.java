package com.navneet.executespec.cost;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

  @Column(name = "agent_role", nullable = false, length = 80)
  private String agentRole;

  @Column(name = "step_id", length = 120)
  private String stepId;

  @Column(name = "retry_of", length = 120)
  private String retryOf;

  @Column(name = "llm_execution_context_id", length = 64)
  private String llmExecutionContextId;

  @Column(name = "org_id", length = 64)
  private String orgId;

  @Column(name = "user_id", length = 64)
  private String userId;

  @Column(name = "project_id", length = 64)
  private String projectId;

  @Column(name = "repo_id")
  private UUID repoId;

  @Column(nullable = false, length = 80)
  private String feature;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType;

  @Column(name = "source_id", nullable = false, length = 160)
  private String sourceId;

  @Column(name = "key_source", nullable = false, length = 16)
  private String keySource;

  @Column(name = "attempt_status", nullable = false, length = 16)
  private String attemptStatus;

  @Column(name = "error_code", length = 64)
  private String errorCode;

  @Column(name = "error_redacted", columnDefinition = "text")
  private String errorRedacted;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Column(name = "latency_ms", nullable = false)
  private long latencyMs;

  @Column(name = "provider", nullable = false, length = 32)
  private String provider;

  @Column(name = "model", nullable = false, length = 128)
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
  private long callIndex;

  /**
   * Provider-usage metadata only. The recorder writes an explicit numeric allow-list; prompts,
   * request bodies, response text, tool arguments, headers, and credentials are never stored.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "provider_usage_raw_json", columnDefinition = "jsonb")
  private Map<String, Object> providerUsageRawJson;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;
}
