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

/** Previous/candidate writer proof for V385 expand -> V386 explicit historical disposition. */
@Tag("integration")
@Testcontainers
class BuildProtocolMixedVersionPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("build_protocol_mixed_version")
          .withUsername("test")
          .withPassword("test");

  @Test
  void previousHistoryBecomesUnknownAndPreviousWriterCannotInventNewProtocol() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");

    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("385")
        .load()
        .migrate();

    String historicalRun = "mixed-history";
    UUID historicalAttempt = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode) VALUES (?,'WORKING','BUILD')",
        historicalRun);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest)"
            + " VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED','/tmp/mixed',repeat('1',64),"
            + "repeat('2',64))",
        historicalAttempt,
        historicalRun);

    // Model an already-poisoned historical row exactly as an incident snapshot can contain it.
    // The test fixture bypasses lifecycle triggers only while seeding the pre-candidate snapshot;
    // V386 must classify it without rewriting its product/operation state.
    String conflictRun = "mixed-conflict";
    UUID conflictAttempt = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode) VALUES (?,'FAILED','BUILD')",
        conflictRun);
    UUID conflictOperationToken = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO llm_execution_context("
            + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
            + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
            + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,status,max_attempts,idempotency_key,request_fingerprint)"
            + " VALUES ('ctx-conflict','PIPELINE_RUN','worker','PIPELINE',?,"
            + "'mixed-conflict','PLATFORM_INTERNAL','platform:internal','SYSTEM','[]'::jsonb,"
            + "'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,'QUEUED',4,"
            + "'idem-conflict',repeat('7',64))",
        conflictRun);
    jdbc.update(
        "INSERT INTO llm_execution_operation(id,llm_execution_context_id,operation_key,"
            + "operation_kind,invocation,operation_fingerprint,status,attempt,max_attempts,lease_owner,lease_token,lease_expires_at,heartbeat_at,started_at)"
            + " VALUES"
            + " ('op-conflict','ctx-conflict','mixed-conflict-operation','RUN_BUILD_TASK',1,repeat('8',64),'RUNNING',1,4,'fixture-owner',?,now()+interval"
            + " '10 minutes',now(),now())",
        conflictOperationToken);
    jdbc.execute("ALTER TABLE workspace_generation_attempt DISABLE TRIGGER USER");
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "recovery_failure_code,recovery_failure_reason,recovery_failure_at,resolved_at,"
            + "before_generation,repositories_json,ignored_artifact_baseline_json,"
            + "ignored_artifact_baseline_digest,before_checkpoint_kind,before_checkpoint_key,"
            + "before_checkpoint_digest,before_checkpoint_completed_at,llm_operation_id,"
            + "llm_attempt_number,llm_lease_token)"
            + " VALUES (?,?,'T1',0,1,'BUILD_TASK','RECOVERY_FAILED','/tmp/conflict',"
            + "repeat('3',64),repeat('4',64),'TERMINAL_CAUSALITY_CONFLICT','conflict',now(),now(),"
            + "repeat('5',64),'{}'::jsonb,'{}'::jsonb,repeat('6',64),'LOCAL_SAME_HOST',"
            + "'/tmp/conflict-before',repeat('7',64),now(),'op-conflict',1,?)",
        conflictAttempt,
        conflictRun,
        conflictOperationToken);
    jdbc.execute("ALTER TABLE workspace_generation_attempt ENABLE TRIGGER USER");

    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    assertThat(
            jdbc.queryForMap(
                "SELECT build_protocol_version,build_protocol_disposition FROM pipeline_run"
                    + " WHERE run_id=?",
                historicalRun))
        .containsEntry("build_protocol_version", "LEGACY_UNKNOWN")
        .containsEntry("build_protocol_disposition", "MISSING_EVIDENCE");
    assertThat(
            jdbc.queryForMap(
                "SELECT build_protocol_version,historical_disposition"
                    + " FROM workspace_generation_attempt WHERE id=?",
                historicalAttempt))
        .containsEntry("build_protocol_version", "LEGACY_UNKNOWN")
        .containsEntry("historical_disposition", "MISSING_EVIDENCE");
    assertThat(
            jdbc.queryForObject(
                "SELECT build_protocol_disposition FROM pipeline_run WHERE run_id=?",
                String.class,
                conflictRun))
        .isEqualTo("QUARANTINED_CONFLICT");
    assertThat(
            jdbc.queryForObject(
                "SELECT historical_disposition FROM workspace_generation_attempt WHERE id=?",
                String.class,
                conflictAttempt))
        .isEqualTo("QUARANTINED_CONFLICT");

    // A previous binary can still insert its legacy/null shape against expanded schema. The
    // trigger classifies it immediately instead of pretending it is a candidate protocol row.
    String previousWriterRun = "mixed-previous-writer";
    UUID previousWriterAttempt = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode) VALUES (?,'WORKING','BUILD')",
        previousWriterRun);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest)"
            + " VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED','/tmp/previous',repeat('1',64),"
            + "repeat('2',64))",
        previousWriterAttempt,
        previousWriterRun);
    assertThat(
            jdbc.queryForObject(
                "SELECT build_protocol_disposition FROM pipeline_run WHERE run_id=?",
                String.class,
                previousWriterRun))
        .isEqualTo("MISSING_EVIDENCE");

    WorkspaceGenerationHistoricalInventoryService inventories =
        new WorkspaceGenerationHistoricalInventoryService(jdbc);
    var firstInventory = inventories.capture();
    var repeatedInventory = inventories.capture();
    assertThat(firstInventory.totalRecords()).isEqualTo(6);
    assertThat(firstInventory.runRecords()).isEqualTo(3);
    assertThat(firstInventory.attemptRecords()).isEqualTo(3);
    assertThat(firstInventory.byDisposition().get("QUARANTINED_CONFLICT")).isEqualTo(2);
    assertThat(firstInventory.byDisposition().get("MISSING_EVIDENCE")).isEqualTo(4);
    assertThat(firstInventory.snapshotSha256()).hasSize(64);
    assertThat(repeatedInventory.snapshotSha256()).isEqualTo(firstInventory.snapshotSha256());
    assertThat(firstInventory.records())
        .allSatisfy(
            record -> {
              assertThat(record.protocolVersion()).isEqualTo("LEGACY_UNKNOWN");
              assertThat(record.safeReason()).isNotBlank();
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT historical_disposition FROM workspace_generation_attempt WHERE id=?",
                String.class,
                previousWriterAttempt))
        .isEqualTo("MISSING_EVIDENCE");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,"
                        + "attempt_number,attempt_kind,state,workspace_root,authority_digest,"
                        + "task_contract_digest,build_protocol_version) VALUES (?,?,'T2',1,1,"
                        + "'BUILD_TASK','ADMITTED','/tmp/invented',repeat('1',64),repeat('2',64),"
                        + "'SHARED_KERNEL_V2')",
                    UUID.randomUUID(),
                    previousWriterRun))
        .rootCause()
        .hasMessageContaining("cannot invent a BUILD protocol version");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET build_protocol_version='SHARED_KERNEL_V2',"
                        + "build_protocol_disposition='NOT_APPLICABLE' WHERE run_id=?",
                    previousWriterRun))
        .rootCause()
        .hasMessageContaining("identity/disposition is immutable");
  }
}
