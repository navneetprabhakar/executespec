package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V353 -> V354 and clean-install proof for atomic operation admission. */
@Tag("integration")
@Testcontainers
class LlmExecutionOperationAdmissionMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_operation_admission")
          .withUsername("test")
          .withPassword("test");

  @Test
  void v353UpgradeAndCleanInstallEnforceDatabaseClockAndVersionedFence() {
    String upgradeSchema = schema("llm_admission_upgrade");
    String cleanSchema = schema("llm_admission_clean");
    prepareSchemaWithVector(upgradeSchema);
    DriverManagerDataSource upgradeDataSource = dataSource(upgradeSchema);
    DriverManagerDataSource cleanDataSource = dataSource(cleanSchema);
    JdbcTemplate upgrade = new JdbcTemplate(upgradeDataSource);
    JdbcTemplate clean = new JdbcTemplate(cleanDataSource);
    try {
      flyway(upgradeDataSource, upgradeSchema, "353").migrate();
      insertContext(upgrade, "llmctx_historical");
      upgrade.update(
          intentInsert(0, true, "llmctx_historical", "NULL", "NULL", "NULL", "NULL"),
          "historical-durable-v0");

      assertThat(flyway(upgradeDataSource, upgradeSchema, "354").migrate().migrationsExecuted)
          .isEqualTo(1);
      assertThat(
              upgrade.queryForObject(
                  "SELECT count(*) FROM llm_usage_attempt_intent"
                      + " WHERE physical_attempt_id='historical-durable-v0'",
                  Integer.class))
          .isEqualTo(1);
      assertContract(upgrade, "upgrade");
      // Clean-install rehearsal represents a separate empty database. Older migrations contain
      // constraint-name existence probes that are global to pg_constraint, so leaving the upgrade
      // schema beside the clean schema creates a false cross-schema hit at V293.
      upgrade.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");

      prepareSchemaWithVector(cleanSchema);
      Flyway cleanFlyway = flyway(cleanDataSource, cleanSchema, "354");
      cleanFlyway.migrate();
      assertThat(cleanFlyway.info().current().getVersion().getVersion()).isEqualTo("354");
      assertContract(clean, "clean");
    } finally {
      upgrade.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      clean.execute("DROP SCHEMA IF EXISTS " + cleanSchema + " CASCADE");
    }
  }

  private static void assertContract(JdbcTemplate jdbc, String suffix) {
    String contextId = "llmctx_" + suffix;
    String operationId = "llmop_" + suffix;
    insertContext(jdbc, contextId);

    // The current LEGACY writer deliberately emits context-free v0 rows.
    jdbc.update(
        intentInsert(0, false, null, "NULL", "NULL", "NULL", "NULL"), "context-free-v0-" + suffix);

    // Expand/contract compatibility: the immediately previous binary writes durable-context v0
    // attribution. It must remain insertable after V354 so relinking that JAR is a safe rollback.
    jdbc.update(
        intentInsert(0, true, contextId, "NULL", "NULL", "NULL", "NULL"),
        "previous-writer-durable-v0-" + suffix);
    assertThat(
            jdbc.queryForObject(
                "SELECT llm_execution_context_id FROM llm_usage_attempt_intent"
                    + " WHERE physical_attempt_id=?",
                String.class,
                "previous-writer-durable-v0-" + suffix))
        .isEqualTo(contextId);

    // Version 0 remains unable to fabricate any operation authority.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    intentInsert(
                        0,
                        true,
                        contextId,
                        "'forged-operation'",
                        "1",
                        "'00000000-0000-0000-0000-000000000354'::uuid",
                        "now() + interval '10 minutes'"),
                    "v0-with-operation-fence-" + suffix))
        .rootCause()
        .hasMessageContaining("Legacy LLM attempt cannot carry operation fence fields");

    insertRunningOperation(jdbc, operationId, contextId, false);
    UUID token =
        jdbc.queryForObject(
            "SELECT lease_token FROM llm_execution_operation WHERE id=?", UUID.class, operationId);

    // started_at is intentionally forged far into the future. V351 compared the lease to this
    // caller-controlled value; V354 correctly admits from database clock + durable horizons.
    jdbc.update(
        intentInsert(
            1,
            true,
            contextId,
            "'" + operationId + "'",
            "1",
            "'" + token + "'::uuid",
            operationHorizon(operationId)),
        "valid-v1-" + suffix);
    assertThat(
            jdbc.queryForObject(
                "SELECT operation_fence_version FROM llm_usage_attempt_intent"
                    + " WHERE physical_attempt_id=?",
                Integer.class,
                "valid-v1-" + suffix))
        .isEqualTo(1);

    String expiredOperation = "llmop_expired_" + suffix;
    insertRunningOperation(jdbc, expiredOperation, contextId, true);
    UUID expiredToken =
        jdbc.queryForObject(
            "SELECT lease_token FROM llm_execution_operation WHERE id=?",
            UUID.class,
            expiredOperation);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    intentInsert(
                        1,
                        true,
                        contextId,
                        "'" + expiredOperation + "'",
                        "1",
                        "'" + expiredToken + "'::uuid",
                        operationHorizon(expiredOperation)),
                    "expired-v1-" + suffix))
        .rootCause()
        .hasMessageContaining("lease expired before admission");
  }

  private static void insertContext(JdbcTemplate jdbc, String contextId) {
    jdbc.update(
        "INSERT INTO llm_execution_context("
            + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
            + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
            + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,status,max_attempts,idempotency_key,request_fingerprint)"
            + " VALUES (?,'INTERNAL_TEST','test','INTERNAL','source','correlation',"
            + "'PLATFORM_INTERNAL','platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,"
            + "'{}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,'QUEUED',1,?,repeat('a',64))",
        contextId,
        "idem-" + contextId);
  }

  private static void insertRunningOperation(
      JdbcTemplate jdbc, String operationId, String contextId, boolean expired) {
    jdbc.update(
        "INSERT INTO llm_execution_operation("
            + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
            + "operation_fingerprint,status,attempt,max_attempts,next_attempt_at,created_at,updated_at)"
            + " VALUES (?,?,?,'TEST',1,repeat('b',64),'QUEUED',0,1,now(),now(),now())",
        operationId,
        contextId,
        "operation/" + operationId);
    String lease = expired ? "now() - interval '1 minute'" : "now() + interval '10 minutes'";
    jdbc.update(
        "UPDATE llm_execution_operation SET status='RUNNING',attempt=1,lease_owner='owner-a',"
            + "lease_token=?::uuid,lease_expires_at="
            + lease
            + ",heartbeat_at=now(),not_reclaim_before=now() + interval '10 minutes',"
            + "next_attempt_at=NULL,started_at=now(),updated_at=now() WHERE id=?",
        UUID.randomUUID().toString(),
        operationId);
  }

  private static String intentInsert(
      int fenceVersion,
      boolean durable,
      String contextId,
      String operationId,
      String operationAttempt,
      String token,
      String horizon) {
    String context = durable ? "'" + contextId + "'" : "NULL";
    String fence =
        fenceVersion
            + ","
            + operationId
            + ","
            + operationAttempt
            + ","
            + token
            + ","
            + horizon
            + ","
            + (fenceVersion == 1 ? "now() + interval '5 minutes'" : "NULL");
    return "INSERT INTO llm_usage_attempt_intent("
        + "physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,"
        + "llm_execution_context_id,correlation_id,feature,source_type,source_id,run_id,"
        + "agent_role,human_role_snapshot,role_provenance,provider,model,model_tier,key_source,"
        + "started_at,operation_fence_version,llm_execution_operation_id,"
        + "llm_execution_operation_attempt,llm_execution_lease_token,"
        + "operation_not_reclaim_before,transport_deadline_at)"
        + " VALUES (?,'logical','PRIMARY',1,"
        + context
        + ",'correlation','test','INTERNAL','source','run','test','SUPER_ADMIN',"
        + "'CAPTURED_AT_DISPATCH','openai','gpt-5.4-mini','LIGHTWEIGHT','PLATFORM',"
        + (fenceVersion == 1 ? "now() + interval '1 day'," : "now(),")
        + fence
        + ")";
  }

  private static String operationHorizon(String operationId) {
    return "(SELECT not_reclaim_before FROM llm_execution_operation WHERE id='"
        + operationId
        + "')";
  }

  private static Flyway flyway(DataSource dataSource, String schema, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target(org.flywaydb.core.api.MigrationVersion.fromVersion(target))
        .baselineOnMigrate(false)
        .cleanDisabled(false)
        .load();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setDriverClassName(POSTGRES.getDriverClassName());
    return dataSource;
  }

  private static void prepareSchemaWithVector(String schema) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource("public"));
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    jdbc.execute("CREATE SCHEMA " + schema);
  }

  private static String schema(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }
}
