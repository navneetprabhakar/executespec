package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL proof that V349 safely replaces only V348's payer-equals-tenant clause. */
@Tag("integration")
@Testcontainers
class PlanGatePayerTenantMigrationIntegrationTest {
  private static final String REMOVED = "gate_billing_org IS DISTINCT FROM run_org_id";
  private static final String RETAINED_EVIDENCE =
      "(gate_evidence ->> 'billingOrgId') IS DISTINCT FROM gate_billing_org";
  private static final String RETAINED_OWNER =
      "gate_billing_owner IS DISTINCT FROM run_owner_user_id";

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("plan_gate_payer_tenant")
          .withUsername("test")
          .withPassword("test");

  @Test
  void v348UpgradeAndCleanInstallBothInstallTheSeparatedPayerContract() {
    String upgradeSchema = schema("payer_upgrade");
    prepareSchemaWithVector(upgradeSchema);
    DriverManagerDataSource upgradeDataSource = dataSource(upgradeSchema);
    JdbcTemplate upgradeJdbc = new JdbcTemplate(upgradeDataSource);
    String cleanSchema = schema("payer_clean");
    DriverManagerDataSource cleanDataSource = dataSource(cleanSchema);
    JdbcTemplate cleanJdbc = new JdbcTemplate(cleanDataSource);
    try {
      flyway(upgradeDataSource, upgradeSchema, "348").migrate();
      assertFunction(upgradeJdbc, true);

      assertThat(flyway(upgradeDataSource, upgradeSchema, "349").migrate().migrationsExecuted)
          .isEqualTo(1);
      assertFunction(upgradeJdbc, false);

      // Historical migrations use some unqualified catalog-sensitive DDL. Rehearse the clean path
      // only after dropping the upgrade schema, matching the application's one-schema deployment.
      upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      prepareSchemaWithVector(cleanSchema);
      Flyway clean = flyway(cleanDataSource, cleanSchema, "349");
      clean.migrate();
      assertThat(clean.info().current().getVersion().getVersion()).isEqualTo("349");
      assertFunction(cleanJdbc, false);
    } finally {
      upgradeJdbc.execute("DROP SCHEMA IF EXISTS " + upgradeSchema + " CASCADE");
      cleanJdbc.execute("DROP SCHEMA IF EXISTS " + cleanSchema + " CASCADE");
    }
  }

  private static void assertFunction(JdbcTemplate jdbc, boolean containsRemovedClause) {
    String definition =
        jdbc.queryForObject(
            "SELECT pg_get_functiondef(p.oid) FROM pg_proc p"
                + " JOIN pg_namespace n ON n.oid=p.pronamespace"
                + " WHERE n.nspname=current_schema()"
                + " AND p.proname='enforce_plan_gate_decision_coherence'"
                + " AND pg_get_function_identity_arguments(p.oid)=''",
            String.class);
    assertThat(definition).isNotNull().contains(RETAINED_EVIDENCE).contains(RETAINED_OWNER);
    if (containsRemovedClause) {
      assertThat(definition).contains(REMOVED);
    } else {
      assertThat(definition).doesNotContain(REMOVED);
    }
  }

  private static DriverManagerDataSource dataSource(String schema) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dataSource.setSchema(schema);
    return dataSource;
  }

  private static Flyway flyway(DataSource dataSource, String schema, String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .locations("classpath:db/migration")
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  private static void prepareSchemaWithVector(String schema) {
    DriverManagerDataSource sharedDataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate sharedJdbc = new JdbcTemplate(sharedDataSource);
    sharedJdbc.execute("CREATE SCHEMA " + schema);
    // Flyway pins its search path to the isolated schema. Install pgvector there so historical
    // migrations that use the unqualified `vector` type exercise the same one-schema contract.
    sharedJdbc.execute("CREATE EXTENSION vector WITH SCHEMA " + schema);
  }

  private static String schema(String prefix) {
    return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
  }
}
