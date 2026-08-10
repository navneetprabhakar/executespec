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

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "task_index", nullable = false)
  private Integer taskIndex;

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

  @Column(name = "before_generation", nullable = false, length = 64)
  private String beforeGeneration;

  @Column(name = "candidate_generation", length = 64)
  private String candidateGeneration;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "repositories_json", nullable = false, columnDefinition = "jsonb")
  private String repositoriesJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ignored_artifact_baseline_json", nullable = false, columnDefinition = "jsonb")
  private String ignoredArtifactBaselineJson;

  @Column(name = "ignored_artifact_baseline_digest", nullable = false, length = 64)
  private String ignoredArtifactBaselineDigest;

  @Column(name = "authority_digest", nullable = false, length = 64)
  private String authorityDigest;

  @Column(name = "task_contract_digest", nullable = false, length = 64)
  private String taskContractDigest;

  @Column(name = "workspace_lease_token", nullable = false)
  private UUID workspaceLeaseToken;

  @Column(name = "llm_operation_id", length = 64)
  private String llmOperationId;

  @Column(name = "llm_attempt_number")
  private Integer llmAttemptNumber;

  @Column(name = "llm_lease_token")
  private UUID llmLeaseToken;

  @Column(name = "before_checkpoint_kind", nullable = false, length = 24)
  private String beforeCheckpointKind;

  @Column(name = "before_checkpoint_key", nullable = false, columnDefinition = "TEXT")
  private String beforeCheckpointKey;

  @Column(name = "before_checkpoint_digest", nullable = false, length = 64)
  private String beforeCheckpointDigest;

  @Column(name = "before_checkpoint_completed_at", nullable = false)
  private Instant beforeCheckpointCompletedAt;

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

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;
}
