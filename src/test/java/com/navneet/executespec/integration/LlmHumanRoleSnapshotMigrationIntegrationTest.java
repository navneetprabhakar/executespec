package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import com.navneet.executespec.cost.LlmUsageAuditQuery;
import com.navneet.executespec.cost.LlmUsageAuditService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V300 -> V301 proof that later membership edits cannot rewrite usage history. */
@Tag("integration")
@Testcontainers
class LlmHumanRoleSnapshotMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("llm_human_role_snapshot_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void migrationBackfillsOnceAndAuditUsesSnapshotAfterRoleChange() {
    String schema = "role_snapshot_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("300")).migrate();
      jdbc.update(
          "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id)"
              + " VALUES ('role-org','Role Org','example.com','ENTERPRISE','role-user')");
      jdbc.update(
          "INSERT INTO app_user"
              + " (id,email,first_name,password_hash,tier,status,org_id,global_role)"
              + " VALUES ('role-user','role@example.com','Role','hash','ENTERPRISE','ACTIVE',"
              + " 'role-org','USER')");
      jdbc.update(
          "INSERT INTO org_member (id,org_id,user_id,org_role)"
              + " VALUES ('role-member','role-org','role-user','ORG_ADMIN')");
      jdbc.update(
          "INSERT INTO llm_usage_attempt_intent"
              + " (physical_attempt_id,logical_call_id,attempt_kind,attempt_ordinal,correlation_id,"
              + " org_id,user_id,feature,source_type,source_id,run_id,agent_role,provider,model,"
              + " model_tier,key_source) VALUES"
              + " ('role-attempt','role-logical','PRIMARY',1,'role-correlation','role-org',"
              + " 'role-user','WORKER','PIPELINE_RUN','role-run','role-run','WorkerAgent',"
              + " 'deepseek','deepseek-v4','WORKHORSE','ORG')");

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("301"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT human_role_snapshot FROM llm_usage_attempt_intent"
                      + " WHERE physical_attempt_id='role-attempt'",
                  String.class))
          .isEqualTo("ORG_ADMIN");

      jdbc.update("UPDATE org_member SET org_role='DEVELOPER' WHERE id='role-member'");
      var report =
          new LlmUsageAuditService(jdbc)
              .report(
                  LlmUsageAuditQuery.of(
                      "role-org",
                      LocalDate.now(ZoneOffset.UTC).minusDays(1),
                      LocalDate.now(ZoneOffset.UTC).plusDays(1),
                      "UTC",
                      0,
                      20));
      assertThat(report.attempts())
          .singleElement()
          .extracting(attempt -> attempt.userRole())
          .isEqualTo("ORG_ADMIN");
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
