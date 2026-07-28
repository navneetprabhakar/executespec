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

/** PostgreSQL proof for V350's durable host-tenant versus payer/credential-org split. */
@Tag("integration")
@Testcontainers
class LlmCredentialTenantMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_credential_tenant")
          .withUsername("test")
          .withPassword("test");

  @Test
  void v349UpgradeAndCleanInstallBothExposeSeparatedAuthorityColumns() {
    String upgradeSchema = schema("llm_authority_upgrade");
    prepareSchemaWithVector(upgradeSchema);
    DriverManagerDataSource upgradeDataSource = dataSource(upgradeSchema);
    JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
    String cleanSchema = schema("llm_authority_clean");
    DriverManagerDataSource cleanDataSource = dataSource(cleanSchema);
    JdbcTemplate cleanJdbc = new JdbcTemplate(cleanDataSource);
    try {
      flyway(upgradeDataSource, upgradeSchema, "349").migrate();
      upgradeJdbc.update(
          "INSERT INTO pipeline_run(run_id, org_id) VALUES (?, ?)",
          "legacy-run",
          "legacy-host-org");
      upgradeJdbc.update(legacyCustomerContextInsert(), "legacy-customer-context");
      assertThat(flyway(upgradeDataSource, upgradeSchema, "350").migrate().migrationsExecuted)
          .isEqualTo(1);
      assertContract(upgradeJdbc);
      assertThat(
              upgradeJdbc.queryForObject(
                  "SELECT billing_org_id FROM pipeline_run WHERE run_id='legacy-run'",
                  String.class))
          .isEqualTo("legacy-host-org");
      assertThat(
              upgradeJdbc.queryForObject(
                  "SELECT credential_org_id FROM llm_execution_context"
                      + " WHERE id='legacy-customer-context'",
                  String.class))
          .isEqualTo("legacy-host-org");

      upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      prepareSchemaWithVector(cleanSchema);
      Flyway clean = flyway(cleanDataSource, cleanSchema, "350");
      clean.migrate();
      assertThat(clean.info().current().getVersion().getVersion()).isEqualTo("350");
      assertContract(cleanJdbc);
    } finally {
      upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      cleanJdbc.execute("DROP SCHEMA IF EXISTS " + cleanSchema + " CASCADE");
    }
  }

  private static void assertContract(JdbcTemplate jdbc) {
    assertThat(columnExists(jdbc, "pipeline_run", "billing_org_id")).isTrue();
    assertThat(columnExists(jdbc, "llm_execution_context", "credential_org_id")).isTrue();
    Integer constraintCount =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint c"
                + " JOIN pg_class t ON t.oid=c.conrelid"
                + " JOIN pg_namespace n ON n.oid=t.relnamespace"
                + " WHERE n.nspname=current_schema() AND t.relname='llm_execution_context'"
                + " AND c.conname='ck_llm_context_customer_credential_org'",
            Integer.class);
    assertThat(constraintCount).isEqualTo(1);

    jdbc.update(
        "INSERT INTO pipeline_run(run_id, org_id, billing_org_id) VALUES (?, ?, ?)",
        "immutable-run",
        "host-org",
        "payer-org");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET billing_org_id=? WHERE run_id=?",
                    "other-payer",
                    "immutable-run"))
        .rootCause()
        .hasMessageContaining("pipeline_run billing_org_id is immutable");

    jdbc.update(platformContextInsert(), "platform-context");
    assertThat(
            jdbc.queryForObject(
                "SELECT credential_org_id FROM llm_execution_context WHERE id='platform-context'",
                String.class))
        .isNull();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    customerContextInsert(),
                    "invalid-customer-context",
                    null,
                    "invalid-customer-context"))
        .rootCause()
        .hasMessageContaining("ck_llm_context_customer_credential_org");

    jdbc.update(customerContextInsert(), "customer-context", "payer-org", "customer-context");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_context SET credential_org_id=? WHERE id=?",
                    "other-payer",
                    "customer-context"))
        .rootCause()
        .hasMessageContaining("llm_execution_context credential_org_id is immutable");
  }

  private static String platformContextInsert() {
    return "INSERT INTO llm_execution_context("
        + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
        + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
        + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,"
        + "status,max_attempts,idempotency_key,request_fingerprint) VALUES (?,"
        + "'INTERNAL_TEST','test','INTERNAL','source','correlation','PLATFORM_INTERNAL',"
        + "'platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,'{}'::jsonb,'{}'::jsonb,"
        + "'PROVIDER_PREFERRED',1,'QUEUED',1,'platform-idem',repeat('a',64))";
  }

  private static String customerContextInsert() {
    return "INSERT INTO llm_execution_context("
        + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
        + "org_id,user_id,credential_org_id,key_source_mode,key_subject_user_id,"
        + "allowed_providers_json,allowed_provider_models_json,provider_key_sources_json,"
        + "model_overrides_json,routing_mode,route_snapshot_version,status,max_attempts,"
        + "idempotency_key,request_fingerprint) VALUES (?,"
        + "'PIPELINE_RUN','test','PIPELINE','source','correlation','CUSTOMER','org:host-org',"
        + "'host-org','user-1',?,'SYSTEM','user-1','[\"anthropic\"]'::jsonb,'{}'::jsonb,"
        + "'{\"anthropic\":\"SYSTEM\"}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,"
        + "'QUEUED',1,'customer-idem-' || ?,repeat('b',64))";
  }

  private static String legacyCustomerContextInsert() {
    return "INSERT INTO llm_execution_context("
        + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
        + "org_id,user_id,key_source_mode,key_subject_user_id,allowed_providers_json,"
        + "allowed_provider_models_json,provider_key_sources_json,model_overrides_json,"
        + "routing_mode,route_snapshot_version,status,max_attempts,idempotency_key,"
        + "request_fingerprint) VALUES (?,"
        + "'PIPELINE_RUN','test','PIPELINE','legacy-source','legacy-correlation','CUSTOMER',"
        + "'org:legacy-host-org','legacy-host-org','legacy-user','SYSTEM','legacy-user',"
        + "'[\"anthropic\"]'::jsonb,'{}'::jsonb,'{\"anthropic\":\"SYSTEM\"}'::jsonb,"
        + "'{}'::jsonb,'PROVIDER_PREFERRED',1,'QUEUED',1,'legacy-customer-idem',repeat('c',64))";
  }

  private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_schema=current_schema() AND table_name=? AND column_name=?",
            Integer.class,
            table,
            column);
    return count != null && count == 1;
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
