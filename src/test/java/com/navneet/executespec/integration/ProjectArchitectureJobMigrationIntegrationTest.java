package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.navneet.executespec.codeintel.ProjectArchitectureJobService;
import com.navneet.executespec.codeintel.ProjectArchitectureJobStore;

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
 * Real PostgreSQL V352→V353 install, cross-JVM claim, lifecycle, fence and constraint rehearsal.
 */
@Tag("integration")
@Testcontainers
class ProjectArchitectureJobMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("architecture_job_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeEnforcesDurableSourceStageAndUncertainRetryStateMachine() throws Exception {
    String schema = "architecture_job_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("352")).migrate();
      seed(jdbc);
      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("353"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
      ProjectArchitectureJobStore store = new ProjectArchitectureJobStore(jdbc);

      var first = create(store, "project-map");
      var replay = create(store, "project-map");
      assertThat(replay.id()).isEqualTo(first.id());
      assertThat(first.sourceSnapshotJson()).isNull();

      // Two independent store instances represent two JVMs. SQL CAS elects exactly one owner.
      ProjectArchitectureJobStore secondJvm = new ProjectArchitectureJobStore(jdbc);
      try (var contenders = Executors.newFixedThreadPool(2)) {
        var claims =
            contenders.invokeAll(
                java.util.List.of(
                    (java.util.concurrent.Callable<
                            java.util.Optional<ProjectArchitectureJobStore.Lease>>)
                        () -> store.tryAcquire(first.id(), "jvm-a", Duration.ofMinutes(5), 2),
                    (java.util.concurrent.Callable<
                            java.util.Optional<ProjectArchitectureJobStore.Lease>>)
                        () -> secondJvm.tryAcquire(first.id(), "jvm-b", Duration.ofMinutes(5), 2)));
        assertThat(
                claims.stream()
                    .filter(
                        f -> {
                          try {
                            return f.get().isPresent();
                          } catch (Exception e) {
                            throw new AssertionError(e);
                          }
                        })
                    .count())
            .isEqualTo(1);
      }
      var running = store.findLatestActive("project-map").orElseThrow();
      var lease =
          new ProjectArchitectureJobStore.Lease(
              running, running.leaseOwner(), running.leaseToken(), running.leaseExpiresAt());
      String sourceJson =
          """
{"schemaVersion":1,"projectId":"project-map","repositories":[{"repoId":"00000000-0000-0000-0000-000000000001","scmProvider":"GITHUB","sourceKey":"acme/api","repoFullName":"acme/api","branch":"main","revision":"1111111111111111111111111111111111111111","credentialSubjectUserId":"user-map"}]}
""";
      String sourceDigest = ProjectArchitectureJobService.sha256(sourceJson);
      assertThat(store.bindPrepared(lease, sourceJson, sourceDigest, "ctx-repo", "ctx-project"))
          .isTrue();
      assertThat(store.beginExecuting(lease)).isTrue();

      // Nothing generated is visible before a fenced promotion transaction.
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_code_map", Long.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM repo_architecture_doc", Long.class))
          .isZero();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project_architecture_doc", Long.class))
          .isZero();

      jdbc.update(
          """
          UPDATE project_architecture_job
          SET created_at=clock_timestamp()-INTERVAL '2 hours',
              lease_expires_at=clock_timestamp()-INTERVAL '1 second',
              deadline_at=clock_timestamp()-INTERVAL '1 second'
          WHERE id=?
          """,
          first.id());
      assertThat(store.tryAcquire(first.id(), "recovery-jvm", Duration.ofMinutes(5), 2)).isEmpty();
      assertThat(store.sweep(2)).isEqualTo(1);
      var uncertain = store.get(first.id()).orElseThrow();
      assertThat(uncertain.status())
          .isEqualTo(ProjectArchitectureJobStore.Status.NEEDS_CONFIRMATION);
      assertThat(uncertain.errorCode()).isEqualTo("OUTCOME_UNCERTAIN");
      assertThat(create(store, "project-map").id()).isEqualTo(first.id());

      var queued = create(store, "project-cancel");
      assertThat(store.cancel(queued.id(), "project-cancel")).isTrue();
      assertThat(store.get(queued.id()).orElseThrow().status())
          .isEqualTo(ProjectArchitectureJobStore.Status.CANCELLED);

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
                      INSERT INTO project_architecture_job
                        (id, project_id, org_id, requested_by_user_id, requested_provider,
                         repo_architecture_model, project_architecture_model, status, phase,
                         completed_at)
                      VALUES (?, 'project-map','org-map','user-map','GEMINI','repo','project',
                              'SUCCEEDED','PUBLISHING',clock_timestamp())
                      """,
                      UUID.randomUUID()))
          .isInstanceOf(DataIntegrityViolationException.class);

      // PUBLISHING work that crosses both its lease and deadline remains uncertain; clearing its
      // stage during terminalization must satisfy the database constraints.
      var publishing = create(store, "project-publishing");
      var publishingLease =
          store.tryAcquire(publishing.id(), "publisher", Duration.ofMinutes(5), 2).orElseThrow();
      assertThat(
              store.bindPrepared(
                  publishingLease,
                  sourceJson.replace("project-map", "project-publishing"),
                  sourceDigest,
                  "ctx-publishing-repo",
                  "ctx-publishing-project"))
          .isTrue();
      assertThat(store.beginExecuting(publishingLease)).isTrue();
      assertThat(
              store.stage(
                  publishingLease,
                  "{}",
                  ProjectArchitectureJobService.sha256("{}"),
                  "QUALIFIED",
                  "{}"))
          .isTrue();
      assertThat(store.beginPublishing(publishingLease)).isTrue();
      jdbc.update(
          """
          UPDATE project_architecture_job
          SET created_at=clock_timestamp()-INTERVAL '2 hours',
              lease_expires_at=clock_timestamp()-INTERVAL '1 second',
              deadline_at=clock_timestamp()-INTERVAL '1 second'
          WHERE id=?
          """,
          publishing.id());
      assertThat(store.sweep(2)).isEqualTo(1);
      assertThat(store.get(publishing.id()).orElseThrow().status())
          .isEqualTo(ProjectArchitectureJobStore.Status.NEEDS_CONFIRMATION);
      assertThat(
              store.confirmRetry(
                  publishing.id(), "project-publishing", "different-writer", Duration.ofHours(2)))
          .isFalse();
      assertThat(store.get(publishing.id()).orElseThrow().status())
          .isEqualTo(ProjectArchitectureJobStore.Status.NEEDS_CONFIRMATION);
      assertThat(
              store.confirmRetry(
                  publishing.id(), "project-publishing", "user-map", Duration.ofHours(2)))
          .isTrue();
      assertThat(store.get(publishing.id()).orElseThrow().status())
          .isEqualTo(ProjectArchitectureJobStore.Status.QUEUED);
      assertThat(
              jdbc.queryForObject(
                  """
                  SELECT COUNT(*) FROM project_architecture_job_retry_confirmation
                  WHERE job_id=? AND project_id=? AND confirmed_by_user_id=?
                  """,
                  Long.class,
                  publishing.id(),
                  "project-publishing",
                  "user-map"))
          .isEqualTo(1L);
      assertThat(store.cancel(publishing.id(), "project-publishing")).isTrue();

      // PREPARING is the only reclaimable phase; a new owner receives a higher fencing token.
      var preparing = create(store, "project-reclaim");
      var stale =
          store.tryAcquire(preparing.id(), "old-jvm", Duration.ofMinutes(5), 2).orElseThrow();
      jdbc.update(
          "UPDATE project_architecture_job SET lease_expires_at=clock_timestamp()-INTERVAL '1"
              + " second' WHERE id=?",
          preparing.id());
      var reclaimed =
          store.tryAcquire(preparing.id(), "new-jvm", Duration.ofMinutes(5), 2).orElseThrow();
      assertThat(reclaimed.fencingToken()).isGreaterThan(stale.fencingToken());
      assertThat(store.renew(stale, Duration.ofMinutes(5))).isFalse();
      assertThat(store.fail(reclaimed, "TEST_DONE", "test cleanup")).isTrue();

      var exhausted = create(store, "project-exhausted");
      store.tryAcquire(exhausted.id(), "one-shot", Duration.ofMinutes(5), 1).orElseThrow();
      jdbc.update(
          "UPDATE project_architecture_job SET lease_expires_at=clock_timestamp()-INTERVAL '1"
              + " second' WHERE id=?",
          exhausted.id());
      assertThat(store.sweep(1)).isEqualTo(1);
      assertThat(store.get(exhausted.id()).orElseThrow().errorCode()).isEqualTo("RETRY_EXHAUSTED");

      var runningCancel = create(store, "project-running-cancel");
      var cancelLease =
          store
              .tryAcquire(runningCancel.id(), "cancel-owner", Duration.ofMinutes(5), 2)
              .orElseThrow();
      assertThat(store.cancel(runningCancel.id(), "project-running-cancel")).isTrue();
      assertThat(store.renew(cancelLease, Duration.ofMinutes(5))).isFalse();
      jdbc.update(
          "UPDATE project_architecture_job SET lease_expires_at=clock_timestamp()-INTERVAL '1"
              + " second' WHERE id=?",
          runningCancel.id());
      assertThat(store.sweep(2)).isEqualTo(1);
      assertThat(store.get(runningCancel.id()).orElseThrow().status())
          .isEqualTo(ProjectArchitectureJobStore.Status.CANCELLED);

      // A failure path running on another JVM/thread must observe an already-committed
      // cancellation and terminalize as CANCELLED, never FAILED or NEEDS_CONFIRMATION.
      var preparingCancelRace = create(store, "project-preparing-cancel-race");
      var preparingCancelLease =
          store
              .tryAcquire(
                  preparingCancelRace.id(), "preparing-failure-owner", Duration.ofMinutes(5), 2)
              .orElseThrow();
      ProjectArchitectureJobStore preparingFailureStore = new ProjectArchitectureJobStore(jdbc);
      try (var race = Executors.newFixedThreadPool(2)) {
        var cancellation =
            race.submit(
                () -> store.cancel(preparingCancelRace.id(), "project-preparing-cancel-race"));
        var failure =
            race.submit(
                () -> {
                  assertThat(cancellation.get()).isTrue();
                  return preparingFailureStore.failOrRequeue(
                      preparingCancelLease,
                      "SCM_FAILURE",
                      "concurrent source failure",
                      2,
                      Duration.ofSeconds(1));
                });
        assertThat(failure.get()).isEqualTo(ProjectArchitectureJobStore.Status.CANCELLED);
      }
      var preparingCancelled = store.get(preparingCancelRace.id()).orElseThrow();
      assertThat(preparingCancelled.status())
          .isEqualTo(ProjectArchitectureJobStore.Status.CANCELLED);
      assertThat(preparingCancelled.errorCode()).isEqualTo("CANCELLED");

      var executingCancelRace = create(store, "project-executing-cancel-race");
      var executingCancelLease =
          store
              .tryAcquire(
                  executingCancelRace.id(), "executing-failure-owner", Duration.ofMinutes(5), 2)
              .orElseThrow();
      assertThat(
              store.bindPrepared(
                  executingCancelLease,
                  sourceJson.replace("project-map", "project-executing-cancel-race"),
                  sourceDigest,
                  "ctx-executing-race-repo",
                  "ctx-executing-race-project"))
          .isTrue();
      assertThat(store.beginExecuting(executingCancelLease)).isTrue();
      ProjectArchitectureJobStore executingFailureStore = new ProjectArchitectureJobStore(jdbc);
      try (var race = Executors.newFixedThreadPool(2)) {
        var cancellation =
            race.submit(
                () -> store.cancel(executingCancelRace.id(), "project-executing-cancel-race"));
        var failure =
            race.submit(
                () -> {
                  assertThat(cancellation.get()).isTrue();
                  return executingFailureStore.needsConfirmation(
                      executingCancelLease, "PROVIDER_FAILURE", "concurrent provider failure");
                });
        assertThat(failure.get()).isTrue();
      }
      var executingCancelled = store.get(executingCancelRace.id()).orElseThrow();
      assertThat(executingCancelled.status())
          .isEqualTo(ProjectArchitectureJobStore.Status.CANCELLED);
      assertThat(executingCancelled.errorCode()).isEqualTo("CANCELLED");

      assertThatThrownBy(
              () ->
                  jdbc.update(
                      """
                      INSERT INTO project_architecture_job
                        (id, project_id, org_id, requested_by_user_id, requested_provider,
                         repo_architecture_model, project_architecture_model)
                      VALUES (?, 'project-tenant', 'org-other', 'user-map', 'GEMINI', 'r', 'p')
                      """,
                      UUID.randomUUID()))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "UPDATE project_architecture_job SET staged_artifacts_digest='corrupt'"
                          + " WHERE id=?",
                      publishing.id()))
          .isInstanceOf(DataIntegrityViolationException.class);

      // A retained SUCCEEDED job owns its result proof. Architecture retention cannot null the
      // proof; after terminal-job retention deletes the job, the version may be pruned.
      UUID retainedArchitecture = UUID.randomUUID();
      jdbc.update(
          """
          INSERT INTO project_architecture_doc
            (id, project_id, version, content, generated_from_shas, generated_from_branches)
          VALUES (?, 'project-retention', 1, '# retained', '{}', '{}')
          """,
          retainedArchitecture);
      UUID retainedJob = UUID.randomUUID();
      jdbc.update(
          """
          INSERT INTO project_architecture_job
            (id, project_id, org_id, requested_by_user_id, requested_provider,
             repo_architecture_model, project_architecture_model, status, phase,
             source_snapshot_json, source_snapshot_digest,
             repo_execution_context_id, project_execution_context_id,
             staged_artifacts_json, staged_artifacts_digest, quality_status,
             result_architecture_id, result_architecture_version, result_source_fingerprint,
             completed_at, promoted_at)
          VALUES (?, 'project-retention', 'org-map', 'user-map', 'GEMINI', 'r', 'p',
            'SUCCEEDED', 'PUBLISHING', '{}'::jsonb, ?, 'ctx-r', 'ctx-p',
            '{}'::jsonb, ?, 'QUALIFIED', ?, 1, ?, clock_timestamp(), clock_timestamp())
          """,
          retainedJob,
          "b".repeat(64),
          ProjectArchitectureJobService.sha256("{}"),
          retainedArchitecture,
          "b".repeat(64));
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "DELETE FROM project_architecture_doc WHERE id=?", retainedArchitecture))
          .isInstanceOf(DataIntegrityViolationException.class);
      jdbc.update("DELETE FROM project_architecture_job WHERE id=?", retainedJob);
      assertThat(
              jdbc.update("DELETE FROM project_architecture_doc WHERE id=?", retainedArchitecture))
          .isEqualTo(1);

      var columns =
          jdbc.queryForList(
              """
              SELECT column_name FROM information_schema.columns
              WHERE table_schema=? AND table_name='project_architecture_job'
              """,
              String.class,
              schema);
      assertThat(columns)
          .noneMatch(
              name -> {
                String lower = name.toLowerCase();
                return lower.contains("credential")
                    || lower.contains("secret")
                    || lower.contains("token_value")
                    || lower.equals("pat")
                    || lower.contains("api_key");
              });
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static ProjectArchitectureJobStore.Job create(
      ProjectArchitectureJobStore store, String projectId) {
    return store.createOrGet(
        UUID.randomUUID(),
        projectId,
        "org-map",
        "user-map",
        null,
        "GEMINI",
        "gemini-repo",
        "gemini-project",
        Duration.ofHours(2));
  }

  private static void seed(JdbcTemplate jdbc) {
    jdbc.update(
        "INSERT INTO org (id, name, domain_patterns, created_by_user_id) VALUES"
            + " ('org-map', 'Map Org', 'example.com', 'user-map')");
    jdbc.update(
        """
        INSERT INTO app_user
          (id, email, first_name, last_name, password_hash, status, org_id)
        VALUES
          ('user-map', 'map@example.com', 'Map', 'Owner', 'hash', 'ACTIVE', 'org-map')
        """);
    jdbc.update(
        "INSERT INTO project (id, org_id, name, created_by) VALUES"
            + " ('project-map', 'org-map', 'Map project', 'user-map')");
    for (String id :
        java.util.List.of(
            "project-cancel",
            "project-publishing",
            "project-reclaim",
            "project-exhausted",
            "project-running-cancel",
            "project-preparing-cancel-race",
            "project-executing-cancel-race",
            "project-tenant",
            "project-retention")) {
      jdbc.update(
          "INSERT INTO project (id, org_id, name, created_by) VALUES (?, 'org-map', ?, 'user-map')",
          id,
          id);
    }
    jdbc.update(
        "INSERT INTO org (id, name, domain_patterns, created_by_user_id) VALUES"
            + " ('org-other', 'Other Org', 'other.example', 'user-map')");
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
