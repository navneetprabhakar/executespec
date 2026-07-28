package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V350 -> V351 and clean-install proof for dormant operation fencing. */
@Tag("integration")
@Testcontainers
class LlmExecutionOperationMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_operation_fencing")
          .withUsername("test")
          .withPassword("test");

  @Test
  void v350UpgradeAndCleanInstallExposeTheSameOperationFenceContract() {
    String upgradeSchema = schema("llm_operation_upgrade");
    String cleanSchema = schema("llm_operation_clean");
    prepareSchemaWithVector(upgradeSchema);
    DriverManagerDataSource upgradeDataSource = dataSource(upgradeSchema);
    DriverManagerDataSource cleanDataSource = dataSource(cleanSchema);
    JdbcTemplate upgrade = new JdbcTemplate(upgradeDataSource);
    JdbcTemplate clean = new JdbcTemplate(cleanDataSource);
    try {
      flyway(upgradeDataSource, upgradeSchema, "350").migrate();
      assertThat(flyway(upgradeDataSource, upgradeSchema, "351").migrate().migrationsExecuted)
          .isEqualTo(1);
      assertContract(upgrade);

      upgrade.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      prepareSchemaWithVector(cleanSchema);
      Flyway cleanFlyway = flyway(cleanDataSource, cleanSchema, "351");
      cleanFlyway.migrate();
      assertThat(cleanFlyway.info().current().getVersion().getVersion()).isEqualTo("351");
      assertContract(clean);
    } finally {
      upgrade.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      clean.execute("DROP SCHEMA IF EXISTS " + cleanSchema + " CASCADE");
    }
  }

  private static void assertContract(JdbcTemplate jdbc) {
    jdbc.update(platformContextInsert(), "llmctx_operation_test");
    jdbc.update(platformContextInsert(), "llmctx_operation_other");
    jdbc.update(
        queuedOperationInsert(),
        "llmop_1",
        "llmctx_operation_test",
        "worker/task-1",
        1,
        "a".repeat(64));
    jdbc.update(
        "UPDATE llm_execution_operation SET status='RUNNING', attempt=1, lease_owner='owner-a',"
            + " lease_token='00000000-0000-0000-0000-000000000001'::uuid,"
            + " lease_expires_at=now() + interval '10 minutes', heartbeat_at=now(),"
            + " not_reclaim_before=now() + interval '10 minutes', next_attempt_at=NULL,"
            + " started_at=now(), updated_at=now() WHERE id='llmop_1'");

    jdbc.update(legacyAttemptIntentInsert(), "intent-legacy");
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_fence_version FROM llm_usage_attempt_intent"
                    + " WHERE physical_attempt_id='intent-legacy'",
                Integer.class))
        .isZero();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO llm_execution_operation("
                        + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
                        + "operation_fingerprint,status,attempt,max_attempts,created_at,updated_at)"
                        + " VALUES (?,?,?,?,? ,?,'RUNNING',1,3,now(),now())",
                    "llmop_invalid_running",
                    "llmctx_operation_test",
                    "worker/invalid",
                    "WORKER",
                    1,
                    "b".repeat(64)))
        .rootCause()
        .hasMessageContaining("ck_llm_execution_operation_state");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    queuedOperationInsert(),
                    "llmop_2",
                    "llmctx_operation_test",
                    "worker/task-1",
                    2,
                    "c".repeat(64)))
        .rootCause()
        .hasMessageContaining("uq_llm_execution_operation_nonterminal");

    jdbc.update(
        "INSERT INTO llm_execution_operation("
            + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
            + "operation_fingerprint,status,attempt,max_attempts,next_attempt_at,"
            + "not_reclaim_before,created_at,updated_at) VALUES ("
            + "'llmop_not_due','llmctx_operation_test','worker/not-due','WORKER',1,"
            + "repeat('f',64),'QUEUED',0,3,now() + interval '5 minutes',"
            + "now() + interval '10 minutes',now(),now())");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_operation SET status='RUNNING', attempt=1,"
                        + " lease_owner='owner-early',"
                        + " lease_token='00000000-0000-0000-0000-000000000008'::uuid,"
                        + " lease_expires_at=now() + interval '1 minute', heartbeat_at=now(),"
                        + " next_attempt_at=NULL, started_at=now(), updated_at=now()"
                        + " WHERE id='llmop_not_due'"))
        .rootCause()
        .hasMessageContaining("illegal QUEUED to RUNNING operation claim");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_operation SET operation_key='worker/changed' WHERE id=?",
                    "llmop_1"))
        .rootCause()
        .hasMessageContaining("immutable identity cannot change");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_operation SET lease_token="
                        + "'00000000-0000-0000-0000-000000000009'::uuid WHERE id='llmop_1'"))
        .rootCause()
        .hasMessageContaining("illegal RUNNING operation heartbeat");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_operation SET attempt=2, lease_owner='owner-z',"
                        + " lease_token='00000000-0000-0000-0000-000000000009'::uuid"
                        + " WHERE id='llmop_1'"))
        .rootCause()
        .hasMessageContaining("illegal RUNNING operation reclaim");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_operation SET not_reclaim_before=now()"
                        + " WHERE id='llmop_1'"))
        .rootCause()
        .hasMessageContaining("reclaim horizon cannot decrease");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    attemptIntentInsert(
                        false,
                        "llmctx_operation_test",
                        1,
                        "00000000-0000-0000-0000-000000000001",
                        0,
                        5),
                    "intent-incomplete"))
        .rootCause()
        .hasMessageContaining("ck_llm_usage_attempt_operation_fence_complete");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    attemptIntentInsert(
                        true,
                        "llmctx_operation_test",
                        2,
                        "00000000-0000-0000-0000-000000000001",
                        0,
                        5),
                    "intent-wrong-attempt"))
        .rootCause()
        .hasMessageContaining("operation claim mismatch");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    attemptIntentInsert(
                        true,
                        "llmctx_operation_test",
                        1,
                        "00000000-0000-0000-0000-000000000002",
                        0,
                        5),
                    "intent-wrong-token"))
        .rootCause()
        .hasMessageContaining("operation claim mismatch");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    attemptIntentInsert(
                        true,
                        "llmctx_operation_other",
                        1,
                        "00000000-0000-0000-0000-000000000001",
                        0,
                        5),
                    "intent-wrong-context"))
        .rootCause()
        .hasMessageContaining("operation context mismatch");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    attemptIntentInsert(
                        true,
                        "llmctx_operation_test",
                        1,
                        "00000000-0000-0000-0000-000000000001",
                        -1,
                        5),
                    "intent-wrong-horizon"))
        .rootCause()
        .hasMessageContaining("reclaim horizon mismatch");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    attemptIntentInsert(
                        true,
                        "llmctx_operation_test",
                        1,
                        "00000000-0000-0000-0000-000000000001",
                        0,
                        11),
                    "intent-deadline-beyond-horizon"))
        .rootCause()
        .hasMessageContaining("transport deadline exceeds admitted reclaim horizon");

    jdbc.update(
        attemptIntentInsert(
            true, "llmctx_operation_test", 1, "00000000-0000-0000-0000-000000000001", 0, 5),
        "intent-complete");
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_fence_version FROM llm_usage_attempt_intent"
                    + " WHERE physical_attempt_id='intent-complete'",
                Integer.class))
        .isEqualTo(1);
  }

  private static String queuedOperationInsert() {
    return "INSERT INTO llm_execution_operation("
        + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
        + "operation_fingerprint,status,attempt,max_attempts,created_at,updated_at)"
        + " VALUES (?,?,?,?,?,?,'QUEUED',0,3,now(),now())";
  }

  private static String attemptIntentInsert(
      boolean complete,
      String contextId,
      int operationAttempt,
      String leaseToken,
      int reclaimOffsetMinutes,
      int deadlineMinutes) {
    String fenceValues =
        complete
            ? "1,'llmop_1',"
                + operationAttempt
                + ",'"
                + leaseToken
                + "'::uuid,(SELECT not_reclaim_before FROM llm_execution_operation"
                + " WHERE id='llmop_1') + interval '"
                + reclaimOffsetMinutes
                + " minutes',now() + interval '"
                + deadlineMinutes
                + " minutes'"
            : "1,NULL,NULL,NULL,NULL,NULL";
    return "INSERT INTO llm_usage_attempt_intent("
               + "physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,llm_execution_context_id,correlation_id,"
               + "feature,source_type,source_id,run_id,agent_role,provider,model,model_tier,key_source,"
               + "human_role_snapshot,role_provenance,"
               + "operation_fence_version,llm_execution_operation_id,llm_execution_operation_attempt,"
               + "llm_execution_lease_token,operation_not_reclaim_before,transport_deadline_at)"
               + " VALUES (?,'logical-1','PRIMARY',1,'"
        + contextId
        + "','correlation','test','INTERNAL','source','run','WorkerAgent',"
        + "'deepseek','model','WORKHORSE','PLATFORM','SUPER_ADMIN','CAPTURED_AT_DISPATCH',"
        + fenceValues
        + ")";
  }

  private static String legacyAttemptIntentInsert() {
    return "INSERT INTO llm_usage_attempt_intent("
        + "physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,"
        + "feature,source_type,source_id,run_id,agent_role,provider,model,model_tier,key_source,"
        + "human_role_snapshot,role_provenance)"
        + " VALUES (?,'logical-legacy','PRIMARY',1,'correlation','test','INTERNAL','source',"
        + "'run','WorkerAgent','deepseek','model','WORKHORSE','PLATFORM','SUPER_ADMIN',"
        + "'CAPTURED_AT_DISPATCH')";
  }

  private static String platformContextInsert() {
    return "INSERT INTO llm_execution_context("
        + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
        + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
        + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,"
        + "status,max_attempts,idempotency_key,request_fingerprint) VALUES (?,"
        + "'INTERNAL_TEST','test','INTERNAL','source','correlation','PLATFORM_INTERNAL',"
        + "'platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,"
        + "'PROVIDER_PREFERRED',1,'QUEUED',1,'platform-operation-idem',repeat('d',64))";
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private static Flyway flyway(DataSource dataSource, String schema, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  private static void prepareSchemaWithVector(String schema) {
    JdbcTemplate jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("CREATE SCHEMA " + schema);
    jdbc.execute("CREATE EXTENSION vector WITH SCHEMA " + schema);
  }

  private static String schema(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }
}
