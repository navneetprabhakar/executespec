package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Real PostgreSQL V340 -> V341 rehearsal for historical physical-attempt usage reconciliation. */
@Tag("integration")
@Testcontainers
class PipelineRunUsageFloorMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("pipeline_usage_floor_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void migrationRaisesMissingUsageFromSuccessfulAttemptsWithoutLoweringExistingTotals() {
    String schema = "usage_floor_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("340")).migrate();

      insertRun(jdbc, "run-missing", null, 0, 0);
      insertRun(jdbc, "run-higher", 0.50, 100, 50);
      insertUsage(jdbc, "run-missing", "known", "SUCCESS", 10, 5, 0.01);
      // Successful but unpriced usage still contributes tokens, not a fabricated zero-cost fact.
      insertUsage(jdbc, "run-missing", "unpriced", "SUCCESS", 20, 7, 0.0);
      // Failed attempts have unknown provider spend and do not contribute run token totals.
      insertUsage(jdbc, "run-missing", "failed", "FAILED", 99, 99, 0.0);
      insertUsage(jdbc, "run-higher", "small", "SUCCESS", 1, 1, 0.001);

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("341"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var repaired =
          jdbc.queryForMap(
              "SELECT total_input_tokens,total_output_tokens,actual_cost_usd FROM pipeline_run"
                  + " WHERE run_id='run-missing'");
      assertThat(((Number) repaired.get("total_input_tokens")).longValue()).isEqualTo(30);
      assertThat(((Number) repaired.get("total_output_tokens")).longValue()).isEqualTo(12);
      assertThat(((Number) repaired.get("actual_cost_usd")).doubleValue()).isEqualTo(0.01);

      var preserved =
          jdbc.queryForMap(
              "SELECT total_input_tokens,total_output_tokens,actual_cost_usd FROM pipeline_run"
                  + " WHERE run_id='run-higher'");
      assertThat(((Number) preserved.get("total_input_tokens")).longValue()).isEqualTo(100);
      assertThat(((Number) preserved.get("total_output_tokens")).longValue()).isEqualTo(50);
      assertThat(((Number) preserved.get("actual_cost_usd")).doubleValue()).isEqualTo(0.50);

      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void insertRun(
      JdbcTemplate jdbc, String runId, Double actualCost, int input, int output) {
    jdbc.update(
        """
        INSERT INTO pipeline_run
            (run_id,status,total_input_tokens,total_output_tokens,actual_cost_usd)
        VALUES (?,?,?,?,?)
        """,
        runId,
        "AWAITING_APPROVAL",
        input,
        output,
        actualCost);
  }

  private static void insertUsage(
      JdbcTemplate jdbc,
      String runId,
      String suffix,
      String status,
      long input,
      long output,
      double cost) {
    String attemptId = runId + "-" + suffix;
    jdbc.update(
        """
        INSERT INTO llm_usage_attempt_intent
            (physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,
             feature,source_type,source_id,run_id,agent_role,provider,model,model_tier,key_source,
             human_role_snapshot,role_provenance)
        VALUES (?,?,'PRIMARY',1,?,'PLANNER','PIPELINE_RUN',?,?,'OutlinePlannerAgent',
                'deepseek','deepseek-v4-pro-native','PLANNING','PLATFORM','UNKNOWN',
                'CAPTURED_AT_DISPATCH')
        """,
        attemptId,
        "logical-" + attemptId,
        "correlation-" + attemptId,
        runId,
        runId);
    jdbc.update(
        """
        INSERT INTO run_step_usage
            (id,run_id,agent_role,provider,model,input_tokens,output_tokens,cache_write_tokens,
             cache_read_tokens,reasoning_tokens,cost_usd,call_index,feature,source_type,source_id,
             key_source,attempt_status,error_code,error_redacted,attempt_number,latency_ms,
             correlation_id,model_tier,logical_call_id,physical_attempt_id,attempt_kind,
             attempt_ordinal)
        VALUES (?,?, 'OutlinePlannerAgent','deepseek','deepseek-v4-pro-native',?,?,0,0,0,?,1,
                'PLANNER','PIPELINE_RUN',?,'PLATFORM',?,?,?,1,10,?,
                'PLANNING',?,?,'PRIMARY',1)
        """,
        attemptId,
        runId,
        input,
        output,
        cost,
        runId,
        status,
        "FAILED".equals(status) ? "UPSTREAM_ERROR" : null,
        "FAILED".equals(status) ? "provider failed" : null,
        "correlation-" + attemptId,
        "logical-" + attemptId,
        attemptId);
  }

  private static DriverManagerDataSource dataSource(String schema) {
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Flyway flyway(DataSource dataSource, String schema, MigrationVersion target) {
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
