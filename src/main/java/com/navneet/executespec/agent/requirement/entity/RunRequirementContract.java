package com.navneet.executespec.agent.requirement.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.navneet.executespec.agent.requirement.RequirementContractDigests;
import com.navneet.executespec.agent.requirement.RequirementIrDocument;

import org.hibernate.annotations.Immutable;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Immutable persisted RequirementIR artifact bound to one exact executable-plan generation. */
@Entity
@Immutable
@Table(name = "run_requirement_contract")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RunRequirementContract {

  @Id
  @GeneratedValue
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "run_id", nullable = false, length = 255)
  private String runId;

  @Column(name = "schema_version", nullable = false)
  private int schemaVersion;

  @Column(name = "compiler_fingerprint", nullable = false, length = 64)
  private String compilerFingerprint;

  @Column(name = "source_sha256", nullable = false, length = 64)
  private String sourceSha256;

  @Column(name = "exact_plan_fingerprint", nullable = false, length = 64)
  private String exactPlanFingerprint;

  @Column(name = "canonical_artifact_json", nullable = false, columnDefinition = "TEXT")
  private String canonicalArtifactJson;

  @Column(name = "artifact_digest", nullable = false, length = 64)
  private String artifactDigest;

  @Column(name = "bound_digest", nullable = false, length = 64)
  private String boundDigest;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private RequirementIrDocument.Completeness completeness;

  @Column(name = "requirement_count", nullable = false)
  private int requirementCount;

  @Column(name = "acceptance_criterion_count", nullable = false)
  private int acceptanceCriterionCount;

  @Column(name = "task_binding_count", nullable = false)
  private int taskBindingCount;

  @Column(name = "issue_count", nullable = false)
  private int issueCount;

  @Column(name = "blocking_issue_count", nullable = false)
  private int blockingIssueCount;

  @Column(name = "compiled_at", nullable = false, insertable = false, updatable = false)
  private Instant compiledAt;

  @PrePersist
  void validateDigestBinding() {
    String expectedArtifactDigest =
        RequirementContractDigests.artifactDigest(canonicalArtifactJson);
    if (!java.util.Objects.equals(expectedArtifactDigest, artifactDigest)) {
      throw new IllegalStateException(
          "RequirementIR artifact digest does not match canonical bytes");
    }
    String expectedBoundDigest =
        RequirementContractDigests.boundDigest(
            exactPlanFingerprint, sourceSha256, expectedArtifactDigest);
    if (!java.util.Objects.equals(expectedBoundDigest, boundDigest)) {
      throw new IllegalStateException("RequirementIR binding digest does not match stored hashes");
    }
  }
}
