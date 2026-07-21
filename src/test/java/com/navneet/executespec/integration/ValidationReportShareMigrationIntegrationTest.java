package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V293 -> V294 rehearsal for retiring legacy validation-report bearers. */
@Tag("integration")
@Testcontainers
class ValidationReportShareMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("validation_report_share_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeHashesAndClearsExistingBearerAndNormalizesRollingLegacyWrite() throws Exception {
    String schema = "report_share_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String existingToken = "A".repeat(43);
    String rollingToken = "B".repeat(43);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("293")).migrate();
      jdbc.update("INSERT INTO pipeline_run (run_id,status) VALUES ('run-share','COMPLETE')");
      jdbc.update(
          "INSERT INTO validation_report (id,run_id,share_token,shared_at)"
              + " VALUES ('report-share','run-share',?,NOW())",
          existingToken);

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("294"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      var migrated = jdbc.queryForMap("SELECT * FROM validation_report WHERE id='report-share'");
      assertThat(migrated.get("share_token")).isNull();
      assertThat(migrated.get("share_token_hash")).isEqualTo(sha256(existingToken));
      assertThat(migrated.get("share_token_prefix")).isEqualTo("AAAAAAAA");
      assertThat(migrated.get("share_expires_at")).isNotNull();

      // Simulate an old application node writing the removed plaintext field during a rolling
      // deployment. The DB trigger converts and clears it before commit.
      jdbc.update(
          "UPDATE validation_report SET share_token=?, shared_at=NOW() WHERE id='report-share'",
          rollingToken);
      var rolling = jdbc.queryForMap("SELECT * FROM validation_report WHERE id='report-share'");
      assertThat(rolling.get("share_token")).isNull();
      assertThat(rolling.get("share_token_hash")).isEqualTo(sha256(rollingToken));
      assertThat(rolling.get("share_token_prefix")).isEqualTo("BBBBBBBB");

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
