package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL proof that V306 makes preview-render single-flight a schema fact.
 *
 * <p>V302 left "one unfinished render per run" to a read-then-write in application code, which two
 * concurrent requests can interleave — spending managed AI credits twice for one run. An in-JVM
 * lock cannot close that across instances, so the constraint has to be in the database.
 */
@Tag("integration")
@Testcontainers
class PreviewRenderSingleFlightMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("preview_render_single_flight_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void oneRunCanHaveOnlyOneUnfinishedRenderAndCanBeRenderedAgainAfterwards() {
    String schema = "preview_sf_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("305")).migrate();
      assertThat(flyway(dataSource, schema, MigrationVersion.fromVersion("306")).migrate())
          .satisfies(r -> assertThat(r.migrationsExecuted).isEqualTo(1));

      insertJob(jdbc, "run-1", "QUEUED", null);

      // Second concurrent request for the same run: rejected by the database, not by a lucky read.
      assertThatThrownBy(() -> insertJob(jdbc, "run-1", "QUEUED", null))
          .isInstanceOf(DuplicateKeyException.class)
          .hasMessageContaining("uq_preview_render_job_active_run");

      // RENDERING is the same active state, so a claimed job blocks a new one too.
      jdbc.update("UPDATE preview_render_job SET status='RENDERING' WHERE run_id='run-1'");
      assertThatThrownBy(() -> insertJob(jdbc, "run-1", "QUEUED", null))
          .isInstanceOf(DuplicateKeyException.class);

      // A different run is unaffected.
      insertJob(jdbc, "run-2", "QUEUED", null);

      // Once the render finishes the run can be rendered again: the index covers active rows only.
      jdbc.update(
          "UPDATE preview_render_job SET status='READY', completed_at=NOW() WHERE run_id='run-1'");
      insertJob(jdbc, "run-1", "QUEUED", null);

      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM preview_render_job WHERE run_id='run-1'", Long.class))
          .isEqualTo(2L);
    } finally {
      new JdbcTemplate(dataSource(null)).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  /** The wedge D8 describes: a RENDERING row with a lapsed lease must be reclaimable. */
  @Test
  void anExpiredLeaseReleasesARunWedgedByARestart() {
    String schema = "preview_lease_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("306")).migrate();

      // A job the dead instance left behind: RENDERING, lease long lapsed, never completed.
      insertJob(jdbc, "run-1", "RENDERING", Instant.now().minusSeconds(3600));

      // Without the reclaim, this row satisfies the active predicate forever and the run is stuck.
      assertThatThrownBy(() -> insertJob(jdbc, "run-1", "QUEUED", null))
          .isInstanceOf(DuplicateKeyException.class);

      int reclaimed =
          jdbc.update(
              """
              UPDATE preview_render_job
                 SET status='FAILED', failure_reason='LEASE_EXPIRED', completed_at=NOW(),
                     lease_owner=NULL, lease_expires_at=NULL
               WHERE run_id=? AND status IN ('QUEUED','RENDERING')
                 AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW()
              """,
              "run-1");

      assertThat(reclaimed).isEqualTo(1);
      insertJob(jdbc, "run-1", "QUEUED", null);
    } finally {
      new JdbcTemplate(dataSource(null)).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  // ---------------------------------------------------------------- harness

  private void insertJob(JdbcTemplate jdbc, String runId, String status, Instant leaseExpiresAt) {
    jdbc.update(
        """
        INSERT INTO preview_render_job (id, run_id, status, lease_owner, lease_expires_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        runId,
        status,
        leaseExpiresAt == null ? null : "dead-instance",
        leaseExpiresAt == null ? null : java.sql.Timestamp.from(leaseExpiresAt));
  }

  private Flyway flyway(DataSource dataSource, String schema, MigrationVersion target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
        .target(target)
        .locations("classpath:db/migration")
        .load();
  }

  private static DriverManagerDataSource dataSource(String schema) {
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        schema == null
            ? baseUrl
            : baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
