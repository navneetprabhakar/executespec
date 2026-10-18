package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable mutation authority and recovery record for one candidate workspace generation. */
@Entity
@Table(name = "workspace_generation_attempt")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceGenerationAttempt {

  @Id private UUID id;

  @Column(name = "run_id", nullable = false)
  private String runId;

  /** Exact immutable protocol copied from the parent run when this physical attempt is admitted. */
  @Column(name = "build_protocol_version", length = 32)
  private String buildProtocolVersion;

  /** V389 physical-command receipt contract; v1 is rolling-deploy compatibility only. */
  @Column(name = "verification_command_protocol_version", nullable = false)
  @Builder.Default
  private Short verificationCommandProtocolVersion = 1;

  /**
   * V386 immutable classification for pre-pin history; new supported attempts are not historical.
   */
  @Column(name = "historical_disposition", length = 32, nullable = false)
  @Builder.Default
  private String historicalDisposition = "NOT_APPLICABLE";

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "task_index", nullable = false)
  private Integer taskIndex;

  /** V401: a correction replaces this exact accepted prefix, without erasing its history. */
  @Column(name = "base_frontier_attempt_id")
  private UUID baseFrontierAttemptId;

  @Column(name = "frontier_task_index")
  private Integer frontierTaskIndex;

  @Column(name = "base_frontier_generation", length = 64)
  private String baseFrontierGeneration;

  @Column(name = "attempt_number", nullable = false)
  private Integer attemptNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "attempt_kind", nullable = false, length = 24)
  private WorkspaceGenerationKind attemptKind;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private WorkspaceGenerationState state;

  @Column(name = "workspace_root", nullable = false, columnDefinition = "TEXT")
  private String workspaceRoot;

  @Column(name = "before_generation", length = 64)
  private String beforeGeneration;

  @Column(name = "candidate_generation", length = 64)
  private String candidateGeneration;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "repositories_json", columnDefinition = "jsonb")
  private String repositoriesJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ignored_artifact_baseline_json", columnDefinition = "jsonb")
  private String ignoredArtifactBaselineJson;

  @Column(name = "ignored_artifact_baseline_digest", length = 64)
  private String ignoredArtifactBaselineDigest;

  @Column(name = "authority_digest", nullable = false, length = 64)
  private String authorityDigest;

  @Column(name = "task_contract_digest", nullable = false, length = 64)
  private String taskContractDigest;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "task_contract_json", columnDefinition = "jsonb")
  private String taskContractJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "initial_failure_evidence_json", columnDefinition = "jsonb")
  private String initialFailureEvidenceJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "observed_initial_failure_evidence_json", columnDefinition = "jsonb")
  private String observedInitialFailureEvidenceJson;

  @Column(name = "initial_failure_observed_at")
  private Instant initialFailureObservedAt;

  @Column(name = "requested_provider", length = 32)
  private String requestedProvider;

  @Column(name = "requested_model", length = 160)
  private String requestedModel;

  @Column(name = "effective_provider", length = 32)
  private String effectiveProvider;

  @Column(name = "effective_model", length = 160)
  private String effectiveModel;

  @Column(name = "worker_iterations")
  private Integer workerIterations;

  @Column(name = "worker_tool_calls")
  private Integer workerToolCalls;

  @Column(name = "model_calls")
  private Long modelCalls;

  @Column(name = "input_tokens")
  private Long inputTokens;

  @Column(name = "output_tokens")
  private Long outputTokens;

  @Column(name = "cache_read_tokens")
  private Long cacheReadTokens;

  @Column(name = "cache_write_tokens")
  private Long cacheWriteTokens;

  @Column(name = "reasoning_tokens")
  private Long reasoningTokens;

  @Column(name = "cost_usd", precision = 16, scale = 8)
  private java.math.BigDecimal costUsd;

  @Column(name = "workspace_lease_token")
  private UUID workspaceLeaseToken;

  @Column(name = "llm_operation_id", length = 64)
  private String llmOperationId;

  @Column(name = "llm_attempt_number")
  private Integer llmAttemptNumber;

  @Column(name = "llm_lease_token")
  private UUID llmLeaseToken;

  @Column(name = "before_checkpoint_kind", length = 24)
  private String beforeCheckpointKind;

  @Column(name = "before_checkpoint_key", columnDefinition = "TEXT")
  private String beforeCheckpointKey;

  @Column(name = "before_checkpoint_digest", length = 64)
  private String beforeCheckpointDigest;

  @Column(name = "before_checkpoint_completed_at")
  private Instant beforeCheckpointCompletedAt;

  @Column(name = "before_checkpoint_storage_generation", length = 128)
  private String beforeCheckpointStorageGeneration;

  @Column(name = "before_checkpoint_size_bytes")
  private Long beforeCheckpointSizeBytes;

  @Column(name = "before_checkpoint_crc32c", length = 64)
  private String beforeCheckpointCrc32c;

  @Column(name = "before_checkpoint_etag", length = 512)
  private String beforeCheckpointEtag;

  @Column(name = "checkpoint_kind", length = 24)
  private String checkpointKind;

  @Column(name = "checkpoint_key", columnDefinition = "TEXT")
  private String checkpointKey;

  @Column(name = "checkpoint_generation", length = 64)
  private String checkpointGeneration;

  @Column(name = "checkpoint_digest", length = 64)
  private String checkpointDigest;

  @Column(name = "checkpoint_completed_at")
  private Instant checkpointCompletedAt;

  @Column(name = "checkpoint_storage_generation", length = 128)
  private String checkpointStorageGeneration;

  @Column(name = "checkpoint_size_bytes")
  private Long checkpointSizeBytes;

  @Column(name = "checkpoint_crc32c", length = 64)
  private String checkpointCrc32c;

  @Column(name = "checkpoint_etag", length = 512)
  private String checkpointEtag;

  @Column(name = "rejected_diff_digest", length = 64)
  private String rejectedDiffDigest;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "rejected_diff_json", columnDefinition = "jsonb")
  private String rejectedDiffJson;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "recovery_failure_code", length = 64)
  private String recoveryFailureCode;

  @Column(name = "recovery_failure_reason", columnDefinition = "TEXT")
  private String recoveryFailureReason;

  @Column(name = "recovery_failure_at")
  private Instant recoveryFailureAt;

  @Column(name = "recovery_lease_token")
  private UUID recoveryLeaseToken;

  @Column(name = "recovery_started_at")
  private Instant recoveryStartedAt;

  @Column(name = "recovery_attempt_count", nullable = false)
  @Builder.Default
  private Integer recoveryAttemptCount = 0;

  @Column(name = "recovery_last_attempt_at")
  private Instant recoveryLastAttemptAt;

  @Column(name = "recovery_next_attempt_at")
  private Instant recoveryNextAttemptAt;

  @Column(name = "recovery_support_id")
  private UUID recoverySupportId;

  @Column(name = "recovery_exhausted_at")
  private Instant recoveryExhaustedAt;

  @Column(name = "task_transition_kind", length = 24)
  private String taskTransitionKind;

  @Column(name = "task_transition_state", length = 16)
  private String taskTransitionState;

  @Column(name = "task_transition_expected_status", length = 64)
  private String taskTransitionExpectedStatus;

  @Column(name = "task_transition_expected_recovery_attempt")
  private Integer taskTransitionExpectedRecoveryAttempt;

  @Column(name = "task_transition_completed_at")
  private Instant taskTransitionCompletedAt;

  @Column(name = "task_transition_failure_reason", columnDefinition = "TEXT")
  private String taskTransitionFailureReason;

  @Column(name = "task_transition_failure_at")
  private Instant taskTransitionFailureAt;

  @Column(name = "operation_transition_target", length = 16)
  private String operationTransitionTarget;

  @Column(name = "operation_transition_state", length = 16)
  private String operationTransitionState;

  @Column(name = "operation_transition_completed_at")
  private Instant operationTransitionCompletedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;
}
