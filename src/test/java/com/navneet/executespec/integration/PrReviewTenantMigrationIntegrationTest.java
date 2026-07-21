package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

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

/** V280 -> V281 rehearsal for project-scoped PR-review identity and idempotency. */
@Tag("integration")
@Testcontainers
class PrReviewTenantMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("pr_review_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeBackfillsImmutableRepoIdentityAndIsolatesLegacyNullRows() {
    String schema = "pr_review_upgrade_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID repoId = UUID.randomUUID();

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("280")).migrate();
      jdbc.update(
          """
          INSERT INTO project_repo
            (id, scope, owner_org_id, github_repo_full_name, github_owner)
          VALUES (?, 'ORG', 'org-1', 'shared/public-repo', 'shared')
          """,
          repoId);
      jdbc.update(
          """
          INSERT INTO project_repo_link (id, project_id, repo_id, linked_by)
          VALUES (?, 'project-1', ?, 'user-1'), (?, 'project-2', ?, 'user-2')
          """,
          UUID.randomUUID(),
          repoId,
          UUID.randomUUID(),
          repoId);
      jdbc.update(
          """
          INSERT INTO pr_review
            (id, project_id, repo_full_name, pr_number, head_sha, status)
          VALUES (?, 'project-1', 'shared/public-repo', 42, ?, 'COMPLETE')
          """,
          UUID.randomUUID(),
          "a".repeat(40));
      // No linked repo matches this historical row, so V281 must retain it under the legacy
      // name-based partial index rather than pretending it has durable identity.
      jdbc.update(
          """
          INSERT INTO pr_review
            (id, project_id, repo_full_name, pr_number, head_sha, status)
          VALUES (?, 'legacy-project', 'orphan/repo', 7, ?, 'COMPLETE')
          """,
          UUID.randomUUID(),
          "b".repeat(40));

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("281"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT repo_id FROM pr_review WHERE project_id='project-1'", UUID.class))
          .isEqualTo(repoId);
      assertThat(
              jdbc.queryForObject(
                  "SELECT repo_id FROM pr_review WHERE project_id='legacy-project'", UUID.class))
          .isNull();

      // Rename the durable registry identity. A second row with the new display name must still
      // collide on immutable repo_id and cannot bypass idempotency.
      jdbc.update(
          "UPDATE project_repo SET github_repo_full_name='shared/renamed-repo' WHERE id=?", repoId);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
                      INSERT INTO pr_review
                        (id, project_id, repo_id, repo_full_name, pr_number, head_sha, status)
                      VALUES (?, 'project-1', ?, 'shared/renamed-repo', 42, ?, 'COMPLETE')
                      """,
                      UUID.randomUUID(),
                      repoId,
                      "a".repeat(40)))
          .isInstanceOf(DuplicateKeyException.class);

      // This insert violated the pre-V281 global repo/PR/SHA index. It must now be legal because
      // the review belongs to a different tenant project.
      jdbc.update(
          """
          INSERT INTO pr_review
            (id, project_id, repo_id, repo_full_name, pr_number, head_sha, status)
          VALUES (?, 'project-2', ?, 'shared/public-repo', 42, ?, 'COMPLETE')
          """,
          UUID.randomUUID(),
          repoId,
          "a".repeat(40));

      // Unresolved historical rows remain duplicate-protected within their original project, but
      // the old global cross-project uniqueness is gone.
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
                      INSERT INTO pr_review
                        (id, project_id, repo_full_name, pr_number, head_sha, status)
                      VALUES (?, 'legacy-project', 'orphan/repo', 7, ?, 'COMPLETE')
                      """,
                      UUID.randomUUID(),
                      "b".repeat(40)))
          .isInstanceOf(DuplicateKeyException.class);
      jdbc.update(
          """
          INSERT INTO pr_review
            (id, project_id, repo_full_name, pr_number, head_sha, status)
          VALUES (?, 'other-legacy-project', 'orphan/repo', 7, ?, 'COMPLETE')
          """,
          UUID.randomUUID(),
          "b".repeat(40));
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pr_review", Integer.class)).isEqualTo(4);
      assertThat(
              jdbc.queryForList(
                  "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename ="
                      + " 'pr_review'",
                  String.class,
                  schema))
          .contains(
              "uq_pr_review_project_repo_pr_sha", "uq_pr_review_project_repo_name_pr_sha_legacy")
          .doesNotContain("uq_pr_review_repo_pr_sha");
      assertThat(
              flyway(dataSource, schema, MigrationVersion.fromVersion("281"))
                  .migrate()
                  .migrationsExecuted)
          .isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
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
    if (target != null) config.target(target);
    return config.load();
  }
}
