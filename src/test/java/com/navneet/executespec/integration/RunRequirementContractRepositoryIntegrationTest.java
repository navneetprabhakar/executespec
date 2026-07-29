package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.agent.requirement.RequirementContractDigests;
import com.navneet.executespec.agent.requirement.RequirementIrCompiler;
import com.navneet.executespec.agent.requirement.RequirementIrDocument.Completeness;
import com.navneet.executespec.agent.requirement.entity.RunRequirementContract;
import com.navneet.executespec.agent.requirement.repository.RunRequirementContractRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL proof for the V345 immutable RequirementIR repository contract. */
class RunRequirementContractRepositoryIntegrationTest extends PostgresIntegrationBase {

  @Autowired RunRequirementContractRepository repository;
  @Autowired JdbcTemplate jdbc;

  @Test
  void repositoryFindsExactGenerationAndDatabaseRejectsMutation() {
    String runId = "requirement-contract-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run (run_id,status,started_at) VALUES (?,?,?)",
        runId,
        "PLANNING",
        Timestamp.from(Instant.now()));

    String sourceSha = "a".repeat(64);
    String planSha = "b".repeat(64);
    String canonicalArtifact = "{\"schemaVersion\":1}";
    String artifactDigest = RequirementContractDigests.artifactDigest(canonicalArtifact);
    String boundDigest = RequirementContractDigests.boundDigest(planSha, sourceSha, artifactDigest);
    RunRequirementContract saved =
        repository.saveAndFlush(
            RunRequirementContract.builder()
                .runId(runId)
                .schemaVersion(1)
                .compilerFingerprint(RequirementIrCompiler.COMPILER_FINGERPRINT)
                .sourceSha256(sourceSha)
                .exactPlanFingerprint(planSha)
                .canonicalArtifactJson(canonicalArtifact)
                .artifactDigest(artifactDigest)
                .boundDigest(boundDigest)
                .completeness(Completeness.READY)
                .requirementCount(1)
                .acceptanceCriterionCount(1)
                .taskBindingCount(1)
                .issueCount(0)
                .blockingIssueCount(0)
                .build());

    assertThat(saved.getId()).isNotNull();
    var reloaded =
        repository
            .findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
                runId, planSha, 1, RequirementIrCompiler.COMPILER_FINGERPRINT)
            .orElseThrow();
    assertThat(reloaded.getId()).isEqualTo(saved.getId());
    assertThat(reloaded.getCompiledAt()).isNotNull();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE run_requirement_contract SET completeness='BLOCKED' WHERE id=?",
                    saved.getId()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("immutable");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    INSERT INTO run_requirement_contract (
                      run_id,schema_version,compiler_fingerprint,source_sha256,
                      exact_plan_fingerprint,canonical_artifact_json,artifact_digest,bound_digest,
                      completeness,requirement_count,acceptance_criterion_count,task_binding_count,
                      issue_count,blocking_issue_count)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,
                    runId,
                    1,
                    RequirementIrCompiler.COMPILER_FINGERPRINT,
                    sourceSha,
                    planSha,
                    canonicalArtifact,
                    artifactDigest,
                    boundDigest,
                    "READY",
                    1,
                    1,
                    1,
                    0,
                    0))
        .isInstanceOf(DataAccessException.class);

    assertThat(
            jdbc.update(
                """
                INSERT INTO run_requirement_contract (
                  run_id,schema_version,compiler_fingerprint,source_sha256,
                  exact_plan_fingerprint,canonical_artifact_json,artifact_digest,bound_digest,
                  completeness,requirement_count,acceptance_criterion_count,task_binding_count,
                  issue_count,blocking_issue_count)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                runId,
                1,
                RequirementIrCompiler.COMPILER_FINGERPRINT,
                sourceSha,
                "e".repeat(64),
                canonicalArtifact,
                artifactDigest,
                RequirementContractDigests.boundDigest("e".repeat(64), sourceSha, artifactDigest),
                "READY",
                1,
                1,
                1,
                0,
                0))
        .isEqualTo(1);

    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_requirement_contract WHERE run_id=?", Long.class, runId))
        .isZero();
  }

  @Test
  void jpaPersistenceBoundaryRejectsArtifactAndBindingDigestMismatch() {
    String runId = "invalid-requirement-contract-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run (run_id,status,started_at) VALUES (?,?,?)",
        runId,
        "PLANNING",
        Timestamp.from(Instant.now()));

    RunRequirementContract invalid =
        RunRequirementContract.builder()
            .runId(runId)
            .schemaVersion(1)
            .compilerFingerprint(RequirementIrCompiler.COMPILER_FINGERPRINT)
            .sourceSha256("a".repeat(64))
            .exactPlanFingerprint("b".repeat(64))
            .canonicalArtifactJson("{\"schemaVersion\":1}")
            .artifactDigest("c".repeat(64))
            .boundDigest("d".repeat(64))
            .completeness(Completeness.READY)
            .requirementCount(1)
            .acceptanceCriterionCount(1)
            .taskBindingCount(1)
            .issueCount(0)
            .blockingIssueCount(0)
            .build();

    assertThatThrownBy(() -> repository.saveAndFlush(invalid))
        .hasRootCauseInstanceOf(IllegalStateException.class)
        .hasMessageContaining("artifact digest");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_requirement_contract WHERE run_id=?", Long.class, runId))
        .isZero();

    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
  }

  @Test
  void legacyRunWithoutARequirementContractRemainsReadableAsAnEmptyLookup() {
    String runId = "legacy-requirement-contract-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run (run_id,status,started_at,plan_json) VALUES (?,?,?,?)",
        runId,
        "AWAITING_APPROVAL",
        Timestamp.from(Instant.now()),
        "{\"planTitle\":\"legacy plan\"}");

    assertThat(
            repository.findByRunIdAndExactPlanFingerprintAndSchemaVersionAndCompilerFingerprint(
                runId, "b".repeat(64), 1, RequirementIrCompiler.COMPILER_FINGERPRINT))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT plan_json FROM pipeline_run WHERE run_id=?", String.class, runId))
        .isEqualTo("{\"planTitle\":\"legacy plan\"}");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_requirement_contract WHERE run_id=?", Long.class, runId))
        .isZero();

    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
  }
}
