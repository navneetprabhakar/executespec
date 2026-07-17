package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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

/** Real PostgreSQL V286 -> V287 rehearsal for public bearer hashing/expiry/append-only audit. */
@Tag("integration")
@Testcontainers
class ProjectShareLinkMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("share_link_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeHashesAndClearsLegacyBearerBoundsExpiryAndRejectsAuditMutation() throws Exception {
    String schema = "share_link_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID linkId = UUID.randomUUID();
    String plaintext = "A".repeat(43);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("286")).migrate();
      jdbc.update(
          """
          INSERT INTO org (id, name, domain_patterns, created_by_user_id)
          VALUES ('org-share', 'Share Org', 'example.com', 'user-share')
          """);
      jdbc.update(
          """
          INSERT INTO app_user
            (id, email, first_name, last_name, password_hash, status, org_id)
          VALUES
            ('user-share', 'share@example.com', 'Share', 'Owner', 'hash', 'ACTIVE', 'org-share')
          """);
      jdbc.update(
          """
          INSERT INTO project (id, org_id, name, created_by)
          VALUES ('project-share', 'org-share', 'Shared project', 'user-share')
          """);
      jdbc.update(
          """
          INSERT INTO project_share_link
            (id, project_id, token, surface, created_by_user_id, expires_at)
          VALUES (?, 'project-share', ?, 'PROJECT_OVERVIEW', 'user-share', NULL)
          """,
          linkId,
          plaintext);

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("287"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var row = jdbc.queryForMap("SELECT * FROM project_share_link WHERE id=?", linkId);
      assertThat(row.get("token")).isNull();
      assertThat(row.get("token_hash")).isEqualTo(sha256(plaintext));
      assertThat(row.get("token_prefix")).isEqualTo("AAAAAAAA");
      assertThat(row.get("expires_at")).isNotNull();

      UUID eventId = UUID.randomUUID();
      jdbc.update(
          """
          INSERT INTO project_share_link_audit
            (id, link_id, project_id, event_type, outcome, surface)
          VALUES (?, ?, 'project-share', 'ACCESS', 'ALLOWED', 'PROJECT_OVERVIEW')
          """,
          eventId,
          linkId);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE project_share_link_audit SET outcome='CHANGED' WHERE id=?", eventId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");

      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static String sha256(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
