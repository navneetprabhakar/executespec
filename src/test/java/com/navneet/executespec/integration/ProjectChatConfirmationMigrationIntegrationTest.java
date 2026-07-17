package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

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

/** Real PostgreSQL V291 -> V292 confirmation and append-only audit rehearsal. */
@Tag("integration")
@Testcontainers
class ProjectChatConfirmationMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("project_chat_confirmation_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeCreatesHashedOneShotScopeAndMakesSecurityAuditDatabaseImmutable() {
    String schema = "chat_confirmation_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("291")).migrate();
      jdbc.update(
          "INSERT INTO org (id,name,domain_patterns,tier,created_by_user_id)"
              + " VALUES ('org-1','Org','example.com','ENTERPRISE','user-1')");
      jdbc.update(
          "INSERT INTO app_user (id,email,first_name,last_name,password_hash,tier,status,org_id)"
              + " VALUES"
              + " ('user-1','user@example.com','User','One','hash','ENTERPRISE','ACTIVE','org-1')");
      jdbc.update(
          "INSERT INTO project (id,org_id,name,created_by,ai_provider)"
              + " VALUES ('project-1','org-1','Project','user-1','DEEPSEEK')");
      jdbc.update(
          "INSERT INTO activity_audit (id,event_type,entity_type,entity_id)"
              + " VALUES ('audit-1','BEFORE_V292','PROJECT_CHAT','project-1')");

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("292"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      String id = UUID.randomUUID().toString();
      String hashA = "a".repeat(64);
      String hashB = "b".repeat(64);
      String hashC = "c".repeat(64);
      assertThat(
              jdbc.update(
                  "INSERT INTO project_chat_confirmation"
                      + " (id,project_id,org_id,actor_user_id,action,capability,provider,model,"
                      + " target_json,target_hash,scope_hash,token_hash,expires_at)"
                      + " VALUES (?::uuid,'project-1','org-1','user-1','GENERATE_PROJECT_DOC',"
                      + " 'SPEND_AI','deepseek','deepseek-v4','{\"docType\":\"SETUP\"}'::jsonb,"
                      + " ?,?,?,NOW()+INTERVAL '5 minutes')",
                  id,
                  hashA,
                  hashB,
                  hashC))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM project_chat_confirmation"
                      + " WHERE token_hash=? AND jsonb_exists(target_json, 'docType')",
                  Long.class,
                  hashC))
          .isEqualTo(1L);

      assertThatThrownBy(
              () -> jdbc.update("UPDATE activity_audit SET metadata='tampered' WHERE id='audit-1'"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> jdbc.update("DELETE FROM activity_audit WHERE id='audit-1'"))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");

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
