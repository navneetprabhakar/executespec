package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.navneet.executespec.sandbox.IllegalSandboxTransitionException;
import com.navneet.executespec.sandbox.RunSandbox;
import com.navneet.executespec.sandbox.RunSandboxStore;
import com.navneet.executespec.sandbox.SandboxIsolationProfile;
import com.navneet.executespec.sandbox.SandboxLeaseFencedException;
import com.navneet.executespec.sandbox.SandboxNetworkPolicy;
import com.navneet.executespec.sandbox.SandboxRetentionState;
import com.navneet.executespec.sandbox.SandboxSecretPolicy;
import com.navneet.executespec.sandbox.SandboxState;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL V306 -> V307 upgrade plus the lifecycle guarantees that only a database can
 * demonstrate: single-winner acquisition across two JVM identities, stale-fence rejection,
 * illegal-transition rejection, cancellation and reconciliation without runtime dispatch, and
 * artifact retention surviving teardown.
 */
@Tag("integration")
@Testcontainers
class RunSandboxLifecycleMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("run_sandbox_migration_test")
          .withUsername("test")
          .withPassword("test");

  private static final Duration LEASE = Duration.ofMinutes(5);

  @Test
  void upgradeSupportsSingleWinnerFencingIllegalTransitionsCancellationAndReconciliation()
      throws Exception {
    String schema = "run_sandbox_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    flyway(dataSource, schema, MigrationVersion.fromVersion("306")).migrate();
    Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("307"));
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

    // The three Phase 5 flags exist as separate rows and are independently OFF. Matched by exact
    // key: an unrelated pre-existing `sandbox.network-isolation` flag also lives in this table.
    assertThat(
            jdbc.queryForList(
                """
                SELECT flag_key, flag_value FROM feature_flag
                WHERE flag_key IN ('sandbox.lifecycle.enabled',
                                   'sandbox.runtime.dispatch.enabled',
                                   'sandbox.runtime.container-strict-v1.enabled')
                ORDER BY flag_key
                """))
        .hasSize(3)
        .allSatisfy(row -> assertThat(row.get("flag_value")).isEqualTo("false"));

    RunSandboxStore alpha = new RunSandboxStore(jdbc);
    RunSandboxStore beta = new RunSandboxStore(jdbc);

    // ---- creation is idempotent
    UUID id = UUID.randomUUID();
    var request = create(id, "run-1", "sandbox:idem:1");
    RunSandbox created = alpha.createOrGet(request);
    assertThat(created.state()).isEqualTo(SandboxState.REQUESTED);
    assertThat(created.leaseToken()).isZero();
    assertThat(alpha.createOrGet(request).id()).isEqualTo(id);

    // A second non-terminal sandbox for the same run is refused by the database, not by a
    // read-then-write race in application code.
    assertThatThrownBy(
            () -> alpha.createOrGet(create(UUID.randomUUID(), "run-1", "sandbox:idem:2")))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

    // ---- two JVMs race for ownership: exactly one wins
    var pool = Executors.newFixedThreadPool(2);
    List<Future<Optional<RunSandbox.Lease>>> raced;
    try {
      List<Callable<Optional<RunSandbox.Lease>>> attempts =
          List.of(
              () -> alpha.tryAcquire(id, "jvm-alpha", LEASE),
              () -> beta.tryAcquire(id, "jvm-beta", LEASE));
      raced = pool.invokeAll(attempts);
    } finally {
      pool.shutdownNow();
    }
    List<RunSandbox.Lease> winners =
        List.of(raced.get(0), raced.get(1)).stream()
            .map(RunSandboxLifecycleMigrationIntegrationTest::join)
            .flatMap(Optional::stream)
            .toList();
    assertThat(winners).as("exactly one JVM may own a sandbox").hasSize(1);

    RunSandbox.Lease live = winners.getFirst();
    assertThat(live.fencingToken()).isEqualTo(1L);
    assertThat(alpha.get(id).orElseThrow().state()).isEqualTo(SandboxState.PROVISIONING);
    assertThat(alpha.get(id).orElseThrow().runtimeEpoch()).isEqualTo(1L);

    // ---- an illegal transition is rejected at the store
    RunSandbox.Lease finalLive = live;
    assertThatThrownBy(() -> alpha.finishRelease(finalLive, SandboxState.TERMINATED))
        .as("PROVISIONING -> TERMINATED skips teardown and must be refused")
        .isInstanceOf(IllegalSandboxTransitionException.class);
    assertThat(alpha.get(id).orElseThrow().state()).isEqualTo(SandboxState.PROVISIONING);

    // ---- a stale fence is rejected even when the transition itself is legal
    RunSandbox.Lease stale = new RunSandbox.Lease(id, live.owner(), live.fencingToken() - 1, null);
    assertThatThrownBy(() -> alpha.markReady(stale, "container-x", "sha256:deadbeef"))
        .isInstanceOf(SandboxLeaseFencedException.class);
    assertThat(alpha.heartbeat(stale, LEASE)).isFalse();
    assertThat(alpha.holdsLiveLease(stale)).isFalse();
    assertThat(alpha.holdsLiveLease(live)).isTrue();
    assertThat(alpha.get(id).orElseThrow().state()).isEqualTo(SandboxState.PROVISIONING);

    // ---- the live fence works
    RunSandbox ready = alpha.markReady(live, "container-x", "sha256:deadbeef");
    assertThat(ready.state()).isEqualTo(SandboxState.READY);
    assertThat(ready.runtimeHandle()).isEqualTo("container-x");
    assertThat(alpha.heartbeat(live, LEASE)).isTrue();

    // ---- takeover of an expired lease increments the fence and evicts the old owner
    expireLease(jdbc, id);
    RunSandbox.Lease takeover = beta.tryAcquire(id, "jvm-beta", LEASE).orElseThrow();
    assertThat(takeover.fencingToken()).isEqualTo(live.fencingToken() + 1);
    RunSandbox.Lease evicted = live;
    assertThatThrownBy(() -> alpha.beginRelease(evicted))
        .as("the superseded owner must not be able to act")
        .isInstanceOf(SandboxLeaseFencedException.class);
    // Takeover does not restart the sandbox: the epoch only advances on a fresh provisioning
    // attempt, so a lease renewal is distinguishable from a retry.
    assertThat(beta.get(id).orElseThrow().runtimeEpoch()).isEqualTo(1L);

    // ---- cancellation needs no lease and no dispatchable runtime
    assertThat(beta.requestCancellation(id).orElseThrow().state())
        .isEqualTo(SandboxState.CANCEL_REQUESTED);
    assertThat(beta.cancellationRequested(takeover)).isTrue();

    // ---- teardown then terminal, preserving audit evidence
    assertThat(beta.markRetention(id, SandboxRetentionState.RETAINED, "gs://evidence/sandbox-1"))
        .isTrue();
    assertThat(beta.beginRelease(takeover).state()).isEqualTo(SandboxState.RELEASING);
    RunSandbox cancelled = beta.finishRelease(takeover, SandboxState.CANCELLED);
    assertThat(cancelled.state()).isEqualTo(SandboxState.CANCELLED);
    assertThat(cancelled.runtimeOwner()).isNull();
    assertThat(cancelled.leaseExpiresAt()).isNull();
    assertThat(cancelled.terminalAt()).isNotNull();
    assertThat(cancelled.artifactUri()).isEqualTo("gs://evidence/sandbox-1");
    assertThat(cancelled.imageDigest()).isEqualTo("sha256:deadbeef");

    // ---- a terminal sandbox cannot be re-acquired or re-cancelled
    assertThat(beta.tryAcquire(id, "jvm-gamma", LEASE)).isEmpty();
    assertThat(beta.requestCancellation(id).orElseThrow().state())
        .isEqualTo(SandboxState.CANCELLED);
    assertThat(beta.orphanCandidates(10)).isEmpty();

    // ---- orphan reconciliation settles a sandbox whose JVM is gone
    UUID orphan = UUID.randomUUID();
    alpha.createOrGet(create(orphan, "run-2", "sandbox:idem:orphan"));
    alpha.tryAcquire(orphan, "jvm-dead", LEASE).orElseThrow();
    expireLease(jdbc, orphan);
    assertThat(alpha.orphanCandidates(10)).extracting(RunSandbox::id).containsExactly(orphan);
    RunSandbox settled =
        alpha.forceTerminal(orphan, SandboxState.FAILED, "ORPHANED_RUNTIME").orElseThrow();
    assertThat(settled.state()).isEqualTo(SandboxState.FAILED);
    assertThat(settled.failureClass()).isEqualTo("ORPHANED_RUNTIME");
    assertThat(settled.runtimeOwner()).isNull();

    // ---- reconciliation cannot steal a sandbox from a live owner
    UUID busy = UUID.randomUUID();
    alpha.createOrGet(create(busy, "run-3", "sandbox:idem:busy"));
    var busyLease = alpha.tryAcquire(busy, "jvm-live", LEASE).orElseThrow();
    assertThat(alpha.forceTerminal(busy, SandboxState.FAILED, "ORPHANED_RUNTIME")).isEmpty();
    assertThat(alpha.get(busy).orElseThrow().state()).isEqualTo(SandboxState.PROVISIONING);
    assertThat(alpha.holdsLiveLease(busyLease)).isTrue();

    // ---- expiry: a never-claimed sandbox past its TTL is settled without any runtime
    UUID expired = UUID.randomUUID();
    alpha.createOrGet(
        new RunSandbox.CreateRequest(
            expired,
            "run-4",
            "project-1",
            null,
            "user-1",
            "org-1",
            "abc1234",
            "[{\"repo\":\"owner/repo\"}]",
            SandboxIsolationProfile.NOT_ENABLED,
            "cfg-digest-1",
            SandboxNetworkPolicy.DENY_ALL,
            SandboxSecretPolicy.NO_HOST_SECRETS,
            Instant.now().minusSeconds(60),
            "sandbox:idem:expired"));
    assertThat(alpha.expiryCandidates(10)).extracting(RunSandbox::id).contains(expired);
    assertThat(alpha.forceTerminal(expired, SandboxState.EXPIRED, null).orElseThrow().state())
        .isEqualTo(SandboxState.EXPIRED);

    // ---- the database refuses a FAILED row with no classification
    assertThatThrownBy(
            () -> jdbc.update("UPDATE run_sandbox SET failure_class=NULL WHERE id=?", settled.id()))
        .as("an unexplained failure must not be persistable")
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  private static Optional<RunSandbox.Lease> join(Future<Optional<RunSandbox.Lease>> future) {
    try {
      return future.get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (Exception failure) {
      // A losing racer may surface a serialization conflict rather than an empty result; both are
      // "did not win", never "both won".
      return Optional.empty();
    }
  }

  private static void expireLease(JdbcTemplate jdbc, UUID id) {
    jdbc.update(
        "UPDATE run_sandbox SET lease_expires_at = NOW() - INTERVAL '1 minute' WHERE id=?", id);
  }

  private static RunSandbox.CreateRequest create(UUID id, String runId, String idempotencyKey) {
    return new RunSandbox.CreateRequest(
        id,
        runId,
        "project-1",
        null,
        "user-1",
        "org-1",
        "abc1234",
        "[{\"repo\":\"owner/repo\"}]",
        SandboxIsolationProfile.CONTAINER_STRICT_V1,
        "cfg-digest-1",
        SandboxNetworkPolicy.DENY_ALL,
        SandboxSecretPolicy.NO_HOST_SECRETS,
        Instant.now().plusSeconds(3600),
        idempotencyKey);
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
    if (target != null) {
      config.target(target);
    }
    return config.load();
  }
}
