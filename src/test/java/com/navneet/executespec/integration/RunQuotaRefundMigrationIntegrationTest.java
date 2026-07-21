package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V299 -> V300 idempotency and cross-JVM locking rehearsal. */
@Tag("integration")
@Testcontainers
class RunQuotaRefundMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("run_quota_refund_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void migrationIsRepeatableAndOneRunCanHaveOnlyOneLockedRefundIntent() throws Exception {
    String schema = "quota_refund_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("299")).migrate();
      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("300"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      String insert =
          """
          INSERT INTO run_quota_refund
              (run_id,user_id,credits,status,attempt_count,next_attempt_at,created_at,updated_at)
          VALUES ('run-1','user-1',1,'PENDING',0,NOW(),NOW(),NOW())
          ON CONFLICT (run_id) DO NOTHING
          """;
      assertThat(jdbc.update(insert)).isEqualTo(1);
      assertThat(jdbc.update(insert)).isZero();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM run_quota_refund WHERE run_id='run-1'", Long.class))
          .isEqualTo(1L);

      try (Connection owner = dataSource.getConnection();
          Connection contender = dataSource.getConnection()) {
        owner.setAutoCommit(false);
        contender.setAutoCommit(false);
        queryForUpdate(owner);
        contender.createStatement().execute("SET LOCAL lock_timeout='200ms'");
        assertThatThrownBy(() -> queryForUpdate(contender)).isInstanceOf(SQLException.class);
        contender.rollback();
        owner
            .createStatement()
            .executeUpdate(
                "UPDATE run_quota_refund SET status='APPLIED', applied_at=NOW()"
                    + " WHERE run_id='run-1'");
        owner.commit();
      }

      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM run_quota_refund WHERE run_id='run-1'", String.class))
          .isEqualTo("APPLIED");
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static void queryForUpdate(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var ignored =
            statement.executeQuery(
                "SELECT run_id FROM run_quota_refund WHERE run_id='run-1' FOR UPDATE")) {
      ignored.next();
    }
  }

  private static DriverManagerDataSource dataSource(String schema) {
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Flyway flyway(DataSource dataSource, String schema, MigrationVersion target) {
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
