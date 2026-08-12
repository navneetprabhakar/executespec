package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for durable poison-record counting, dead-lettering, and resolution. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationReconciliationFailurePostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_reconciliation_failure")
          .withUsername("test")
          .withPassword("test");

  @Test
  void repeatedFailureDeadLettersAtThresholdAndSuccessResolvesOperatorBacklog() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    WorkspaceGenerationReconciliationFailureStore store =
        new WorkspaceGenerationReconciliationFailureStore(
            jdbc, new DataSourceTransactionManager(dataSource));
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES ('run-poison','WORKING','BUILD','SHARED_KERNEL_V2')");

    var first =
        store.recordFailure(
            WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN,
            "run-poison",
            "RUN_TERMINAL_RECONCILIATION_FAILED",
            3);
    var second =
        store.recordFailure(
            WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN,
            "run-poison",
            "RUN_TERMINAL_RECONCILIATION_FAILED",
            3);
    var third =
        store.recordFailure(
            WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN,
            "run-poison",
            "RUN_TERMINAL_RECONCILIATION_FAILED",
            3);

    assertThat(first.failureCount()).isEqualTo(1);
    assertThat(first.deadLetterAt()).isNull();
    assertThat(second.failureCount()).isEqualTo(2);
    assertThat(second.deadLetterAt()).isNull();
    assertThat(third.failureCount()).isEqualTo(3);
    assertThat(third.deadLetterAt()).isNotNull();
    assertThat(store.readOpen())
        .extracting(WorkspaceGenerationReconciliationFailureStore.Failure::identityKey)
        .containsExactly("run-poison");

    store.recordSuccess(
        WorkspaceGenerationReconciliationFailureStore.IdentityKind.RUN, "run-poison");
    assertThat(store.readOpen()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT resolved_at IS NOT NULL FROM workspace_generation_reconciliation_failure"
                    + " WHERE identity_kind='RUN' AND identity_key='run-poison'",
                Boolean.class))
        .isTrue();
  }
}
