package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.navneet.executespec.billing.service.AnalyticsService;
import com.navneet.executespec.cost.LlmUsageAuditQuery;
import com.navneet.executespec.cost.LlmUsageAuditService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V282 -> V283 canonical-attempt upgrade and restart rehearsal. */
@Tag("integration")
@Testcontainers
class LlmAttemptUsageMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_usage_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeBackfillsTenantDimensionsScrubsRawPayloadAndPinsConstraints() {
    String schema = "llm_usage_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("282")).migrate();
      jdbc.update(
          """
          INSERT INTO org (id, name, domain_patterns, created_by_user_id)
          VALUES ('org-usage', 'Usage Org', 'example.com', 'user-usage')
          """);
      jdbc.update(
          """
          INSERT INTO app_user
            (id, email, first_name, last_name, password_hash, status, org_id)
          VALUES
            ('user-usage', 'usage@example.com', 'Usage', 'Owner', 'hash', 'ACTIVE', 'org-usage')
          """);
      jdbc.update(
          """
          INSERT INTO project (id, org_id, name, created_by)
          VALUES ('project-usage', 'org-usage', 'Usage Project', 'user-usage')
          """);
      jdbc.update(
          """
          INSERT INTO pipeline_run (run_id, status, org_id, created_by_user_id)
          VALUES ('run-usage', 'QUEUED', 'org-usage', 'user-usage')
          """);
      jdbc.update(
          """
          INSERT INTO run_step_usage
            (id, run_id, agent_role, provider, model, input_tokens, output_tokens, call_index,
             provider_usage_raw_json)
          VALUES
            ('usage-1', 'run-usage', 'WorkerAgent', 'deepseek', 'deepseek-v4', 11, 7, 1,
             '{"prompt":"must-not-survive","reasoning_tokens":4,"cost":"not-numeric"}'::jsonb)
          """);

