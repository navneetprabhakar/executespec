package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof that rollback resolution is fenced by the exact live authority. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationRollbackAuthorityPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_rollback_authority")
          .withUsername("test")
          .withPassword("test");

  @Test
  void expiredOriginalCannotResolveAndExpiredRecoveryCanBeFencedAndReplaced() {
    JdbcTemplate jdbc = migratedDatabase();

    String runId = "run-rollback-authority";
    UUID attemptId = UUID.randomUUID();
    UUID original = UUID.randomUUID();
    UUID recovery = UUID.randomUUID();
    insertRunAndAttempt(jdbc, runId, attemptId, original, "PREPARED");
    insertAuthority(jdbc, runId, "BUILD_GENERATION", original, "10 minutes");

    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
                    + " rejected_diff_digest=repeat('a',64), rejected_diff_json='{}'::jsonb,"
                    + " failure_code='RUNTIME_CONTRACT_FAILED', failure_reason='red' WHERE id=?",
                attemptId))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE run_workspace_authority SET lease_expires_at=now()-interval '1 second'"
            + " WHERE run_id=?",
        runId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='ROLLED_BACK' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation rollback authority is not current");

    replaceAuthority(jdbc, runId, "RECOVERY", recovery);
    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET recovery_lease_token=?::uuid,"
                    + " recovery_started_at=now() WHERE id=? AND state='ROLLING_BACK'",
                recovery.toString(),
                attemptId))
        .isEqualTo(1);
    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET state='ROLLED_BACK',"
                    + " task_transition_kind='REOPEN_PENDING', task_transition_state='PENDING'"
                    + " WHERE id=? AND recovery_lease_token=?::uuid",
                attemptId,
                recovery.toString()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_expected_status FROM workspace_generation_attempt"
                    + " WHERE id=?",
                String.class,
                attemptId))
        .isEqualTo("WORKING");
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_expected_recovery_attempt"
                    + " FROM workspace_generation_attempt WHERE id=?",
                Integer.class,
                attemptId))
        .isZero();
    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET task_transition_state='COMPLETED',"
                    + " task_transition_completed_at=now() WHERE id=?",
                attemptId))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET failure_reason='tampered' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("resolved workspace generation attempts are immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET task_transition_state='PENDING',"
                        + " task_transition_completed_at=NULL WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation task-transition receipt is immutable");

    String takeoverRun = "run-recovery-takeover";
    UUID takeoverAttempt = UUID.randomUUID();
    UUID originalWorkspace = UUID.randomUUID();
    UUID staleRecovery = UUID.randomUUID();
    UUID replacementRecovery = UUID.randomUUID();
    insertRunAndAttempt(jdbc, takeoverRun, takeoverAttempt, originalWorkspace, "PREPARED");
    insertAuthority(jdbc, takeoverRun, "BUILD_GENERATION", originalWorkspace, "10 minutes");
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
            + " rejected_diff_digest=repeat('b',64), rejected_diff_json='{}'::jsonb,"
            + " failure_code='INTERRUPTED', failure_reason='first recovery' WHERE id=?",
        takeoverAttempt);
    replaceAuthority(jdbc, takeoverRun, "RECOVERY", staleRecovery);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET recovery_lease_token=?::uuid,"
            + " recovery_started_at=now() WHERE id=?",
        staleRecovery.toString(),
        takeoverAttempt);
    jdbc.update(
        "UPDATE run_workspace_authority SET lease_expires_at=now()-interval '1 second'"
            + " WHERE run_id=?",
        takeoverRun);
    replaceAuthority(jdbc, takeoverRun, "RECOVERY", replacementRecovery);

    // This is the repository claim shape after a recovery JVM dies: the replacement token can
    // take ownership while the attempt remains ROLLING_BACK.
    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET recovery_lease_token=?::uuid,"
                    + " recovery_started_at=now() WHERE id=? AND state='ROLLING_BACK'",
                replacementRecovery.toString(),
                takeoverAttempt))
        .isEqualTo(1);
    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET state='ROLLED_BACK'"
                    + " WHERE id=? AND recovery_lease_token=?::uuid",
                takeoverAttempt,
                staleRecovery.toString()))
        .isZero();
    assertThat(
            jdbc.update(
                "UPDATE workspace_generation_attempt SET state='ROLLED_BACK',"
                    + " task_transition_kind='REOPEN_PENDING', task_transition_state='PENDING'"
                    + " WHERE id=? AND recovery_lease_token=?::uuid",
                takeoverAttempt,
                replacementRecovery.toString()))
        .isEqualTo(1);
  }

  @Test
  void expiredBuildOwnerCannotEvenPublishRollbackIntent() {
    JdbcTemplate jdbc = migratedDatabase();
    String runId = "run-expired-build-intent";
    UUID attemptId = UUID.randomUUID();
    UUID original = UUID.randomUUID();
    insertRunAndAttempt(jdbc, runId, attemptId, original, "PREPARED");
    insertAuthority(jdbc, runId, "BUILD_GENERATION", original, "-1 second");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
                        + " rejected_diff_digest=repeat('c',64),"
                        + " rejected_diff_json='{}'::jsonb, failure_code='STALE',"
                        + " failure_reason='expired' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation rollback authority is not current");
  }

  private static JdbcTemplate migratedDatabase() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    return jdbc;
  }

  private static void insertRunAndAttempt(
      JdbcTemplate jdbc, String runId, UUID attemptId, UUID workspaceToken, String state) {
    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt) VALUES (?,?, 'WORKING',0)",
        runId.substring(0, Math.min(8, runId.length())) + "_T1",
        runId);
    jdbc.update(
        """
        INSERT INTO workspace_generation_attempt(
          id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
          before_generation,repositories_json,authority_digest,task_contract_digest,
          ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
          workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
          before_checkpoint_digest,before_checkpoint_completed_at)
        VALUES (?,?,'T1',0,1,'BUILD_TASK',?,'/tmp/workspace',repeat('1',64),
          '[]'::jsonb,repeat('2',64),repeat('3',64),'{}'::jsonb,repeat('4',64),?,
          'GCS','gs://bucket/base.zip',repeat('5',64),NOW())
        """,
        attemptId,
        runId,
        state,
        workspaceToken);
  }

  private static void insertAuthority(
      JdbcTemplate jdbc, String runId, String type, UUID token, String interval) {
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + " heartbeat_at) VALUES (?,?,?::uuid,now()+(?::interval),now())",
        runId,
        type,
        token.toString(),
        interval);
  }

  private static void replaceAuthority(JdbcTemplate jdbc, String runId, String type, UUID token) {
    jdbc.update(
        "UPDATE run_workspace_authority SET authority_type=?,owner_token=?::uuid,"
            + " lease_expires_at=now()+interval '10 minutes',heartbeat_at=now() WHERE run_id=?",
        type,
        token.toString(),
        runId);
  }
}
