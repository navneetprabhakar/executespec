package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Physical process-death matrix for terminal generation/task/operation/authority convergence. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationTerminalKillPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_terminal_kill")
          .withUsername("test")
          .withPassword("test");

  @Test
  void replacementJvmConvergesEveryTerminalBoundaryTwiceWithoutPrematureRunCompletion()
      throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    List<String> stages =
        List.of(
            "AFTER_ATTEMPT_UPDATE_BEFORE_FRONTIER",
            "AFTER_FRONTIER_UPDATE_BEFORE_COMMIT",
            "AFTER_ACCEPT_COMMIT",
            "AFTER_TASK_ROW_COMMIT",
            "AFTER_TASK_RECEIPT_COMMIT",
            "AFTER_OPERATION_ROW_BEFORE_RECEIPT",
            "AFTER_OPERATION_RECEIPT_COMMIT",
            "AFTER_AUTHORITY_RELEASE");
    for (String stage : stages) {
      for (int replay = 1; replay <= 2; replay++) {
        Fixture fixture = fixture(jdbc, stage, replay);
        ProcessResult killed = probe("CUT", stage, fixture);
        assertThat(killed.exitCode()).as(killed.output()).isEqualTo(137);
        assertThat(killed.output()).contains("TERMINAL_KILL_MARKER " + stage);

        if (!"AFTER_AUTHORITY_RELEASE".equals(stage)) {
          assertThatThrownBy(
                  () ->
                      jdbc.update(
                          "UPDATE pipeline_run SET status='COMPLETE' WHERE run_id=?",
                          fixture.runId()))
              .rootCause()
              .hasMessageContaining("terminal BUILD run retains unresolved generation state");
        }

        ProcessResult recovered = probe("RECOVER", "-", fixture);
        assertThat(recovered.exitCode()).as(recovered.output()).isZero();
        assertThat(recovered.output()).contains("TERMINAL_RECOVERY_PASSED");
        ProcessResult replayed = probe("RECOVER", "-", fixture);
        assertThat(replayed.exitCode()).as(replayed.output()).isZero();
        assertThat(replayed.output()).contains("TERMINAL_RECOVERY_PASSED");

        assertThat(
                jdbc.update(
                    "UPDATE pipeline_run SET status='COMPLETE' WHERE run_id=?", fixture.runId()))
            .isOne();
        assertConverged(jdbc, fixture);
      }
    }
  }

  private Fixture fixture(JdbcTemplate jdbc, String stage, int replay) {
    String runId =
        (UUID.randomUUID().toString().replace("-", "")
                + "-tk-"
                + Math.abs(stage.hashCode())
                + replay)
            .substring(0, 30);
    String taskKey = runId.substring(0, 8) + "_T1";
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    String candidate = org.apache.commons.codec.digest.DigestUtils.sha256Hex(runId + "candidate");
    String before = org.apache.commons.codec.digest.DigestUtils.sha256Hex(runId + "before");
    String contract = org.apache.commons.codec.digest.DigestUtils.sha256Hex(runId + "contract");
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','LEGACY_V1')",
        runId);
    jdbc.update(
        "UPDATE pipeline_run SET accepted_workspace_generation=?,accepted_task_index=-1"
            + " WHERE run_id=?",
        before,
        runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES (?,?,'WORKING',0,NOW())",
        taskKey,
        runId);
    jdbc.update(
        "INSERT INTO"
            + " run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,heartbeat_at)"
            + " VALUES (?,'BUILD_GENERATION',?::uuid,NOW()+INTERVAL '10 minutes',NOW())",
        runId,
        workspaceToken.toString());
    WorkspaceGenerationPostgresFixture.OperationBinding operation =
        WorkspaceGenerationPostgresFixture.insertPrepared(
            jdbc,
            attemptId,
            runId,
            "T1",
            0,
            1,
            "/tmp/" + runId,
            workspaceToken,
            before,
            "[]",
            "1".repeat(64),
            contract,
            "{}",
            "2".repeat(64),
            "GCS",
            "gs://fixture/" + runId + "/before.tar.gz",
            "3".repeat(64),
            "ctx-" + UUID.randomUUID(),
            "op-" + UUID.randomUUID(),
            UUID.randomUUID(),
            Duration.ofHours(1));
    jdbc.update("UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='VERIFYING',candidate_generation=? WHERE"
            + " id=?",
        candidate,
        attemptId);
    jdbc.update(
        "INSERT INTO workspace_generation_verification_receipt(id,attempt_id,candidate_generation,"
            + "task_contract_digest,verifier_kind,command_source,command_digest,outcome,build_passed,"
            + "tests_executed,tests_passed,tests_failed,tests_errors,tests_skipped,evidence_json,evidence_digest,started_at,completed_at)"
            + " VALUES (?,?,?,?,'BUILD_ONLY',"
            + "'SERVER_RESOLVED_CONTRACT',?,'PASSED',TRUE,FALSE,0,0,0,0,'{}'::jsonb,?,NOW(),NOW())",
        UUID.randomUUID(),
        attemptId,
        candidate,
        contract,
        "4".repeat(64),
        "5".repeat(64));
    jdbc.update("UPDATE workspace_generation_attempt SET state='VERIFIED' WHERE id=?", attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='CHECKPOINTING' WHERE id=?", attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET checkpoint_kind='GCS',checkpoint_key=?,"
            + "checkpoint_generation=?,checkpoint_digest=?,checkpoint_storage_generation='fixture-v1',"
            + "checkpoint_size_bytes=1024,checkpoint_crc32c='fixture-crc',checkpoint_etag='fixture-etag',checkpoint_completed_at=NOW()"
            + " WHERE id=?",
        "gs://fixture/" + runId + "/candidate.tar.gz",
        candidate,
        "6".repeat(64),
        attemptId);
    return new Fixture(runId, attemptId, operation.operationId());
  }

  private ProcessResult probe(String mode, String stage, Fixture fixture) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Process process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("surefire.test.class.path"),
                WorkspaceGenerationTerminalKillProbe.class.getName(),
                mode,
                stage,
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                fixture.runId(),
                fixture.attemptId().toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    return new ProcessResult(process.waitFor(), output);
  }

  private static void assertConverged(JdbcTemplate jdbc, Fixture fixture) {
    assertThat(
            jdbc.queryForMap(
                "SELECT p.status AS run_status,a.state AS attempt_state,t.status AS task_status,"
                    + "a.task_transition_state,a.operation_transition_target,"
                    + "a.operation_transition_state,o.status AS operation_status,"
                    + "(SELECT COUNT(*) FROM run_workspace_authority w WHERE w.run_id=p.run_id)"
                    + " AS authorities FROM pipeline_run p JOIN workspace_generation_attempt a"
                    + " ON a.run_id=p.run_id JOIN agent_task t ON t.run_id=p.run_id"
                    + " JOIN llm_execution_operation o ON o.id=a.llm_operation_id"
                    + " WHERE a.id=?",
                fixture.attemptId()))
        .containsEntry("run_status", "COMPLETE")
        .containsEntry("attempt_state", "ACCEPTED")
        .containsEntry("task_status", "COMPLETE")
        .containsEntry("task_transition_state", "COMPLETED")
        .containsEntry("operation_transition_target", "SUCCEEDED")
        .containsEntry("operation_transition_state", "COMPLETED")
        .containsEntry("operation_status", "SUCCEEDED")
        .containsEntry("authorities", 0L);
  }

  private record Fixture(String runId, UUID attemptId, String operationId) {}

  private record ProcessResult(int exitCode, String output) {}
}
