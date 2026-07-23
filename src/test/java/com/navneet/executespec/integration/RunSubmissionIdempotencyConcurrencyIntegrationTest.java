package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.navneet.executespec.run.RunIdempotencyService;
import com.navneet.executespec.run.RunIdempotencyStatus;
import com.navneet.executespec.run.RunSubmissionClaimStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL proof for the V303 durable run-submission idempotency claim.
 *
 * <p>Each {@link RunIdempotencyService} instance is built over its OWN {@link DataSource} against
 * the same database, so a race between two instances exercises the same code path a race between
 * two backend JVMs would. That is stronger than a mocked repository, and weaker than the two-JVM
 * deployment rehearsal the execution plan requires as a later gate.
 */
@Tag("integration")
@Testcontainers
class RunSubmissionIdempotencyConcurrencyIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("run_idempotency_test")
          .withUsername("test")
          .withPassword("test");

  private static final String FINGERPRINT_A = "a".repeat(64);
  private static final String FINGERPRINT_B = "b".repeat(64);

  @BeforeAll
  static void migrate() {
    DataSource dataSource = dataSource();
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .target("303")
        .load()
        .migrate();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    // Stand-ins for the two side effects that must happen exactly once per claim.
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS fake_run (run_id VARCHAR(64) PRIMARY KEY, k VARCHAR(200))");
    jdbc.execute("CREATE TABLE IF NOT EXISTS fake_quota (id SERIAL PRIMARY KEY, k VARCHAR(200))");
  }

  /**
   * The headline guarantee: true parallelism across two independent pools yields exactly one run,
   * exactly one quota reservation, and every loser observes the WINNER's runId.
   */
  @Test
  void parallelClaimsAcrossTwoPoolsProduceExactlyOneRunAndOneQuotaReservation() throws Exception {
    String key = "race-" + UUID.randomUUID();
    String actor = "actor-" + UUID.randomUUID();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource());

    int callers = 16;
    // Two services over two DataSources = two connection pools racing on one database.
    RunIdempotencyService jvmA = service();
    RunIdempotencyService jvmB = service();
    JdbcTemplate sideEffectsA = new JdbcTemplate(dataSource());
    JdbcTemplate sideEffectsB = new JdbcTemplate(dataSource());

    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    Map<Integer, String> observed = new ConcurrentHashMap<>();
    AtomicInteger claimedCount = new AtomicInteger();

    try (var pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        int index = i;
        RunIdempotencyService svc = index % 2 == 0 ? jvmA : jvmB;
        JdbcTemplate effects = index % 2 == 0 ? sideEffectsA : sideEffectsB;
        pool.submit(
            () -> {
              ready.countDown();
              if (!start.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start barrier timed out");
              }
              var claim = svc.claim(actor, key, FINGERPRINT_A);
              if (claim.status() == RunIdempotencyStatus.CLAIMED) {
                claimedCount.incrementAndGet();
                String runId = "run-" + UUID.randomUUID();
                // The two side effects the defect duplicated, in submit order.
                effects.update("INSERT INTO fake_quota (k) VALUES (?)", key);
                effects.update("INSERT INTO fake_run (run_id,k) VALUES (?,?)", runId, key);
                svc.complete(claim, runId);
                observed.put(index, runId);
              } else if (claim.status() == RunIdempotencyStatus.DUPLICATE) {
                observed.put(index, claim.runId());
              } else {
                observed.put(index, "UNEXPECTED:" + claim.status());
              }
              return null;
            });
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(claimedCount.get()).isEqualTo(1);
    assertThat(countRuns(jdbc, key)).isEqualTo(1);
    assertThat(countQuota(jdbc, key)).isEqualTo(1);
    assertThat(observed).hasSize(callers);

    String winner = jdbc.queryForObject("SELECT run_id FROM fake_run WHERE k=?", String.class, key);
    assertThat(observed.values()).containsOnly(winner);
  }

  /** A reused key with a different body is a conflict; it must never silently return run one. */
  @Test
  void sameKeyWithDifferentRequestBodyConflicts() {
    String key = "conflict-" + UUID.randomUUID();
    String actor = "actor-" + UUID.randomUUID();
    RunIdempotencyService svc = service();

    var first = svc.claim(actor, key, FINGERPRINT_A);
    assertThat(first.status()).isEqualTo(RunIdempotencyStatus.CLAIMED);
    svc.complete(first, "run-original");

    var conflicting = svc.claim(actor, key, FINGERPRINT_B);
    assertThat(conflicting.status()).isEqualTo(RunIdempotencyStatus.CONFLICT);
    assertThat(conflicting.runId()).isNull();

    // The original is still resolvable by the original body.
    assertThat(svc.claim(actor, key, FINGERPRINT_A).runId()).isEqualTo("run-original");
  }

  /** A claim conflicts even while the winner is still in flight — no silent first-run return. */
  @Test
  void differentBodyConflictsWhileWinnerStillInFlight() {
    String key = "conflict-inflight-" + UUID.randomUUID();
    String actor = "actor-" + UUID.randomUUID();
    RunIdempotencyService svc = service();

    assertThat(svc.claim(actor, key, FINGERPRINT_A).status())
        .isEqualTo(RunIdempotencyStatus.CLAIMED);
    assertThat(svc.claim(actor, key, FINGERPRINT_B).status())
        .isEqualTo(RunIdempotencyStatus.CONFLICT);
  }

  /** A winner that dies after CLAIMED must not deadlock the key forever. */
  @Test
  void deadWinnerClaimIsRecoverableAfterLeaseExpiry() {
    String key = "dead-winner-" + UUID.randomUUID();
    String actor = "actor-" + UUID.randomUUID();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource());

    // Lease of 1ms: the claimant is dead by the time the retry arrives.
    RunIdempotencyService dying = service(1L, 200L);
    var abandoned = dying.claim(actor, key, FINGERPRINT_A);
    assertThat(abandoned.status()).isEqualTo(RunIdempotencyStatus.CLAIMED);

    RunIdempotencyService retry = service();
    var recovered = retry.claim(actor, key, FINGERPRINT_A);
    assertThat(recovered.status()).isEqualTo(RunIdempotencyStatus.CLAIMED);
    // Fencing advanced, so the dead claimant is superseded.
    assertThat(recovered.fencingToken()).isGreaterThan(abandoned.fencingToken());

    // The superseded claimant cannot publish over the JVM that legitimately took over.
    dying.complete(abandoned, "run-from-dead-jvm");
    assertThat(state(jdbc, actor, key)).isEqualTo("CLAIMED");

    retry.complete(recovered, "run-from-live-jvm");
    assertThat(
            jdbc.queryForObject(
                "SELECT run_id FROM run_submission_idempotency WHERE actor_id=? AND"
                    + " idempotency_key=?",
                String.class,
                actor,
                key))
        .isEqualTo("run-from-live-jvm");
  }

  /** A retryable failure releases the key so the client's next attempt proceeds. */
  @Test
  void retryableFailureReleasesTheKey() {
    String key = "retryable-" + UUID.randomUUID();
    String actor = "actor-" + UUID.randomUUID();
    RunIdempotencyService svc = service();

    var first = svc.claim(actor, key, FINGERPRINT_A);
    svc.releaseForRetry(first, "upstream 503");

    var second = svc.claim(actor, key, FINGERPRINT_A);
    assertThat(second.status()).isEqualTo(RunIdempotencyStatus.CLAIMED);
  }

  /** A deterministic client rejection is not stealable; retrying the same body cannot storm. */
  @Test
  void finalFailureIsNotStealable() {
    String key = "final-" + UUID.randomUUID();
    String actor = "actor-" + UUID.randomUUID();
    RunIdempotencyService svc = service();

    var first = svc.claim(actor, key, FINGERPRINT_A);
    svc.failFinal(first, "402 managed AI credits exhausted");

    var second = svc.claim(actor, key, FINGERPRINT_A);
    assertThat(second.status()).isEqualTo(RunIdempotencyStatus.FAILED_FINAL);
  }

  /** Keys are actor-scoped: one user's key can never resolve to another user's run. */
  @Test
  void keysAreScopedByActor() {
    String key = "shared-" + UUID.randomUUID();
    RunIdempotencyService svc = service();

    var a = svc.claim("actor-a", key, FINGERPRINT_A);
    svc.complete(a, "run-a");
    var b = svc.claim("actor-b", key, FINGERPRINT_A);

    assertThat(b.status()).isEqualTo(RunIdempotencyStatus.CLAIMED);
    assertThat(b.runId()).isNull();
  }

  /**
   * Characterises the DEFECT this change removes. The retired algorithm read first and wrote
   * second, so parallel callers all missed and all created a run.
   */
  @Test
  void legacyLookupThenRecordAdmitsDuplicatesUnderTheSameRace() throws Exception {
    String key = "legacy-" + UUID.randomUUID();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource());
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS legacy_idem (k VARCHAR(200) PRIMARY KEY, run_id VARCHAR(64))");

    int callers = 16;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<JdbcTemplate> pools =
        List.of(new JdbcTemplate(dataSource()), new JdbcTemplate(dataSource()));

    try (var pool = Executors.newFixedThreadPool(callers)) {
      for (int i = 0; i < callers; i++) {
        int index = i;
        pool.submit(
            () -> {
              ready.countDown();
              if (!start.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start barrier timed out");
              }
              JdbcTemplate db = pools.get(index % pools.size());
              // Step 1: lookup (separate operation).
              List<String> prior =
                  db.queryForList("SELECT run_id FROM legacy_idem WHERE k=?", String.class, key);
              if (!prior.isEmpty()) return null;
              // Step 2: create + record (separate operation) — the unprotected window.
              String runId = "legacy-run-" + UUID.randomUUID();
              db.update("INSERT INTO fake_run (run_id,k) VALUES (?,?)", runId, key);
              db.update(
                  "INSERT INTO legacy_idem (k,run_id) VALUES (?,?) ON CONFLICT (k) DO NOTHING",
                  key,
                  runId);
              return null;
            });
      }
      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(90, TimeUnit.SECONDS)).isTrue();
    }

    // The retired design cannot promise one run. This asserts the bug is real, not theoretical.
    assertThat(countRuns(jdbc, key)).isGreaterThan(1);
  }

  private static int countRuns(JdbcTemplate jdbc, String key) {
    Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM fake_run WHERE k=?", Integer.class, key);
    return n == null ? 0 : n;
  }

  private static int countQuota(JdbcTemplate jdbc, String key) {
    Integer n =
        jdbc.queryForObject("SELECT COUNT(*) FROM fake_quota WHERE k=?", Integer.class, key);
    return n == null ? 0 : n;
  }

  private static String state(JdbcTemplate jdbc, String actor, String key) {
    return jdbc.queryForObject(
        "SELECT state FROM run_submission_idempotency WHERE actor_id=? AND idempotency_key=?",
        String.class,
        actor,
        key);
  }

  private static RunIdempotencyService service() {
    return service(120_000L, 20_000L);
  }

  private static RunIdempotencyService service(long leaseMillis, long waitMillis) {
    return new RunIdempotencyService(
        new RunSubmissionClaimStore(new JdbcTemplate(dataSource())),
        leaseMillis,
        waitMillis,
        86_400_000L);
  }

  private static DataSource dataSource() {
    DriverManagerDataSource ds = new DriverManagerDataSource();
    ds.setDriverClassName("org.postgresql.Driver");
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUsername(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    return ds;
  }
}
