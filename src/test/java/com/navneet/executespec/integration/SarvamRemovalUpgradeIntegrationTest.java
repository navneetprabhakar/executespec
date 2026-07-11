package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Production-like V255 to V259 upgrade fixture for removal of active Sarvam routing state. */
class SarvamRemovalUpgradeIntegrationTest extends PostgresIntegrationBase {

  @Test
  void v259ClearsExecutableSarvamStateButPreservesHistoricalEvidence() {
    String schema = "sarvam_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Flyway preV256 = flyway(dataSource, schema, MigrationVersion.fromVersion("255"));

    try {
      preV256.migrate();
      seedV255Fixture(jdbc);

      var result = flyway(dataSource, schema, null).migrate();

      // V256 removes executable Sarvam state; V257 seeds the coordinator flag; V258/V259 add
      // subsequent task-cost and default auto-verification changes.
      assertThat(result.migrationsExecuted).isEqualTo(4);
      assertActiveDispatchStateWasReset(jdbc);
      assertHistoricalEvidenceWasPreserved(jdbc);
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

  private static void seedV255Fixture(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO org
          (id, name, domain_patterns, created_by_user_id, enabled_providers, model_overrides_json)
        VALUES
          ('org-upgrade', 'Upgrade Org', 'example.com', 'user-upgrade',
           'anthropic,sarvam,openai',
           '{"sarvam:PLANNING":"sarvam-105b","anthropic:PLANNING":"claude-sonnet-4-6"}')
        """);
    jdbc.update(
        """
        INSERT INTO app_user
          (id, email, first_name, password_hash, status, org_id, preferred_ai_provider)
        VALUES
          ('user-upgrade', 'upgrade@example.com', 'Upgrade', 'hash', 'ACTIVE', 'org-upgrade',
           'SARVAM')
        """);
    jdbc.update(
        """
        INSERT INTO project (id, org_id, name, created_by, ai_provider)
        VALUES ('project-upgrade', 'org-upgrade', 'Upgrade Project', 'user-upgrade', 'SARVAM')
        """);
    jdbc.update(
        """
        INSERT INTO pipeline_run
          (run_id, status, ai_provider, planner_model, worker_model, reviewer_model,
           batch_provider, created_by_user_id, org_id)
        VALUES
          ('run-active', 'WORKING', 'SARVAM', 'sarvam-105b', 'sarvam-105b', 'sarvam-30b',
           'SARVAM', 'user-upgrade', 'org-upgrade'),
          ('run-history', 'COMPLETE', 'SARVAM', 'sarvam-105b', 'sarvam-105b', 'sarvam-30b',
           'SARVAM', 'user-upgrade', 'org-upgrade')
        """);
    jdbc.update(
        """
        INSERT INTO execution_run
          (id, project_id, org_id, created_by, status, requirement, ai_provider)
        VALUES
          ('execution-active', 'project-upgrade', 'org-upgrade', 'user-upgrade', 'EXECUTING',
           'upgrade fixture', 'SARVAM')
        """);
    jdbc.update(
        """
        INSERT INTO agent_session
          (id, org_id, user_id, title, default_provider, default_model, run_id)
        VALUES
          ('session-active', 'org-upgrade', 'user-upgrade', 'Upgrade session', 'SARVAM',
           'sarvam-105b', 'run-active')
        """);
    jdbc.update(
        """
        INSERT INTO agent_task
          (task_id, run_id, status, current_provider, current_model,
           continuation_decision_json, continuation_actions_json)
        VALUES
          ('run-acti_T1', 'run-active', 'PARTIAL', 'SARVAM', 'sarvam-105b',
           '{"selectedCandidate":{"provider":"sarvam","model":"sarvam-105b"}}',
           '[{"provider":"sarvam","model":"sarvam-105b"}]'),
          ('run-acti_T2', 'run-active', 'PARTIAL', 'ANTHROPIC', 'claude-sonnet-4-6',
           '{"selectedCandidate":{"provider":"sarvam","model":"sarvam-105b"}}', NULL)
        """);

    jdbc.update(
        """
INSERT INTO managed_ai_usage_period
  (id, user_id, org_id, tier, period_start, period_end)
VALUES
  ('period-history', 'user-upgrade', 'org-upgrade', 'PRO', NOW(), NOW() + INTERVAL '1 month')
""");
    jdbc.update(
        """
        INSERT INTO managed_ai_usage_event
          (id, period_id, user_id, org_id, run_id, source_type, provider, model,
           routing_state_before, routing_state_after)
        VALUES
          ('usage-history', 'period-history', 'user-upgrade', 'org-upgrade', 'run-history',
           'RUN', 'sarvam', 'sarvam-105b', 'FULL', 'FULL')
        """);
    jdbc.update(
        """
        INSERT INTO run_step_usage (id, run_id, provider, model, input_tokens, output_tokens)
        VALUES ('step-history', 'run-history', 'sarvam', 'sarvam-105b', 100, 20)
        """);
    jdbc.update(
        """
        INSERT INTO benchmark_run
          (id, suite, dataset, provider, model, total_tasks, resolved_tasks, status)
        VALUES
          ('benchmark-history', 'SWE_BENCH', 'lite', 'sarvam', 'sarvam-105b', 2, 0, 'DONE')
        """);
    jdbc.update(
        """
        INSERT INTO benchmark_event (id, benchmark_run_id, seq, type, message, payload)
        VALUES
          ('benchmark-event-history', 'benchmark-history', 1, 'RUN_DONE',
           'Sarvam historical benchmark', '{"provider":"sarvam","model":"sarvam-105b"}')
        """);
    jdbc.update(
        """
        INSERT INTO agent_action_event (id, session_id, type, payload_json, action_id)
        VALUES
          ('action-history', 'session-active', 'RUN_VALIDATION_EVIDENCE',
           '{"provider":"sarvam","model":"sarvam-105b","historical":true}', 'history-action')
        """);
  }

  private static void assertActiveDispatchStateWasReset(JdbcTemplate jdbc) {
    assertThat(text(jdbc, "SELECT preferred_ai_provider FROM app_user WHERE id='user-upgrade'"))
        .isNull();
    assertThat(text(jdbc, "SELECT ai_provider FROM project WHERE id='project-upgrade'"))
        .isEqualTo("ANTHROPIC");
    assertThat(
            jdbc.queryForMap(
                "SELECT ai_provider, planner_model, worker_model, reviewer_model, batch_provider"
                    + " FROM pipeline_run WHERE run_id='run-active'"))
        .containsEntry("ai_provider", "ANTHROPIC")
        .containsEntry("planner_model", null)
        .containsEntry("worker_model", null)
        .containsEntry("reviewer_model", null)
        .containsEntry("batch_provider", null);
    assertThat(text(jdbc, "SELECT ai_provider FROM execution_run WHERE id='execution-active'"))
        .isNull();
    assertThat(
            jdbc.queryForMap(
                "SELECT default_provider, default_model FROM agent_session"
                    + " WHERE id='session-active'"))
        .containsEntry("default_provider", null)
        .containsEntry("default_model", null);
    assertThat(
            jdbc.queryForMap(
                "SELECT current_provider, current_model, continuation_decision_json,"
                    + " continuation_actions_json FROM agent_task WHERE task_id='run-acti_T1'"))
        .containsEntry("current_provider", null)
        .containsEntry("current_model", null)
        .containsEntry("continuation_decision_json", null)
        .containsEntry("continuation_actions_json", null);
    assertThat(
            text(
                jdbc,
                "SELECT continuation_decision_json FROM agent_task WHERE task_id='run-acti_T2'"))
        .isNull();
    assertThat(text(jdbc, "SELECT enabled_providers FROM org WHERE id='org-upgrade'"))
        .isEqualTo("anthropic,openai");
    assertThat(text(jdbc, "SELECT model_overrides_json FROM org WHERE id='org-upgrade'"))
        .contains("anthropic:PLANNING")
        .doesNotContainIgnoringCase("sarvam");
  }

  private static void assertHistoricalEvidenceWasPreserved(JdbcTemplate jdbc) {
    assertThat(
            jdbc.queryForMap(
                "SELECT ai_provider, planner_model, worker_model, reviewer_model, batch_provider"
                    + " FROM pipeline_run WHERE run_id='run-history'"))
        .containsEntry("ai_provider", "SARVAM")
        .containsEntry("planner_model", "sarvam-105b")
        .containsEntry("worker_model", "sarvam-105b")
        .containsEntry("reviewer_model", "sarvam-30b")
        .containsEntry("batch_provider", null);
    assertThat(
            jdbc.queryForMap(
                "SELECT provider, model FROM managed_ai_usage_event WHERE id='usage-history'"))
        .containsEntry("provider", "sarvam")
        .containsEntry("model", "sarvam-105b");
    assertThat(
            jdbc.queryForMap("SELECT provider, model FROM run_step_usage WHERE id='step-history'"))
        .containsEntry("provider", "sarvam")
        .containsEntry("model", "sarvam-105b");
    assertThat(
            jdbc.queryForMap(
                "SELECT provider, model FROM benchmark_run WHERE id='benchmark-history'"))
        .containsEntry("provider", "sarvam")
        .containsEntry("model", "sarvam-105b");
    assertThat(text(jdbc, "SELECT payload FROM benchmark_event WHERE id='benchmark-event-history'"))
        .contains("sarvam-105b");
    assertThat(
            text(
                jdbc,
                "SELECT payload_json::text FROM agent_action_event WHERE id='action-history'"))
        .contains("sarvam-105b");
  }

  private static String text(JdbcTemplate jdbc, String sql) {
    return jdbc.queryForObject(sql, String.class);
  }
}
