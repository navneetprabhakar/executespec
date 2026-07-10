package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies that all Flyway migrations run cleanly and produce the expected schema. */
@DisplayName("Flyway migrations (Integration)")
class FlywayMigrationIntegrationTest extends PostgresIntegrationBase {

  private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
  private static final Pattern MIGRATION_FILE = Pattern.compile("^V\\d+(?:_\\d+)?__.*\\.sql$");

  private final Flyway flyway;
  private final JdbcTemplate jdbc;

  @Autowired
  FlywayMigrationIntegrationTest(Flyway flyway, JdbcTemplate jdbc) {
    this.flyway = flyway;
    this.jdbc = jdbc;
  }

  @Test
  @DisplayName("all migrations succeed and flyway_schema_history reaches current head")
  void migrationCount() throws IOException {
    var info = flyway.info().applied();
    assertThat(info.length).isEqualTo(sourceMigrationCount());
  }

  private static long sourceMigrationCount() throws IOException {
    try (var stream = Files.list(MIGRATION_DIR)) {
      return stream
          .map(path -> path.getFileName().toString())
          .filter(name -> MIGRATION_FILE.matcher(name).matches())
          .count();
    }
  }

  @Test
  @DisplayName("pipeline_run table exists with tenant_id column")
  void pipelineRunHasTenantId() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'pipeline_run'",
            String.class);
    assertThat(columns).contains("tenant_id");
  }

  @Test
  @DisplayName("agent_task table exists with stuck detection columns")
  void agentTaskHasStuckColumns() {
    List<String> columns =
        jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'agent_task'",
            String.class);
    assertThat(columns)
        .containsAll(
            List.of("last_activity_at", "stuck_detected_at", "recovery_attempt", "tenant_id"));
  }

  @Test
  @DisplayName("all 14 expected tables exist")
  void allTablesExist() {
    List<String> tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class);
    assertThat(tables)
        .containsAll(
            List.of(
                "pipeline_run",
                "agent_task",
                "agent_message",
                "file_lock",
                "agent_inbox",
                "gate_event",
                "compaction_event",
                "run_recovery",
                "prompt_audit_entry",
                "run_repo",
                "deployment",
                "integration_run",
                "integration_event_log",
                "model_pricing"));
  }

  @Test
  @DisplayName("tenant_id indexes exist on key tables")
  void tenantIdIndexesExist() {
    List<String> indexes =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
    assertThat(indexes).containsAnyOf("idx_pipeline_run_tenant_id", "idx_agent_task_tenant_id");
  }

  @Test
  @DisplayName("V256 cleanup columns exist for an upgrade-safe data migration")
  void sarvamCleanupColumnsExist() {
    assertThat(columns("app_user")).contains("preferred_ai_provider");
    assertThat(columns("project")).contains("ai_provider");
    assertThat(columns("pipeline_run")).contains("ai_provider", "batch_provider");
    assertThat(columns("execution_run")).contains("ai_provider");
    assertThat(columns("agent_session")).contains("default_provider", "default_model");
    assertThat(columns("agent_task")).contains("current_provider", "current_model");
    assertThat(columns("org")).contains("enabled_providers", "model_overrides_json");
  }

  private List<String> columns(String table) {
    return jdbc.queryForList(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND"
            + " table_name = ?",
        String.class,
        table);
  }
}
