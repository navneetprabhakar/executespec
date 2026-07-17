package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.navneet.executespec.cost.LlmUsageAuditQuery;
import com.navneet.executespec.cost.LlmUsageAuditService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V292 -> V293 immutable usage-causality and exact-credit rehearsal. */
@Tag("integration")
@Testcontainers
class LlmUsageCausalityMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_usage_causality_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeBackfillsIntentLinksExactCreditsAndRejectsMaterialMutation() {
    String schema = "usage_causality_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("292")).migrate();
      jdbc.update(
          "INSERT INTO run_step_usage"
              + " (id,run_id,org_id,agent_role,provider,model,feature,source_type,source_id,"
              + " key_source,attempt_status,attempt_number,latency_ms,cost_usd)"
              + " VALUES ('usage-legacy','run-1','org-test','WorkerAgent','deepseek',"
              + " 'deepseek-v4','WORKER','PIPELINE_RUN','run-1','PLATFORM','SUCCESS',1,12,0.01)");

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("293"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM llm_usage_attempt_intent"
                      + " WHERE physical_attempt_id='legacy:usage-legacy'"
                      + " AND correlation_id='run-1' AND model_tier='UNKNOWN'",
                  Long.class))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT physical_attempt_id FROM run_step_usage WHERE id='usage-legacy'",
                  String.class))
          .isEqualTo("legacy:usage-legacy");

      jdbc.update(
          "INSERT INTO managed_ai_usage_period"
              + " (id,user_id,tier,period_start,period_end,routing_state)"
              + " VALUES ('period-1','user-1','PRO',NOW(),NOW()+INTERVAL '1 month','FULL')");
      jdbc.update(
          "INSERT INTO managed_ai_usage_event"
              + " (id,period_id,user_id,org_id,run_id,source_type,provider,model,raw_cost_usd,"
              + " ai_credits_charged,routing_state_before,routing_state_after,usage_event_id)"
              + " VALUES ('managed-1','period-1','user-1','org-test','run-1','PLATFORM','deepseek',"
              + " 'deepseek-v4',0.01,9,'FULL','FULL','usage-legacy')");
      assertThat(
              jdbc.queryForObject(
                  "SELECT managed_ai_credits FROM canonical_llm_usage_event"
                      + " WHERE id='usage-legacy'",
                  Long.class))
          .isEqualTo(9L);

      // Idempotent ORM-style replay is permitted; only material mutation is forbidden.
      assertThat(jdbc.update("UPDATE run_step_usage SET cost_usd=cost_usd WHERE id='usage-legacy'"))
          .isEqualTo(1);

      assertThatThrownBy(
              () -> jdbc.update("UPDATE run_step_usage SET cost_usd=999 WHERE id='usage-legacy'"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "DELETE FROM llm_usage_attempt_intent"
                          + " WHERE physical_attempt_id='legacy:usage-legacy'"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");

      String longAttemptId = "logical-call-" + "x".repeat(80) + ":deepseek:1";
      jdbc.update(
          "INSERT INTO llm_usage_attempt_intent"
              + " (physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,"
              + " feature,source_type,source_id,run_id,agent_role,provider,model,model_tier,key_source)"
              + " VALUES (?, 'logical-call', 'PRIMARY', 1, 'corr-2', 'WORKER', 'PIPELINE_RUN',"
              + " 'run-2', 'run-2', 'WorkerAgent', 'deepseek', 'deepseek-v4', 'CAPABLE', 'ORG')",
          longAttemptId);
      assertThat(
              jdbc.update(
                  "UPDATE llm_usage_attempt_intent SET org_id=org_id"
                      + " WHERE physical_attempt_id=?",
                  longAttemptId))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM canonical_llm_usage_event"
                      + " WHERE intent_id=? AND id IS NULL",
                  Long.class,
                  longAttemptId))
          .isEqualTo(1L);

      // The Enterprise report is intent-first: an authorized call cannot disappear merely because
      // the process died before its terminal provider result was persisted.
      jdbc.update(
          "INSERT INTO llm_usage_attempt_intent"
              + " (physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,"
              + " org_id,feature,source_type,source_id,run_id,agent_role,provider,model,model_tier,"
              + " key_source) VALUES ('missing-terminal','logical-missing','PRIMARY',1,'corr-3',"
              + " 'org-test','WORKER','PIPELINE_RUN','run-3','run-3','WorkerAgent','deepseek',"
              + " 'deepseek-v4','WORKHORSE','ORG')");
      var report =
          new LlmUsageAuditService(jdbc)
              .report(
                  LlmUsageAuditQuery.of(
                      "org-test",
                      java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1),
                      java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1),
                      "UTC",
                      0,
                      100));
      assertThat(report.source()).isEqualTo("canonical_llm_usage_event");
      assertThat(report.totals().missingTerminalAttempts()).isEqualTo(1);
      assertThat(report.attempts())
          .anyMatch(
              attempt ->
                  "missing-terminal".equals(attempt.physicalAttemptId())
                      && "MISSING_TERMINAL".equals(attempt.attemptStatus())
                      && "PENDING_TERMINAL".equals(attempt.costStatus()));
      assertThat(report.reconciliation().managedAiEvents().status()).isEqualTo("MATCH");

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
