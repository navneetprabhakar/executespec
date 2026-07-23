package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL V303 -&gt; V304 rehearsal for per-attempt benchmark route attribution.
 *
 * <p>The structural check is the important part: it makes a MIXED attempt carrying a single
 * provider impossible at the database level, so a later "simplification" cannot quietly collapse a
 * multi-route attempt to one misleading provider.
 */
@Tag("integration")
@Testcontainers
class BenchmarkRouteAttributionMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("benchmark_route_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void migrationAddsNullableRouteColumnsAndForbidsCollapsingAMixedAttempt() {
    String schema = "bench_route_" + UUID.randomUUID().toString().replace("-", "");
    DriverManagerDataSource dataSource = dataSource(schema);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("303")).migrate();

      // A historical row predating V304 must survive the upgrade with NULL attribution.
      jdbc.update(
          """
INSERT INTO benchmark_run
    (id,suite,dataset,provider,model,k,total_tasks,resolved_tasks,status,cost_usd,created_at)
VALUES ('bench-1','SWE_BENCH','lite','deepseek','deepseek-v4-pro-native',1,1,0,'RUNNING',0,NOW())
""");
      jdbc.update(
          """
          INSERT INTO benchmark_task_result
              (id,benchmark_run_id,task_name,attempt,reward,es_run_id,cost_usd,created_at)
          VALUES ('t-legacy','bench-1','django__django-1',1,1,'es-1',0,NOW())
          """);

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("304"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      assertThat(
              jdbc.queryForObject(
                  "SELECT route_attribution FROM benchmark_task_result WHERE id='t-legacy'",
                  String.class))
          .isNull();

      // LEDGER requires a concrete route.
      jdbc.update(insert("t-ledger", 2, "'LEDGER'", "'openrouter'", "'glm-5.2'"));
      // MIXED and UNATTRIBUTED must stay route-free.
      jdbc.update(insert("t-mixed", 3, "'MIXED'", "NULL", "NULL"));
      jdbc.update(insert("t-unattr", 4, "'UNATTRIBUTED'", "NULL", "NULL"));

      // The whole point of C1: a MIXED attempt cannot be stored as a single provider.
      assertThatThrownBy(
              () -> jdbc.update(insert("t-bad-mixed", 5, "'MIXED'", "'deepseek'", "'v4'")))
          .isInstanceOf(DataIntegrityViolationException.class);
      // Nor can a LEDGER attempt claim attribution without naming the route.
      assertThatThrownBy(() -> jdbc.update(insert("t-bad-ledger", 6, "'LEDGER'", "NULL", "NULL")))
          .isInstanceOf(DataIntegrityViolationException.class);

      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM benchmark_task_result WHERE benchmark_run_id='bench-1'",
                  Long.class))
          .isEqualTo(4L);

      // Repeatable: re-running the migration is a no-op.
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static String insert(
      String id, int attempt, String attribution, String provider, String model) {
    return """
           INSERT INTO benchmark_task_result
               (id,benchmark_run_id,task_name,attempt,reward,es_run_id,cost_usd,created_at,
                route_attribution,provider,model)
           VALUES ('%s','bench-1','django__django-1',%d,1,'es-1',0,NOW(),%s,%s,%s)
           """
        .formatted(id, attempt, attribution, provider, model);
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
