package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.project.rag.ChunkingService;
import com.navneet.executespec.project.rag.CodeChunkStore;
import com.navneet.executespec.project.rag.CodeChunkStore.LeaseLostException;
import com.navneet.executespec.project.rag.CodeIndexStateStore;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Completion;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Status;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V285 -> V286 lease/fence, restart, deletion, and tenant-isolation rehearsal. */
@Tag("integration")
@Testcontainers
class CodeIndexStateMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("code_index_state_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void expiredOwnerCannotPublishAndProjectCorporaStayIsolated() {
    String schema = "code_index_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID repoId = UUID.randomUUID();

    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("285")).migrate();
      jdbc.update(
          "INSERT INTO project_repo(id, scope, owner_user_id, github_repo_full_name, github_owner)"
              + " VALUES (?, 'USER', 'user-1', 'acme/large', 'acme')",
          repoId);

      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("286"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
      CodeIndexStateStore states = new CodeIndexStateStore(jdbc);
      CodeChunkStore chunks = new CodeChunkStore(jdbc);

      var abandoned =
          states
              .tryAcquire("project-a", repoId, "sha-a", "jvm-a", Duration.ofMinutes(5))
              .orElseThrow();
      assertThat(states.tryAcquire("project-a", repoId, "sha-a", "jvm-b", Duration.ofMinutes(5)))
          .isEmpty();

      // Simulate process death, then a second JVM's startup/status reconciliation and takeover.
      jdbc.update(
          "UPDATE project_code_index_state SET lease_expires_at=NOW()-INTERVAL '1 second'"
              + " WHERE project_id='project-a' AND repo_id=?",
          repoId);
      states.reconcile("project-a", repoId, "sha-a");
      assertThat(states.get("project-a", repoId).orElseThrow().status()).isEqualTo(Status.STALE);
      var resumed =
          states
              .tryAcquire("project-a", repoId, "sha-a", "jvm-b", Duration.ofMinutes(5))
              .orElseThrow();
      assertThat(resumed.fencingToken()).isGreaterThan(abandoned.fencingToken());
      assertThat(states.complete(abandoned, completion())).isFalse();

      float[] vectorA = vector(0.1f);
      assertThatThrownBy(
              () ->
                  chunks.replaceFileChunks(
                      abandoned,
                      "acme/large",
                      "src/Stale.java",
                      "stale-file",
                      List.of(new ChunkingService.Chunk(0, "must not publish", 3)),
                      List.of(vectorA)))
          .isInstanceOf(LeaseLostException.class);
      assertThat(chunks.countChunks("project-a", repoId.toString())).isZero();
      chunks.replaceFileChunks(
          resumed,
          "acme/large",
          "src/Auth.java",
          "file-a",
          List.of(new ChunkingService.Chunk(0, "project a auth", 3)),
          List.of(vectorA));
      assertThat(states.complete(resumed, completion())).isTrue();

      // The same registry repo in another project gets a distinct state + vector corpus.
      var tenantB =
          states
              .tryAcquire("project-b", repoId, "sha-a", "jvm-c", Duration.ofMinutes(5))
              .orElseThrow();
      chunks.replaceFileChunks(
          tenantB,
          "acme/large",
          "src/Auth.java",
          "file-b",
          List.of(new ChunkingService.Chunk(0, "project b auth", 3)),
          List.of(vector(0.2f)));
      assertThat(chunks.indexedFileShas("project-a", repoId.toString()))
          .containsEntry("src/Auth.java", "file-a");
      assertThat(chunks.indexedFileShas("project-b", repoId.toString()))
          .containsEntry("src/Auth.java", "file-b");

      // Deleting a removed file under B's fence cannot delete A's same-path chunks.
      assertThat(chunks.deleteFile(tenantB, "src/Auth.java")).isTrue();
      assertThat(chunks.countChunks("project-a", repoId.toString())).isEqualTo(1);
      assertThat(chunks.countChunks("project-b", repoId.toString())).isZero();
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static Completion completion() {
    return new Completion(
        Status.READY, "FULL_SOURCE_COVERAGE", 1, 1, 1, 1, 0, 0, 0, 0, 1, 100.0, false);
  }

  private static float[] vector(float value) {
    float[] vector = new float[1536];
    java.util.Arrays.fill(vector, value);
    return vector;
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
