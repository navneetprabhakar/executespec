package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.export.ProjectExportJobStore;

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

/** Real PostgreSQL V288 -> V289 upgrade plus restart/takeover/fencing rehearsal. */
@Tag("integration")
@Testcontainers
class ProjectExportMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("project_export_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeSupportsSingleWinnerRestartTakeoverFencingExpiryAndAppendOnlyAudit()
      throws Exception {
    String schema = "project_export_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("288")).migrate();
      jdbc.update(
          "INSERT INTO org (id, name, domain_patterns, created_by_user_id) VALUES"
              + " ('org-export', 'Export Org', 'example.com', 'user-export')");
      jdbc.update(
          """
INSERT INTO app_user
  (id, email, first_name, last_name, password_hash, status, org_id)
VALUES
  ('user-export', 'export@example.com', 'Export', 'Owner', 'hash', 'ACTIVE', 'org-export')
""");
      jdbc.update(
          "INSERT INTO project (id, org_id, name, created_by) VALUES"
              + " ('project-export', 'org-export', 'Export project', 'user-export')");

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("289"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      ProjectExportJobStore first = new ProjectExportJobStore(jdbc, new ObjectMapper());
      UUID id = UUID.randomUUID();
      var queued =
          first.createOrGet(
              id,
              "project-export",
              "org-export",
              "user-export",
              "request-key-1",
              "project-export.tar.gz");
      assertThat(queued.status()).isEqualTo(ProjectExportJobStore.Status.QUEUED);
      assertThat(first.getScoped(id, "project-export", "foreign-org")).isEmpty();

      try (var contenders = Executors.newFixedThreadPool(2)) {
        var calls =
            java.util.List.of(
                (java.util.concurrent.Callable<java.util.Optional<ProjectExportJobStore.Lease>>)
                    () -> first.tryAcquire(id, "jvm-a", Duration.ofMinutes(10), 3),
                (java.util.concurrent.Callable<java.util.Optional<ProjectExportJobStore.Lease>>)
                    () -> first.tryAcquire(id, "jvm-b", Duration.ofMinutes(10), 3));
        long winners = contenders.invokeAll(calls).stream().filter(f -> get(f).isPresent()).count();
        assertThat(winners).isEqualTo(1);
      }

      ProjectExportJobStore.Lease stale =
          new ProjectExportJobStore.Lease(
              first.get(id).orElseThrow(),
              first.get(id).orElseThrow().leaseOwner(),
              first.get(id).orElseThrow().leaseToken(),
              first.get(id).orElseThrow().leaseExpiresAt());
      jdbc.update(
          "UPDATE project_export_job SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
          id);

      // Simulate a fresh JVM using only database state.
      ProjectExportJobStore restarted = new ProjectExportJobStore(jdbc, new ObjectMapper());
      var winner =
          restarted.tryAcquire(id, "jvm-after-restart", Duration.ofMinutes(10), 3).orElseThrow();
      assertThat(winner.fencingToken()).isGreaterThan(stale.fencingToken());
      assertThat(
              first.complete(
                  stale, "local:stale.tar.gz", 5, "a".repeat(64), Instant.now().plusSeconds(3600)))
          .isFalse();
      assertThat(
              restarted.complete(
                  winner,
                  "local:current.tar.gz",
                  7,
                  "b".repeat(64),
                  Instant.now().plusSeconds(3600)))
          .isTrue();
      assertThat(new ProjectExportJobStore(jdbc, new ObjectMapper()).get(id).orElseThrow().status())
          .isEqualTo(ProjectExportJobStore.Status.SUCCEEDED);

      jdbc.update(
          "UPDATE project_export_job SET expires_at=NOW()-INTERVAL '1 second' WHERE id=?", id);
      var expiring = restarted.beginExpiry(id).orElseThrow();
      assertThat(expiring.status()).isEqualTo(ProjectExportJobStore.Status.EXPIRING);
      assertThat(restarted.finishExpiry(id, "local:current.tar.gz")).isTrue();
      assertThat(restarted.get(id).orElseThrow().status())
          .isEqualTo(ProjectExportJobStore.Status.EXPIRED);

      UUID cancelledId = UUID.randomUUID();
      restarted.createOrGet(
          cancelledId,
          "project-export",
          "org-export",
          "user-export",
          "request-key-cancel",
          "cancelled-project-export.tar.gz");
      restarted
          .tryAcquire(cancelledId, "jvm-before-cancel-restart", Duration.ofMinutes(10), 3)
          .orElseThrow();
      assertThat(
              restarted
                  .requestCancellation(cancelledId, "project-export", "org-export", "user-export")
                  .orElseThrow()
                  .status())
          .isEqualTo(ProjectExportJobStore.Status.CANCEL_REQUESTED);
      jdbc.update(
          "UPDATE project_export_job SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
          cancelledId);

      ProjectExportJobStore afterCancelledWorkerRestart =
          new ProjectExportJobStore(jdbc, new ObjectMapper());
      assertThat(afterCancelledWorkerRestart.finishAbandonedCancellations()).isEqualTo(1);
      assertThat(afterCancelledWorkerRestart.get(cancelledId).orElseThrow().status())
          .isEqualTo(ProjectExportJobStore.Status.CANCELLED);

      UUID auditId =
          jdbc.queryForObject(
              "SELECT id FROM project_export_audit WHERE job_id=? LIMIT 1", UUID.class, id);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE project_export_audit SET outcome='CHANGED' WHERE id=?", auditId))
          .isInstanceOf(DataAccessException.class)
          .hasMessageContaining("append-only");

      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static <T> T get(java.util.concurrent.Future<T> future) {
    try {
      return future.get();
    } catch (Exception failure) {
      throw new AssertionError(failure);
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
