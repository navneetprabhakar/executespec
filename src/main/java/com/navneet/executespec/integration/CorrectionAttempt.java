package com.navneet.executespec.integration;

import java.time.Instant;
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

/** Durable authority for one reversible correction of a run workspace. */
@Entity
@Table(name = "correction_attempt")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class CorrectionAttempt {

  @Id private UUID id;

  @Column(name = "run_id", nullable = false)
  private String runId;

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(nullable = false, length = 32)
  private String phase;

  @Column(name = "workspace_root", nullable = false, columnDefinition = "TEXT")
  private String workspaceRoot;

  @Column(name = "before_generation", nullable = false, length = 64)
  private String beforeGeneration;

  @Column(name = "after_generation", length = 64)
  private String afterGeneration;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "repositories_json", nullable = false, columnDefinition = "jsonb")
  private String repositoriesJson;

  @Column(name = "evidence_report_id")
  private String evidenceReportId;

  @Column(name = "evidence_status", length = 20)
  private String evidenceStatus;

  @Column(name = "evidence_workspace_generation", length = 64)
  private String evidenceWorkspaceGeneration;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence_snapshot_json", columnDefinition = "jsonb")
  private String evidenceSnapshotJson;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "task_snapshot_json", nullable = false, columnDefinition = "jsonb")
  private String taskSnapshotJson;

  @Column(name = "snapshot_schema_version", nullable = false)
  private Integer snapshotSchemaVersion;

  @Column(name = "evidence_snapshot_digest", nullable = false, length = 64)
  private String evidenceSnapshotDigest;

  @Column(name = "approval_id", length = 36)
  private String approvalId;

  @Column(name = "proposal_digest", length = 64)
  private String proposalDigest;

  @Column(name = "lease_token", nullable = false)
  private UUID leaseToken;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;
}
