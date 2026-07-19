package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL rehearsal of the {@code modelPricingInitializer} startup race.
 *
 * <p>Two backend instances starting concurrently against a fresh database both run the seed. The
 * seed is a read-then-write over a composite primary key, so without serialization both instances
 * observe an empty table and both insert — the loser fails to boot with a duplicate-key violation.
 * That is invisible in single-instance operation and breaks scale-out, rolling deploys, and
 * blue/green onto a fresh schema.
 *
 * <p>This exercises the SQL sequence rather than the Spring bean, matching the other migration
 * rehearsals in this package: it needs no Redis, no provider keys, and no application context.
 */
@Tag("integration")
@Testcontainers
class ModelPricingConcurrentSeedIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("model_pricing_seed_test")
          .withUsername("test")
          .withPassword("test");

  private static final String LOCK =
      "SELECT pg_advisory_xact_lock(hashtext('executespec'), hashtext('model_pricing_seed'))";

  /** The unguarded read-then-insert both instances run today. */
  private static final String UNGUARDED_INSERT =
      """
      INSERT INTO model_pricing
          (provider, model, input_cost_per1m, output_cost_per1m, updated_at)
      VALUES ('anthropic', 'claude-sonnet-5', 3.0, 15.0, NOW())
      """;

  @Test
  void concurrentSeedsCollideWithoutSerializationAndSucceedWithTheAdvisoryLock() throws Exception {
    String schema = "pricing_seed_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema).migrate();
      jdbc.update("DELETE FROM model_pricing");

      // 1. The defect: interleaved seeds both see an empty table, and the loser cannot boot.
      assertThatThrownBy(() -> raceTwoSeeds(dataSource, false))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("model_pricing_pkey");

      jdbc.update("DELETE FROM model_pricing");

      // 2. The fix: the transaction-scoped advisory lock serializes the two seeds, so the second
      //    instance reads the row the first committed and upserts instead of colliding.
      assertThatCode(() -> raceTwoSeeds(dataSource, true)).doesNotThrowAnyException();
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM model_pricing WHERE model='claude-sonnet-5'", Long.class))
          .isEqualTo(1L);
    } finally {
      new JdbcTemplate(dataSource(null)).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  /**
   * Runs two seeds that are forced to overlap. Each opens its own transaction, optionally takes the
   * advisory lock, reads, then writes — the exact shape of the initializer.
   */
  private void raceTwoSeeds(DataSource dataSource, boolean guarded) throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2);
    AtomicReference<Exception> failure = new AtomicReference<>();

    Runnable seed =
        () -> {
          try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
              if (guarded) {
                st.execute(LOCK);
              }
              bothStarted.countDown();
              bothStarted.await(10, TimeUnit.SECONDS);
              st.executeUpdate(guarded ? guardedUpsert() : UNGUARDED_INSERT);
            }
            c.commit();
          } catch (Exception e) {
            failure.compareAndSet(null, e);
          }
        };

    Thread a = new Thread(seed, "seed-a");
    Thread b = new Thread(seed, "seed-b");
    a.start();
    b.start();
    a.join(30_000);
    b.join(30_000);

    if (failure.get() != null) {
      throw failure.get();
    }
  }

  /**
   * The guarded write. Idempotent on its own, so a seed cannot collide even if the lock is somehow
   * not held — the lock additionally serializes the stale-row cleanup the initializer performs.
   */
  private String guardedUpsert() {
    return UNGUARDED_INSERT
        + " ON CONFLICT (provider, model) DO UPDATE SET"
        + " input_cost_per1m = EXCLUDED.input_cost_per1m,"
        + " output_cost_per1m = EXCLUDED.output_cost_per1m,"
        + " updated_at = EXCLUDED.updated_at";
  }

  // ---------------------------------------------------------------- harness

  private Flyway flyway(DataSource dataSource, String schema) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .createSchemas(true)
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
