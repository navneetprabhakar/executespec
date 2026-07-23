package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Real PostgreSQL V296 -> V297 rehearsal for durable benchmark progress and trajectory state. */
@Tag("integration")
@Testcontainers
class BenchmarkProgressMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("benchmark_progress_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradesExistingBenchmarkRowsAndEnforcesDurableStateConstraints() {
    String schema = "benchmark_progress_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("296")).migrate();
      jdbc.update(
          "INSERT INTO benchmark_run"
              + " (id,suite,dataset,provider,model,status,total_tasks)"
              + " VALUES ('bench-1','SWE_BENCH','lite','deepseek','deepseek-v4-pro-native',"
              + " 'RUNNING',10)");
      jdbc.update(
          "INSERT INTO benchmark_task_result"
              + " (id,benchmark_run_id,task_name,attempt,reward)"
              + " VALUES ('result-1','bench-1','task-a',1,1),"
              + " ('result-2','bench-1','task-b',1,0)");

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("297"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var migrated = jdbc.queryForMap("SELECT * FROM benchmark_run WHERE id='bench-1'");
      assertThat(migrated.get("active_attempt_count")).isEqualTo(0);
      assertThat(migrated.get("completed_attempts")).isEqualTo(2);
      assertThat(migrated.get("submission_required")).isEqualTo(false);
      assertThat(migrated.get("submission_readiness")).isEqualTo("NOT_REQUIRED");
      assertThat(
              jdbc.queryForList(
                  "SELECT failure_classification FROM benchmark_task_result"
                      + " WHERE benchmark_run_id='bench-1' ORDER BY id",
                  String.class))
          .containsExactly("UNKNOWN", "UNKNOWN");

      jdbc.update(
          "INSERT INTO benchmark_active_attempt"
              + " (id,benchmark_run_id,task_name,attempt,started_at,last_progress_at,stale_after,"
              + " trajectory_location,trajectory_sha256)"
              + " VALUES ('active-1','bench-1','task-c',1,NOW(),NOW(),NOW() + INTERVAL '15 min',"
              + " 'gs://safe-bucket/trajectory.json',?)",
          "a".repeat(64));

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE benchmark_run SET submission_readiness='INVALID' WHERE id='bench-1'"))
          .hasMessageContaining("ck_benchmark_submission_readiness");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE benchmark_task_result SET trajectory_location='unsafe',"
                          + " trajectory_sha256='bad' WHERE id='result-1'"))
          .hasMessageContaining("ck_benchmark_task_trajectory_pair");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE benchmark_active_attempt SET stale_after=last_progress_at"
                          + " - INTERVAL '1 second' WHERE id='active-1'"))
          .hasMessageContaining("ck_benchmark_active_heartbeat");

      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static Flyway flyway(
      DriverManagerDataSource dataSource, String schema, MigrationVersion target) {
    var config =
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .schemas(schema)
            .defaultSchema(schema)
            .createSchemas(true);
    if (target != null) config.target(target);
    return config.load();
  }
}
