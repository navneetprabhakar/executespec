package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL plus OS-file-lock rehearsal for cross-process workspace authority. */
@Tag("integration")
@Testcontainers
class RunWorkspaceAuthorityServiceIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_authority_test")
          .withUsername("test")
          .withPassword("test");

  @TempDir Path tempDir;

  @Test
  void osAndDatabaseFencesSerializeAuthorityAndExpiredLeaseFailsClosed() {
    String schema = "workspace_authority_" + UUID.randomUUID().toString().replace("-", "");
    JdbcTemplate jdbc = jdbc(schema);
    jdbc.execute("CREATE SCHEMA " + schema);
    jdbc.execute(
        """
        CREATE TABLE run_workspace_authority (
          run_id VARCHAR(255) PRIMARY KEY,
          authority_type VARCHAR(24) NOT NULL,
          owner_token UUID NOT NULL,
          lease_expires_at TIMESTAMPTZ NOT NULL,
          heartbeat_at TIMESTAMPTZ NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL
        )
        """);

    RunWorkspaceAuthorityService hostA = service(jdbc, tempDir.resolve("host-a"));
    RunWorkspaceAuthorityService sameHost = service(jdbc, tempDir.resolve("host-a"));
    RunWorkspaceAuthorityService hostB = service(jdbc, tempDir.resolve("host-b"));

    try (RunWorkspaceAuthorityService.Lease lease =
        hostA.acquire("run-1", RunWorkspaceAuthorityService.Authority.CORRECTION)) {
      assertThat(sameHost.tryAcquire("run-1", RunWorkspaceAuthorityService.Authority.PUBLICATION))
          .as("the same-host OS lock rejects overlap before a second authority is issued")
          .isEmpty();
      assertThat(hostB.tryAcquire("run-1", RunWorkspaceAuthorityService.Authority.PUBLICATION))
          .as("a different-host file lock still loses at the shared PostgreSQL lease")
          .isEmpty();

      lease.assertOwned();
      jdbc.update(
          "UPDATE run_workspace_authority SET lease_expires_at = NOW() - INTERVAL '1 second'"
              + " WHERE run_id = ?",
          "run-1");
      assertThatThrownBy(lease::assertOwned)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("RUN_WORKSPACE_AUTHORITY_LOST");
    }

    try (RunWorkspaceAuthorityService.Lease reacquired =
        hostB.acquire("run-1", RunWorkspaceAuthorityService.Authority.PUBLICATION)) {
      reacquired.assertOwned();
    } finally {
      new JdbcTemplate(baseDataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  @Test
  void concurrentCloseReleasesDatabaseAndFileAuthorityExactlyOnce() throws Exception {
    String schema = "workspace_close_" + UUID.randomUUID().toString().replace("-", "");
    JdbcTemplate jdbc = jdbc(schema);
    jdbc.execute("CREATE SCHEMA " + schema);
    jdbc.execute(
        """
        CREATE TABLE run_workspace_authority (
          run_id VARCHAR(255) PRIMARY KEY,
          authority_type VARCHAR(24) NOT NULL,
          owner_token UUID NOT NULL,
          lease_expires_at TIMESTAMPTZ NOT NULL,
          heartbeat_at TIMESTAMPTZ NOT NULL,
          updated_at TIMESTAMPTZ NOT NULL
        )
        """);
    RunWorkspaceAuthorityService service = service(jdbc, tempDir.resolve("close-host"));
    RunWorkspaceAuthorityService.Lease lease =
        service.acquire("run-close", RunWorkspaceAuthorityService.Authority.CORRECTION);
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(lease::close);
      var second = executor.submit(lease::close);
      first.get();
      second.get();
      assertThat(
              service.tryAcquire("run-close", RunWorkspaceAuthorityService.Authority.PUBLICATION))
          .isPresent()
          .get()
          .satisfies(RunWorkspaceAuthorityService.Lease::close);
    } finally {
      executor.shutdownNow();
      new JdbcTemplate(baseDataSource()).execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private RunWorkspaceAuthorityService service(JdbcTemplate jdbc, Path workspace) {
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(workspace.toString());
    return new RunWorkspaceAuthorityService(jdbc, properties);
  }

  private JdbcTemplate jdbc(String schema) {
    String separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
    return new JdbcTemplate(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + separator + "currentSchema=" + schema + ",public",
            POSTGRES.getUsername(),
            POSTGRES.getPassword()));
  }

  private DriverManagerDataSource baseDataSource() {
    return new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