      // Pin this contract rehearsal to the exact one-step V282 -> V283 boundary. Later unrelated
      // migrations may exist in the shared tree and must not make this test's assertion drift.
      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("283"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var row = jdbc.queryForMap("SELECT * FROM run_step_usage WHERE id='usage-1'");
      assertThat(row.get("org_id")).isEqualTo("org-usage");
      assertThat(row.get("user_id")).isEqualTo("user-usage");
      assertThat(row.get("feature")).isEqualTo("WorkerAgent");
      assertThat(row.get("source_type")).isEqualTo("PIPELINE_RUN");
      assertThat(row.get("source_id")).isEqualTo("run-usage");
      assertThat(row.get("attempt_status")).isEqualTo("SUCCESS");
      assertThat(row.get("attempt_number")).isEqualTo(1);
      assertThat(String.valueOf(row.get("provider_usage_raw_json")))
          .contains("reasoning_tokens")
          .doesNotContain("prompt", "cost");
      assertThat(
              jdbc.queryForObject(
                  """
                  SELECT data_type FROM information_schema.columns
                  WHERE table_schema=current_schema()
                    AND table_name='run_step_usage' AND column_name='call_index'
                  """,
                  String.class))
          .isEqualTo("bigint");

      AnalyticsService analytics = new AnalyticsService(jdbc, null, null, null, null, null, null);
      var audit = analytics.getLlmUsageAudit("org-usage", 30);
      assertThat(audit.get("source")).isEqualTo("run_step_usage_attempt_ledger");
      @SuppressWarnings("unchecked")
      var totals = (java.util.Map<String, Object>) audit.get("totals");
      assertThat(((Number) totals.get("attempts")).longValue()).isEqualTo(1L);
      assertThat(((Number) totals.get("input_tokens")).longValue()).isEqualTo(11L);
      @SuppressWarnings("unchecked")
      var byRole = (java.util.List<java.util.Map<String, Object>>) audit.get("byRole");
      assertThat(byRole).anyMatch(group -> "WorkerAgent".equals(group.get("label")));
      @SuppressWarnings("unchecked")
      var byUserModelRole =
          (java.util.List<java.util.Map<String, Object>>) audit.get("byUserModelRole");
      assertThat(byUserModelRole)
          .anyMatch(
              group ->
                  "user-usage".equals(group.get("user_id"))
                      && "deepseek-v4".equals(group.get("model"))
                      && "WorkerAgent".equals(group.get("agent_role")));

      jdbc.update(
          """
          UPDATE pipeline_run
          SET project_id='project-usage', actual_cost_usd=0.25
          WHERE run_id='run-usage'
          """);
      jdbc.update(
          "UPDATE run_step_usage SET project_id='project-usage', cost_usd=0.25 WHERE id='usage-1'");
      jdbc.update(
          """
          INSERT INTO agent_session
            (id, org_id, user_id, project_id, client_surface, title, status,
             default_execution_mode, approval_policy)
          VALUES
            ('session-usage', 'org-usage', 'user-usage', 'project-usage', 'WEB', 'Usage chat',
             'ACTIVE', 'CLOUD', 'PLAN')
          """);
      jdbc.update(
          """
          INSERT INTO agent_turn
            (id, session_id, org_id, user_id, sequence, intent_type, intent_confidence, status)
          VALUES ('turn-usage', 'session-usage', 'org-usage', 'user-usage', 1, 'CHAT', 1, 'OK')
          """);
      jdbc.update(
          """
          INSERT INTO run_step_usage
            (id, run_id, org_id, user_id, project_id, feature, source_type, source_id, key_source,
             attempt_status, attempt_number, latency_ms, agent_role, step_id, provider, model,
             input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, reasoning_tokens,
             cost_usd, call_index, error_code, error_redacted, created_at)
          VALUES
            ('usage-session', 'session-usage', 'org-usage', 'user-usage', 'project-usage',
             'project_chat', 'AGENT_TURN', 'turn-usage', 'PLATFORM', 'SUCCESS', 1, 20,
             'ProjectChatService', 'chat.turn', 'deepseek', 'deepseek-v4', 5, 2, 1, 0, 1,
             0.05, 2, NULL, NULL, now()),
            ('usage-failed', 'run-usage', 'org-usage', 'user-usage', 'project-usage',
             'worker', 'PIPELINE_RUN', 'run-usage', 'ORG', 'FAILED', 2, 40,
             'WorkerAgent', 'worker.iteration', 'deepseek', 'deepseek-v4', 0, 0, 0, 0, 0,
             0, 0, 'TIMEOUT', 'Timeout:PROVIDER_ERROR_DETAIL_OMITTED', now())
          """);
      jdbc.update(
          """
          INSERT INTO managed_ai_usage_period
            (id, user_id, org_id, tier, period_start, period_end)
          VALUES ('period-usage', 'user-usage', 'org-usage', 'PRO', now() - interval '1 day',
                  now() + interval '1 month')
          """);
      jdbc.update(
          """
          INSERT INTO managed_ai_usage_event
            (id, period_id, user_id, org_id, run_id, source_type, provider, model, agent_role,
             step_id, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
             reasoning_tokens, raw_cost_usd, retail_multiplier, ai_credits_charged,
             routing_state_before, routing_state_after)
          VALUES
            ('managed-usage', 'period-usage', 'user-usage', 'org-usage', 'session-usage',
             'PLATFORM', 'deepseek', 'deepseek-v4', 'ProjectChatService', 'chat.turn',
             5, 2, 1, 0, 1, 0.05, 1, 1, 'FULL', 'FULL')
          """);

      LlmUsageAuditService reporting = new LlmUsageAuditService(jdbc);
      var report =
          reporting.report(
              LlmUsageAuditQuery.of(
                  "org-usage",
                  java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1),
                  java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1),
                  "UTC",
                  0,
                  100));
      assertThat(report.totals().attempts()).isEqualTo(3);
      assertThat(report.totals().knownCostUsdLowerBound()).isEqualByComparingTo("0.30");
      assertThat(report.totals().unknownCostAttempts()).isOne();
      assertThat(report.attempts())
          .anyMatch(
              attempt ->
                  "usage-failed".equals(attempt.id())
                      && attempt.knownCostUsd() == null
                      && "UNKNOWN_PROVIDER_NOT_REPORTED".equals(attempt.costStatus()));
      assertThat(report.rollups())
          .anyMatch(
              group ->
                  "session-usage".equals(group.sessionId())
                      && "ProjectChatService".equals(group.agentRole()));
      assertThat(report.reconciliation().runTotals().status())
          .isEqualTo("INDETERMINATE_INCOMPLETE_OR_UNKNOWN_COST");
      assertThat(report.reconciliation().managedAiEvents().status()).isEqualTo("MATCH");
      assertThat(report.reconciliation().providerInvoiceStatus())
          .isEqualTo("NOT_AVAILABLE_NO_PROVIDER_INVOICE_OR_REQUEST_IDENTIFIER");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
                      INSERT INTO run_step_usage
                        (id, run_id, attempt_status, attempt_number, latency_ms)
                      VALUES ('usage-invalid', 'run-usage', 'UNKNOWN', 1, 0)
                      """))
          .isInstanceOf(DataIntegrityViolationException.class);
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
