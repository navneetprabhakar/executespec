package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.LlmUsageTerminalOutboxStore;
import com.navneet.executespec.cost.RunStepUsage;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V297 -> V298 cross-JVM terminal-usage recovery rehearsal. */
@Tag("integration")
@Testcontainers
class LlmUsageTerminalOutboxMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_usage_terminal_outbox_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void exactFactSurvivesWorkerLossAndMaterializesExactlyOnce() {
    String schema = "usage_outbox_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("297")).migrate();
      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("298"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      insertIntent(jdbc, "attempt-1");
      var store =
          new LlmUsageTerminalOutboxStore(jdbc, new ObjectMapper().findAndRegisterModules());
      var row = usage("attempt-1");

      assertThat(store.stage(row)).isEqualTo(LlmUsageTerminalOutboxStore.StageResult.STAGED);
      var abandoned = store.claimOne("attempt-1", "node-a", Duration.ofSeconds(30)).orElseThrow();
      assertThat(store.claimOne("attempt-1", "node-b", Duration.ofSeconds(30))).isEmpty();

      // Simulate node-a dying after its committed claim and before ledger materialization.
      jdbc.update(
          "UPDATE llm_usage_terminal_outbox SET lease_expires_at=NOW()-INTERVAL '1 second'"
              + " WHERE physical_attempt_id='attempt-1'");
      var recovered = store.claimOne("attempt-1", "node-b", Duration.ofSeconds(30)).orElseThrow();
      assertThat(recovered.fencingToken()).isGreaterThan(abandoned.fencingToken());
      assertThat(store.materialize(recovered, store.deserialize(recovered))).isTrue();

      var canonical =
          jdbc.queryForMap("SELECT * FROM run_step_usage WHERE physical_attempt_id='attempt-1'");
      assertThat(((Number) canonical.get("input_tokens")).longValue()).isEqualTo(10L);
      assertThat(((Number) canonical.get("output_tokens")).longValue()).isEqualTo(5L);
      assertThat(((Number) canonical.get("reasoning_tokens")).longValue()).isEqualTo(3L);
      assertThat(((Number) canonical.get("cost_usd")).doubleValue()).isEqualTo(0.01);
      assertThat(canonical.get("terminal_payload_sha256")).isEqualTo(recovered.payloadSha256());
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM llm_usage_terminal_outbox WHERE"
                      + " physical_attempt_id='attempt-1'",
                  String.class))
          .isEqualTo("COMPLETED");
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM run_step_usage WHERE physical_attempt_id='attempt-1'",
                  Long.class))
          .isEqualTo(1L);

      assertThat(store.stage(row))
          .isEqualTo(LlmUsageTerminalOutboxStore.StageResult.ALREADY_STAGED);
      var conflicting = usage("attempt-1");
      conflicting.setInputTokens(999);
      assertThatThrownBy(() -> store.stage(conflicting))
          .isInstanceOf(LlmUsageTerminalOutboxStore.TerminalUsageConflictException.class)
          .hasMessageContaining("different terminal usage payload");

      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void insertIntent(JdbcTemplate jdbc, String physicalAttemptId) {
    jdbc.update(
        """
        INSERT INTO llm_usage_attempt_intent
          (physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,
           org_id,user_id,project_id,feature,source_type,source_id,run_id,task_id,agent_role,
           step_id,provider,model,model_tier,key_source)
        VALUES (?, 'logical-1', 'PRIMARY', 1, 'corr-1', 'org-1', 'user-1', 'project-1',
                'WORKER', 'PIPELINE_RUN', 'run-1', 'run-1', 'task-1', 'WorkerAgent',
                'worker.iteration', 'deepseek', 'deepseek-v4', 'CAPABLE', 'ORG')
        """,
        physicalAttemptId);
  }

  private static RunStepUsage usage(String attemptId) {
    return RunStepUsage.builder()
        .id(attemptId)
        .runId("run-1")
        .taskId("task-1")
        .agentRole("WorkerAgent")
        .stepId("worker.iteration")
        .correlationId("corr-1")
        .orgId("org-1")
        .userId("user-1")
        .projectId("project-1")
        .feature("WORKER")
        .sourceType("PIPELINE_RUN")
        .sourceId("run-1")
        .keySource("ORG")
        .attemptStatus("SUCCESS")
        .attemptNumber(1)
        .latencyMs(12)
        .provider("deepseek")
        .model("deepseek-v4")
        .modelTier("CAPABLE")
        .logicalCallId("logical-1")
        .physicalAttemptId(attemptId)
        .attemptKind("PRIMARY")
        .attemptOrdinal(1)
        .inputTokens(10)
        .outputTokens(5)
        .cacheWriteTokens(1)
        .cacheReadTokens(2)
        .reasoningTokens(3)
        .costUsd(0.01)
        .callIndex(1)
        .createdAt(Instant.parse("2026-07-18T12:00:00Z"))
        .build();
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
