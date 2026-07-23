package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import com.navneet.executespec.reviewjob.ReviewBackgroundJobStore;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL V298 -> V299 upgrade and two-JVM lease/fencing/cancellation rehearsal. */
@Tag("integration")
@Testcontainers
class ReviewBackgroundJobMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("review_job_migration_test")
          .withUsername("test")
          .withPassword("test");

  @Test
  void upgradeSupportsSingleWinnerRestartTakeoverFencingCancellationRetryAndIdempotency()
      throws Exception {
    String schema = "review_job_" + UUID.randomUUID().toString().replace("-", "");
    String baseUrl = POSTGRES.getJdbcUrl();
    String url =
        baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    try {
      flyway(dataSource, schema, MigrationVersion.fromVersion("298")).migrate();
      Flyway upgrade = flyway(dataSource, schema, MigrationVersion.fromVersion("299"));
      assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);

      ReviewBackgroundJobStore first = new ReviewBackgroundJobStore(jdbc);
      UUID subject = UUID.randomUUID();
      var request = request(subject, "review:idem:1");
      var queued = first.createOrGet(request);
      assertThat(queued.status()).isEqualTo(ReviewBackgroundJobStore.Status.QUEUED);
      assertThat(first.createOrGet(request).id()).isEqualTo(queued.id());
      jdbc.update(
          """
INSERT INTO code_review_scan
  (id, project_id, repo_id, repo_full_name, scanned_sha, status, trigger_source, started_at)
VALUES (?, ?, ?, 'owner/repo', ?, 'QUEUED', 'MANUAL', NOW())
""",
          subject,
          queued.projectId(),
          queued.repoId(),
          "a".repeat(40));

      try (var contenders = Executors.newFixedThreadPool(2)) {
        var calls =
            java.util.List.of(
                (java.util.concurrent.Callable<java.util.Optional<ReviewBackgroundJobStore.Lease>>)
                    () -> first.tryAcquire(queued.id(), "jvm-a", Duration.ofMinutes(5)),
                (java.util.concurrent.Callable<java.util.Optional<ReviewBackgroundJobStore.Lease>>)
                    () -> first.tryAcquire(queued.id(), "jvm-b", Duration.ofMinutes(5)));
        long winners = contenders.invokeAll(calls).stream().filter(f -> get(f).isPresent()).count();
        assertThat(winners).isEqualTo(1);
      }

      var staleRow = first.get(queued.id()).orElseThrow();
      var stale =
          new ReviewBackgroundJobStore.Lease(
              staleRow, staleRow.leaseOwner(), staleRow.leaseToken(), staleRow.leaseExpiresAt());
      assertThat(first.stageResult(stale, "{\"prepared\":true}")).isTrue();
      jdbc.update(
          "UPDATE review_background_job SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
          queued.id());

      ReviewBackgroundJobStore restarted = new ReviewBackgroundJobStore(jdbc);
      var winner =
          restarted
              .tryAcquire(queued.id(), "jvm-after-restart", Duration.ofMinutes(5))
              .orElseThrow();
      assertThat(winner.fencingToken()).isGreaterThan(stale.fencingToken());
      assertThat(winner.job().resultPayload()).contains("prepared");
      assertThat(first.updateCodeReviewStatus(stale, "LLM_RUNNING")).isFalse();
      assertThat(restarted.updateCodeReviewStatus(winner, "LLM_RUNNING")).isTrue();
      assertThat(first.complete(stale)).isFalse();
      assertThat(restarted.complete(winner)).isTrue();

      UUID cancelSubject = UUID.randomUUID();
      var cancelJob = restarted.createOrGet(request(cancelSubject, "review:idem:cancel"));
      insertCodeReview(jdbc, cancelJob, "b".repeat(40));
      var cancelLease =
          restarted
              .tryAcquire(cancelJob.id(), "jvm-before-cancel", Duration.ofMinutes(5))
              .orElseThrow();
      assertThat(restarted.stageResult(cancelLease, "{\"prepared\":\"reuse-me\"}")).isTrue();
      assertThat(
              restarted
                  .requestCancellation(ReviewBackgroundJobStore.Type.CODE_REVIEW, cancelSubject)
                  .orElseThrow()
                  .status())
          .isEqualTo(ReviewBackgroundJobStore.Status.CANCEL_REQUESTED);
      assertThat(restarted.finishCancellation(cancelLease)).isTrue();
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM code_review_scan WHERE id=?", String.class, cancelSubject))
          .isEqualTo("CANCELLED");
      assertThat(
              restarted
                  .retry(ReviewBackgroundJobStore.Type.CODE_REVIEW, cancelSubject)
                  .orElseThrow()
                  .status())
          .isEqualTo(ReviewBackgroundJobStore.Status.QUEUED);
      assertThat(
              restarted
                  .findBySubject(ReviewBackgroundJobStore.Type.CODE_REVIEW, cancelSubject)
                  .orElseThrow()
                  .resultPayload())
          .contains("reuse-me");
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM code_review_scan WHERE id=?", String.class, cancelSubject))
          .isEqualTo("QUEUED");

      UUID failedSubject = UUID.randomUUID();
      var failedJob = restarted.createOrGet(request(failedSubject, "review:idem:failed", 1));
      insertCodeReview(jdbc, failedJob, "c".repeat(40));
      var failedLease =
          restarted
              .tryAcquire(failedJob.id(), "jvm-final-failure", Duration.ofMinutes(5))
              .orElseThrow();
      assertThat(
              restarted.failOrRequeue(
                  failedLease, "PROVIDER_ERROR", "provider failed", Duration.ZERO))
          .isEqualTo(ReviewBackgroundJobStore.Status.FAILED);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM code_review_scan WHERE id=?", String.class, failedSubject))
          .isEqualTo("FAILED");

      UUID abandonedSubject = UUID.randomUUID();
      var abandonedJob = restarted.createOrGet(request(abandonedSubject, "review:idem:abandoned"));
      insertCodeReview(jdbc, abandonedJob, "d".repeat(40));
      restarted.tryAcquire(abandonedJob.id(), "dead-jvm", Duration.ofMinutes(5)).orElseThrow();
      restarted.requestCancellation(ReviewBackgroundJobStore.Type.CODE_REVIEW, abandonedSubject);
      jdbc.update(
          "UPDATE review_background_job SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?",
          abandonedJob.id());
      restarted.finishAbandonedCancellations();
      assertThat(restarted.get(abandonedJob.id()).orElseThrow().status())
          .isEqualTo(ReviewBackgroundJobStore.Status.CANCELLED);
      assertThat(
              jdbc.queryForObject(
                  "SELECT status FROM code_review_scan WHERE id=?", String.class, abandonedSubject))
          .isEqualTo("CANCELLED");
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  private static ReviewBackgroundJobStore.Job request(UUID subjectId, String idempotencyKey) {
    return request(subjectId, idempotencyKey, 3);
  }

  private static ReviewBackgroundJobStore.Job request(
      UUID subjectId, String idempotencyKey, int maxAttempts) {
    return new ReviewBackgroundJobStore.Job(
        UUID.randomUUID(),
        ReviewBackgroundJobStore.Type.CODE_REVIEW,
        subjectId,
        "project-review",
        "org-review",
        UUID.randomUUID(),
        "user-review",
        idempotencyKey,
        "DEEPSEEK",
        "deepseek-v4-pro-native",
        "llmctx_review",
        "CUSTOMER",
        "org-review",
        "{\"repoFullName\":\"owner/repo\",\"sha\":\"abc123\"}",
        null,
        null,
        ReviewBackgroundJobStore.Status.QUEUED,
        0,
        maxAttempts,
        Instant.now(),
        null,
        0,
        null,
        null,
        null,
        null,
        null,
        Instant.now(),
        null,
        null);
  }

  private static void insertCodeReview(
      JdbcTemplate jdbc, ReviewBackgroundJobStore.Job job, String sha) {
    jdbc.update(
        """
INSERT INTO code_review_scan
  (id, project_id, repo_id, repo_full_name, scanned_sha, status, trigger_source, started_at)
VALUES (?, ?, ?, 'owner/repo', ?, 'QUEUED', 'MANUAL', NOW())
""",
        job.subjectId(),
        job.projectId(),
        job.repoId(),
        sha);
  }

  private static <T> T get(java.util.concurrent.Future<T> future) {
    try {
      return future.get();
    } catch (Exception failure) {
      throw new AssertionError(failure);
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
