package com.navneet.executespec.harness.entity;

import java.time.Instant;
import java.util.UUID;

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

  /** Positive values identify a versioned admission receipt; {@code null} means legacy/unbound. */
  @Column(name = "admission_contract_version")
  private Integer admissionContractVersion;

  /** LEGACY | SHADOW | ENFORCED. */
  @Column(name = "binding_mode", length = 16)
  private String bindingMode;

  /** UNBOUND | BOUND | BLOCKED | MISSING | INVALID | UNAVAILABLE. */
  @Column(name = "binding_status", length = 16)
  private String bindingStatus;

  @Column(name = "requirement_contract_id", columnDefinition = "uuid")
  private UUID requirementContractId;

  @Column(name = "binding_schema_version")
  private Integer bindingSchemaVersion;

  @Column(name = "binding_compiler_fingerprint", length = 64)
  private String bindingCompilerFingerprint;

  @Column(name = "binding_source_sha256", length = 64)
  private String bindingSourceSha256;

  @Column(name = "binding_exact_plan_fingerprint", length = 64)
  private String bindingExactPlanFingerprint;

  @Column(name = "binding_artifact_digest", length = 64)
  private String bindingArtifactDigest;

  @Column(name = "binding_bound_digest", length = 64)
  private String bindingBoundDigest;

  @Column(name = "binding_bound_at")
  private Instant bindingBoundAt;

  @Column(name = "binding_reason", length = 512)
  private String bindingReason;

  @Column(name = "contract_gate_report_digest", length = 64)
  private String contractGateReportDigest;

  @Column(name = "continuation_schema_version")
  private Integer continuationSchemaVersion;

  @Column(name = "continuation_sha256", length = 64)
  private String continuationSha256;

  /** Canonical exact bytes; deliberately TEXT rather than JSONB so the digest remains stable. */
  @Column(name = "approval_evidence_json", columnDefinition = "TEXT")
  private String approvalEvidenceJson;

  @Column(name = "approval_evidence_digest", length = 64)
  private String approvalEvidenceDigest;

  @Column(name = "resolved_by_user_id", length = 64)
  private String resolvedByUserId;

  @Column(name = "resolved_by_role", length = 64)
  private String resolvedByRole;

  @Column(name = "resolution_source", length = 64)
  private String resolutionSource;

  @Column(name = "resolution_reason", length = 512)
  private String resolutionReason;

  @Column(name = "billing_owner_user_id", length = 64)
  private String billingOwnerUserId;

  @Column(name = "billing_org_id", length = 64)
  private String billingOrgId;

  @Column(name = "admitted_at")
  private Instant admittedAt;

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
