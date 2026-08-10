package com.navneet.executespec.workspacegeneration;

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

/** Immutable deterministic verification evidence bound to one exact candidate generation. */
@Entity
@Table(name = "workspace_generation_verification_receipt")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceGenerationVerificationReceipt {

  @Id private UUID id;

  @Column(name = "attempt_id", nullable = false)
  private UUID attemptId;

  @Column(name = "receipt_version", nullable = false)
  private Integer receiptVersion;

  @Column(name = "candidate_generation", nullable = false, length = 64)
  private String candidateGeneration;

  @Column(name = "task_contract_digest", nullable = false, length = 64)
  private String taskContractDigest;

  @Column(name = "verifier_kind", nullable = false, length = 32)
  private String verifierKind;

  @Column(name = "command_source", nullable = false, length = 32)
  private String commandSource;

  @Column(name = "command_digest", nullable = false, length = 64)
  private String commandDigest;

  @Column(nullable = false, length = 16)
  private String outcome;

  @Column(name = "build_passed", nullable = false)
  private boolean buildPassed;

  @Column(name = "tests_executed", nullable = false)
  private boolean testsExecuted;

  @Column(name = "tests_passed", nullable = false)
  private Integer testsPassed;

  @Column(name = "tests_failed", nullable = false)
  private Integer testsFailed;

  @Column(name = "tests_errors", nullable = false)
  private Integer testsErrors;

  @Column(name = "tests_skipped", nullable = false)
  private Integer testsSkipped;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "evidence_json", nullable = false, columnDefinition = "jsonb")
  private String evidenceJson;

  @Column(name = "evidence_digest", nullable = false, length = 64)
  private String evidenceDigest;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at", nullable = false)
  private Instant completedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
