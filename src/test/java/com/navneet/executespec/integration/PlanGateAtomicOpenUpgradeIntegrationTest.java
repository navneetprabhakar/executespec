package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real V345 -> V347 rolling-upgrade proof for historical plan-gate continuations. */
@Tag("integration")
@Testcontainers
class PlanGateAtomicOpenUpgradeIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("plan_gate_upgrade_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeBindsCopiedContinuationAndQuarantinesMissingHistoricalPayload() {
    String schema = "gate_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, "345").migrate();
      insertRun(jdbc, "pending-run", "AWAITING_APPROVAL");
      insertRun(jdbc, "approved-run", "AWAITING_APPROVAL");
      jdbc.update(
          "INSERT INTO gate_record (gate_key,run_id,gate_type,status,comment)"
              + " VALUES ('gate:plan:pending-run','pending-run','PLAN','PENDING','{\"v\":1}')");
      jdbc.update(
          "INSERT INTO gate_record (gate_key,run_id,gate_type,status,resolved_at)"
              + " VALUES ('gate:plan:approved-run','approved-run','PLAN','APPROVED',NOW())");

      assertThat(flyway(dataSource, schema, "346").migrate().migrationsExecuted).isEqualTo(1);
      assertThat(flyway(dataSource, schema, "347").migrate().migrationsExecuted).isEqualTo(1);

      String payloadDigest = PlanGateOpenService.sha256("{\"v\":1}");
      assertThat(
              jdbc.queryForObject(
                  "SELECT continuation_sha256 FROM gate_record"
                      + " WHERE gate_key='gate:plan:pending-run'",
                  String.class))
          .isEqualTo(payloadDigest);
      assertThat(
              jdbc.queryForObject(
                  "SELECT payload_sha256 FROM plan_gate_resume_outbox WHERE run_id='pending-run'",
                  String.class))
          .isEqualTo(payloadDigest);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM plan_gate_resume_outbox WHERE run_id='approved-run'",
                  String.class))
          .isEqualTo("DEAD");
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void insertRun(JdbcTemplate jdbc, String runId, String status) {
    jdbc.update(
        "INSERT INTO pipeline_run (run_id,status,started_at) VALUES (?,?,NOW())", runId, status);
  }

  private static Flyway flyway(DriverManagerDataSource dataSource, String schema, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .target(MigrationVersion.fromVersion(target))
        .load();
  }
}
