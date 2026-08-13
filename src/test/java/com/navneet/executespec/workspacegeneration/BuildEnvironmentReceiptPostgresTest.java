package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof that environment receipts are append-only state-transition authority. */
@Tag("integration")
@Testcontainers
class BuildEnvironmentReceiptPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("build_environment_receipt")
          .withUsername("test")
          .withPassword("test");

  @Test
  void sharedProtocolCannotVerifyWithoutPassedEnvironmentReceipts() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    String runId = "run-r6-environment";
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    String candidate = "c".repeat(64);
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES ('run-r6-e_T1',?,'WORKING',0,NOW())",
        runId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'BUILD_GENERATION',?::uuid,"
            + "NOW() + INTERVAL '10 minutes',NOW())",
        runId,
        workspaceToken.toString());
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        attemptId,
        runId,
        "T1",
        0,
        1,
        "/tmp/run-r6-environment",
        workspaceToken,
        "a".repeat(64),
        "[]",
        "b".repeat(64),
        "d".repeat(64),
        "{}",
        DigestUtils.sha256Hex("{}"),
        "GCS",
        "gs://fixture/before.zip",
        "e".repeat(64),
        "ctx-r6-environment",
        "op-r6-environment",
        UUID.randomUUID(),
        Duration.ofMinutes(10));
    jdbc.update("UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", attemptId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + "candidate_generation=? WHERE id=?",
                    candidate,
                    attemptId))
        .rootCause()
        .hasMessageContaining("requires passed probe and worker environment receipts");

    BuildEnvironmentReceiptStore store = new BuildEnvironmentReceiptStore(jdbc);
    BuildEnvironmentPlan plan = plan();
    recordPassed(store, attemptId, "INITIAL_PROBE", plan);
    recordPassed(store, attemptId, "WORKER", plan);
    store.requirePassed(attemptId, "WORKER", "INITIAL_PROBE");
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='VERIFYING',candidate_generation=?"
            + " WHERE id=?",
        candidate,
        attemptId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFIED' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("requires a passed final environment receipt");

    assertThatThrownBy(() -> recordPassed(store, attemptId, "WORKER", plan))
        .hasMessage("BUILD_ENVIRONMENT_RECEIPT_ALREADY_EXISTS");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_environment_receipt SET disposition='FAILED'"
                        + " WHERE attempt_id=? AND phase='WORKER'",
                    attemptId))
        .rootCause()
        .hasMessageContaining("environment receipts are append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "DELETE FROM workspace_generation_environment_receipt"
                        + " WHERE attempt_id=? AND phase='WORKER'",
                    attemptId))
        .rootCause()
        .hasMessageContaining("environment receipts are append-only");

    String removedJson = "[\"/tmp/run-r6-environment:node_modules/x.js\"]";
    store.recordCleanup(
        attemptId,
        "CANDIDATE_CLEANUP",
        removedJson,
        DigestUtils.sha256Hex(removedJson),
        "{}",
        DigestUtils.sha256Hex("{}"));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_generation_environment_receipt"
                    + " WHERE attempt_id=?",
                Integer.class,
                attemptId))
        .isEqualTo(3);
  }

  private static void recordPassed(
      BuildEnvironmentReceiptStore store, UUID attemptId, String phase, BuildEnvironmentPlan plan) {
    store.recordMaterialization(
        attemptId, phase, plan, "PASSED", "{}", DigestUtils.sha256Hex("{}"));
  }

  private static BuildEnvironmentPlan plan() {
    return new BuildEnvironmentPlan(
        1,
        "toolchain@sha256:" + "f".repeat(64),
        "DENY_ALL",
        "IMAGE_PRESEEDED_OFFLINE_ONLY",
        List.of(),
        "1".repeat(64),
        "2".repeat(64));
  }
}
