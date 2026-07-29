package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof that duplicate GitHub deliveries have one database admission winner. */
@Tag("integration")
@Testcontainers
class CiFailureDeliveryClaimPostgresIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("ci_delivery_claim_test")
          .withUsername("test")
          .withPassword("test");

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(dataSource())
        .locations("classpath:db/migration")
        .target("359")
        .load()
        .migrate();
  }

  @Test
  void parallelClaimsAcrossIndependentConnectionsProduceOneWinner() throws Exception {
    UUID prId = insertPullRequest();
    String key = "CI_FAILURE:" + prId + "|check-77|sha-abcdef1234567890";
    int callers = 16;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<CiFailureDeliveryClaimStore.Claim> results = new CopyOnWriteArrayList<>();

    try (var pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        pool.submit(
            () -> {
              ready.countDown();
              if (!start.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start barrier timed out");
              }
              DataSource ds = dataSource();
              CiFailureDeliveryClaimStore store =
                  new CiFailureDeliveryClaimStore(new JdbcTemplate(ds));
              results.add(
                  new TransactionTemplate(new DataSourceTransactionManager(ds))
                      .execute(ignored -> store.claim(key, prId, "77", "abcdef1234567890", 3)));
              return null;
            });
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(results).hasSize(callers);
    assertThat(results.stream().filter(CiFailureDeliveryClaimStore.Claim::acquired).count())
        .isEqualTo(1);
    assertThat(
            results.stream()
                .filter(claim -> claim.outcome() == CiFailureDeliveryClaimStore.Outcome.IN_PROGRESS)
                .count())
        .isEqualTo(callers - 1L);
    assertThat(
            new JdbcTemplate(dataSource())
                .queryForObject(
                    "SELECT count(*) FROM ci_failure_delivery_claim WHERE finding_key=?",
                    Long.class,
                    key))
        .isEqualTo(1L);
    assertThat(
            new JdbcTemplate(dataSource())
                .queryForObject(
                    "SELECT ci_auto_fix_attempts FROM pull_request_record WHERE id=?",
                    Integer.class,
                    prId))
        .isEqualTo(1);
  }

  @Test
  void failedAdmissionCanBeFencedAndReclaimedThenBecomesPermanentDuplicate() {
    UUID prId = insertPullRequest();
    String key = "CI_FAILURE:" + prId + "|check-88|sha-fedcba9876543210";
    DataSource ds = dataSource();
    CiFailureDeliveryClaimStore store = new CiFailureDeliveryClaimStore(new JdbcTemplate(ds));
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

    var first = tx.execute(ignored -> store.claim(key, prId, "88", "fedcba9876543210", 3));
    assertThat(first.acquired()).isTrue();
    assertThat(Boolean.TRUE.equals(tx.execute(ignored -> store.fail(first, "SUBMIT_REJECTED"))))
        .isTrue();
    assertThat(
            new JdbcTemplate(ds)
                .queryForObject(
                    "SELECT ci_auto_fix_attempts FROM pull_request_record WHERE id=?",
                    Integer.class,
                    prId))
        .isZero();

    var retry = tx.execute(ignored -> store.claim(key, prId, "88", "fedcba9876543210", 3));
    assertThat(retry.acquired()).isTrue();
    assertThat(retry.token()).isGreaterThan(first.token());

    String runId = "ci-patch-" + UUID.randomUUID();
    new JdbcTemplate(dataSource())
        .update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'PLANNING')", runId);
    assertThat(Boolean.TRUE.equals(tx.execute(ignored -> store.complete(retry, runId)))).isTrue();

    var duplicate = tx.execute(ignored -> store.claim(key, prId, "88", "fedcba9876543210", 3));
    assertThat(duplicate.outcome()).isEqualTo(CiFailureDeliveryClaimStore.Outcome.DISPATCHED);
    assertThat(duplicate.runId()).isEqualTo(runId);
    assertThat(Boolean.TRUE.equals(tx.execute(ignored -> store.complete(first, "stale-run"))))
        .isFalse();
  }

  @Test
  void expiredClaimRecoversAtomicallyStampedRunInsteadOfDispatchingAgain() {
    UUID prId = insertPullRequest();
    String key = "CI_FAILURE:" + prId + "|check-99|sha-0123456789abcdef";
    DataSource ds = dataSource();
    JdbcTemplate jdbc = new JdbcTemplate(ds);
    CiFailureDeliveryClaimStore store = new CiFailureDeliveryClaimStore(jdbc);
    TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));

    var first = tx.execute(ignored -> store.claim(key, prId, "99", "0123456789abcdef", 3));
    assertThat(first.acquired()).isTrue();
    String runId = "ci-recovered-" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,triggered_by_finding) VALUES (?, 'PLANNING', ?)",
        runId,
        key);
    jdbc.update(
        "UPDATE ci_failure_delivery_claim SET lease_expires_at=clock_timestamp()-INTERVAL '1"
            + " second' WHERE finding_key=?",
        key);

    var recovered = tx.execute(ignored -> store.claim(key, prId, "99", "0123456789abcdef", 3));
    assertThat(recovered.outcome()).isEqualTo(CiFailureDeliveryClaimStore.Outcome.DISPATCHED);
    assertThat(recovered.runId()).isEqualTo(runId);
    assertThat(
            jdbc.queryForObject(
                "SELECT ci_auto_fix_attempts FROM pull_request_record WHERE id=?",
                Integer.class,
                prId))
        .isEqualTo(1);
  }

  private static UUID insertPullRequest() {
    UUID id = UUID.randomUUID();
    new JdbcTemplate(dataSource())
        .update(
            """
            INSERT INTO pull_request_record
              (id, run_id, repo_full_name, pr_number, pr_url, head_branch, base_branch,
               status, kind, created_at, updated_at, ci_auto_fix_attempts, ci_status)
            VALUES (?, NULL, ?, ?, ?, 'feature', 'main', 'OPEN', 'RUN',
                    clock_timestamp(), clock_timestamp(), 0, 'UNKNOWN')
            """,
            id,
            "acme/app-" + id,
            Math.abs(id.hashCode()),
            "https://github.com/acme/app/pull/" + id);
    return id;
  }

  private static DataSource dataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName(POSTGRES.getDriverClassName());
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
