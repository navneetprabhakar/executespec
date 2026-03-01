package com.navneet.agentteam.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that all Flyway migrations run cleanly and produce the expected schema.
 */
@DisplayName("Flyway migrations (Integration)")
class FlywayMigrationIntegrationTest extends PostgresIntegrationBase {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("all migrations succeed and flyway_schema_history has 2 entries")
    void migrationCount() {
        var info = flyway.info().applied();
        // V1 baseline + V2 add_tenant_id (plus possibly the baseline marker itself)
        assertThat(info.length).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("pipeline_run table exists with tenant_id column")
    void pipelineRunHasTenantId() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'pipeline_run'",
            String.class
        );
        assertThat(columns).contains("tenant_id");
    }

    @Test
    @DisplayName("agent_task table exists with stuck detection columns")
    void agentTaskHasStuckColumns() {
        List<String> columns = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns " +
            "WHERE table_name = 'agent_task'",
            String.class
        );
        assertThat(columns).containsAll(List.of(
            "last_activity_at", "stuck_detected_at", "recovery_attempt", "tenant_id"
        ));
    }

    @Test
    @DisplayName("all 14 expected tables exist")
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
            String.class
        );
        assertThat(tables).containsAll(List.of(
            "pipeline_run", "agent_task", "agent_message", "file_lock",
            "agent_inbox", "gate_event", "compaction_event", "run_recovery",
            "prompt_audit_entry", "run_repo", "deployment",
            "integration_run", "integration_event_log", "model_pricing"
        ));
    }

    @Test
    @DisplayName("tenant_id indexes exist on key tables")
    void tenantIdIndexesExist() {
        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
            String.class
        );
        assertThat(indexes).containsAnyOf(
            "idx_pipeline_run_tenant_id",
            "idx_agent_task_tenant_id"
        );
    }
}
