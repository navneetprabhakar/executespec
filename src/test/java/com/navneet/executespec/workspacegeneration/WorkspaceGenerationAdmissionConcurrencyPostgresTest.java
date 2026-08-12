package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cross-connection proof that PostgreSQL, not JVM serialization, owns BUILD admission uniqueness.
 */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationAdmissionConcurrencyPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_admission_race")
          .withUsername("test")
          .withPassword("test");

  @Test
  void exactlyOneOfTwoConcurrentUnresolvedAttemptsCommitsForOneRun() throws Exception {
    DriverManagerDataSource dataSource = dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    String runId = "admission-race-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        runId);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Outcome> first = executor.submit(() -> insertUnresolved(runId, "T1", ready, start));
      Future<Outcome> second = executor.submit(() -> insertUnresolved(runId, "T2", ready, start));
      ready.await();
      start.countDown();
      List<Outcome> outcomes = List.of(first.get(), second.get());

      assertThat(outcomes).filteredOn(Outcome::committed).hasSize(1);
      assertThat(outcomes)
          .filteredOn(outcome -> !outcome.committed())
          .singleElement()
          .satisfies(
              failure -> {
                assertThat(failure.sqlState()).isEqualTo("23505");
                assertThat(failure.message())
                    .contains("uq_workspace_generation_attempt_active_run");
              });
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM workspace_generation_attempt WHERE run_id=?"
                      + " AND state IN ('ADMITTED','OPERATION_CLAIMED','AUTHORITY_ACQUIRED',"
                      + "'PREPARED','MUTATING','VERIFYING','VERIFIED','CHECKPOINTING',"
                      + "'ROLLING_BACK','RECOVERY_FAILED')",
                  Integer.class,
                  runId))
          .isEqualTo(1);
    }
  }

  private Outcome insertUnresolved(
      String runId, String taskId, CountDownLatch ready, CountDownLatch start) throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      ready.countDown();
      start.await();
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO workspace_generation_attempt("
                  + "id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,"
                  + "authority_digest,task_contract_digest,build_protocol_version)"
                  + " VALUES (?,?,?,?,1,'BUILD_TASK','ADMITTED',?,repeat('1',64),repeat('2',64),"
                  + "'SHARED_KERNEL_V2')")) {
        statement.setObject(1, UUID.randomUUID());
        statement.setString(2, runId);
        statement.setString(3, taskId);
        statement.setInt(4, "T1".equals(taskId) ? 0 : 1);
        statement.setString(5, "/tmp/" + runId + "/" + taskId);
        statement.executeUpdate();
        connection.commit();
        return new Outcome(true, null, null);
      } catch (SQLException failure) {
        connection.rollback();
        return new Outcome(false, failure.getSQLState(), failure.getMessage());
      }
    }
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private record Outcome(boolean committed, String sqlState, String message) {}
}
