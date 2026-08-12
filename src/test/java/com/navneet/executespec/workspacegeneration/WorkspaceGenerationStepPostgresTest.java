package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for V397's monotonic physical-side-effect journal. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationStepPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_step")
          .withUsername("test")
          .withPassword("test");

  @Test
  void intentIsIdempotentAndPhysicalIdentityBecomesImmutableAfterVerification() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    WorkspaceGenerationStepStore store =
        new WorkspaceGenerationStepStore(
            jdbc, new ObjectMapper(), new DataSourceTransactionManager(dataSource));

    String runId = "v397-run";
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        runId);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "build_protocol_version) VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED',"
            + "'/tmp/v397',repeat('1',64),repeat('2',64),'SHARED_KERNEL_V2')",
        attemptId,
        runId);

    String generation = "a".repeat(64);
    var first =
        store.recordIntent(
            attemptId,
            WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
            "before:" + generation,
            generation,
            null,
            Map.of("objectKey", "workspace-generations/v397/before.tar.gz"));
    var replay =
        store.recordIntent(
            attemptId,
            WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
            "before:" + generation,
            generation,
            null,
            Map.of("objectKey", "workspace-generations/v397/before.tar.gz"));
    assertThat(replay.id()).isEqualTo(first.id());
    assertThatThrownBy(
            () ->
                store.recordIntent(
                    attemptId,
                    WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
                    "before:" + generation,
                    generation,
                    null,
                    Map.of("objectKey", "workspace-generations/v397/different.tar.gz")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("WORKSPACE_GENERATION_STEP_INTENT_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_generation_step WHERE attempt_id=?",
                Integer.class,
                attemptId))
        .isEqualTo(1);

    store.markArchiveApplied(
        attemptId,
        WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
        "before:" + generation,
        new WorkspaceGenerationStepStore.ArchiveReceipt(
            "dev/workspace-generations/v397/before.tar.gz",
            "b".repeat(64),
            "1234",
            512,
            "crc",
            "etag"));
    store.markVerified(
        attemptId,
        WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
        "before:" + generation,
        Map.of("generation", generation));
    assertThat(
            store
                .find(
                    attemptId,
                    WorkspaceGenerationStepStore.Kind.BEFORE_ARCHIVE_UPLOAD,
                    "before:" + generation)
                .orElseThrow()
                .state())
        .isEqualTo(WorkspaceGenerationStepStore.State.VERIFIED);
    WorkspaceGenerationArchiveCleanupStore cleanup =
        new WorkspaceGenerationArchiveCleanupStore(
            jdbc, new DataSourceTransactionManager(dataSource));
    assertThat(cleanup.enqueueEligible())
        .as("an archive owned by an unresolved attempt must never enter the deletion queue")
        .isZero();
    UUID recoveryToken = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'RECOVERY',?::uuid,NOW()+interval '10 minutes',NOW())",
        runId,
        recoveryToken.toString());
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',recovery_lease_token=?,"
            + "recovery_started_at=NOW() WHERE id=?",
        recoveryToken,
        attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLED_BACK',"
            + "failure_code='PRE_DISPATCH_ATTEMPT_RECOVERED',failure_reason='test recovery',"
            + "resolved_at=NOW() WHERE id=?",
        attemptId);
    assertThat(cleanup.enqueueEligible()).isEqualTo(1);
    var orphan = cleanup.claimNext(java.time.Duration.ZERO).orElseThrow();
    assertThat(cleanup.stillDeletable(orphan)).isTrue();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_step SET object_key='tampered' WHERE id=?",
                    first.id()))
        .rootCause()
        .hasMessageContaining("verified workspace generation step is immutable");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM workspace_generation_step WHERE id=?", first.id()))
        .rootCause()
        .hasMessageContaining("retained with their attempt");
    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_generation_step WHERE attempt_id=?",
                Integer.class,
                attemptId))
        .isZero();
  }
}
