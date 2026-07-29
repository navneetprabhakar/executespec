package com.navneet.executespec.cost;

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

/** Immutable write-ahead fact proving that an audited physical provider attempt was authorized. */
@Entity
@Table(name = "llm_usage_attempt_intent")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageAttemptIntent {
  @Id
  @Column(name = "physical_attempt_id", length = 160)
  private String physicalAttemptId;

  @Column(name = "logical_call_id", nullable = false, length = 96)
  private String logicalCallId;

  @Column(name = "parent_attempt_id", length = 160)
  private String parentAttemptId;

  @Column(name = "attempt_kind", nullable = false, length = 24)
  private String attemptKind;

  @Column(name = "attempt_ordinal", nullable = false)
  private long attemptOrdinal;

  @Column(name = "llm_execution_context_id", length = 64)
  private String llmExecutionContextId;

  @Column(name = "correlation_id", nullable = false, length = 96)
  private String correlationId;

  @Column(name = "org_id", length = 64)
  private String orgId;

  @Column(name = "user_id", length = 64)
  private String userId;

  @Column(name = "project_id", length = 64)
  private String projectId;

  @Column(nullable = false, length = 80)
  private String feature;

  @Column(name = "source_type", nullable = false, length = 32)
  private String sourceType;

  @Column(name = "source_id", nullable = false, length = 160)
  private String sourceId;

  @Column(name = "run_id", nullable = false, length = 96)
  private String runId;

  @Column(name = "task_id", length = 160)
  private String taskId;

  @Column(name = "agent_role", nullable = false, length = 80)
  private String agentRole;

  /** Most-scoped project/org/global role captured before dispatch; never re-resolved later. */
  @Column(name = "human_role_snapshot", nullable = false, length = 40)
  private String humanRoleSnapshot;

  /**
   * RR-430 — how {@link #humanRoleSnapshot} was obtained: {@code CAPTURED_AT_DISPATCH} (recorded
   * before the provider attempt), {@code BACKFILLED_AT_MIGRATION} (reconstructed by a data
   * migration), or {@code UNKNOWN}. Null legacy rows (pre-V337) are read as UNKNOWN so a
   * reconstructed role is never presented with the authority of a true snapshot.
   */
  @Column(name = "role_provenance", length = 40)
  private String roleProvenance;

  @Column(name = "step_id", length = 120)
  private String stepId;

  @Column(nullable = false, length = 32)
  private String provider;

  @Column(nullable = false, length = 128)
  private String model;

  @Column(name = "model_tier", nullable = false, length = 32)
  private String modelTier;

  @Column(name = "key_source", nullable = false, length = 16)
  private String keySource;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  /**
   * V351/V354: 0 is a legacy row without operation-fence fields. Durable-context attribution is
   * accepted for previous-binary rollback compatibility; 1 is atomically operation-fenced.
   */
  @Column(name = "operation_fence_version", nullable = false)
  @Builder.Default
  private short operationFenceVersion = 0;

  @Column(name = "llm_execution_operation_id", length = 64)
  private String llmExecutionOperationId;

  @Column(name = "llm_execution_operation_attempt")
  private Integer llmExecutionOperationAttempt;

  @Column(name = "llm_execution_lease_token")
  private UUID llmExecutionLeaseToken;

  @Column(name = "operation_not_reclaim_before")
  private Instant operationNotReclaimBefore;

  @Column(name = "transport_deadline_at")
  private Instant transportDeadlineAt;
}
