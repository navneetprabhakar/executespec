package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.codeintel.repository.ProjectGeneratedDocRepository;
import com.navneet.executespec.integration.PostgresIntegrationBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** PostgreSQL proof that document persistence precedes the durable job's result foreign key. */
class ProjectGeneratedDocPublicationPostgresIntegrationTest extends PostgresIntegrationBase {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectGeneratedDocRepository documents;
  @Autowired private ProjectGeneratedDocJobStore jobs;
  @Autowired private PlatformTransactionManager transactionManager;

  private String userId;
  private String orgId;
  private String projectId;

  @BeforeEach
  void setUpRows() {
    String suffix = UUID.randomUUID().toString();
    userId = "doc-user-" + suffix;
    orgId = "doc-org-" + suffix;
    projectId = "doc-project-" + suffix;
    jdbc.update(
        """
        INSERT INTO app_user
          (id, email, first_name, last_name, password_hash, global_role, tier, status)
        VALUES (?, ?, 'Doc', 'Tester', 'not-used', 'USER', 'ENTERPRISE', 'ACTIVE')
        """,
        userId,
        suffix + "@example.test");
    jdbc.update(
        """
        INSERT INTO org (id, name, domain_patterns, tier, status, created_by_user_id)
        VALUES (?, 'Doc publication test', 'example.test', 'ENTERPRISE', 'ACTIVE', ?)
        """,
        orgId,
        userId);
    jdbc.update("UPDATE app_user SET org_id=? WHERE id=?", orgId, userId);
    jdbc.update(
        """
        INSERT INTO project (id, org_id, name, status, created_by)
        VALUES (?, ?, 'Doc publication test', 'ACTIVE', ?)
        """,
        projectId,
        orgId,
        userId);
  }

  @AfterEach
  void cleanUpRows() {
    jdbc.update("DELETE FROM project WHERE id=?", projectId);
    jdbc.update("DELETE FROM org WHERE id=?", orgId);
    jdbc.update("DELETE FROM app_user WHERE id=?", userId);
  }

  @Test
  void flushesDocumentBeforeBindingDurableJobResultInSameTransaction() {
    UUID jobId = UUID.randomUUID();
    jobs.createOrGet(
        jobId,
        projectId,
        orgId,
        userId,
        DocType.EXPLANATION.name(),
        "ANTHROPIC",
        "claude-test",
        Duration.ofMinutes(30));
    ProjectGeneratedDocJobStore.Lease lease =
        jobs.tryAcquire(jobId, "doc-publication-test", Duration.ofMinutes(5)).orElseThrow();

    String fingerprint = "{\"owner/repo\":\"abc123\"}";
    ProjectArchitectureService architecture = mock(ProjectArchitectureService.class);
    when(architecture.latest(projectId))
        .thenReturn(
            Optional.of(
                ProjectArchitectureDoc.builder()
                    .projectId(projectId)
                    .version(7)
                    .generatedFromShas(fingerprint)
                    .content("# Architecture")
                    .build()));
    ProjectGeneratedDocPublisher publisher =
        new ProjectGeneratedDocPublisher(
            architecture,
            documents,
            mock(VersionRetentionService.class),
            projects,
            transactionManager);
    ProjectGeneratedDoc candidate =
        ProjectGeneratedDoc.builder()
            .projectId(projectId)
            .docType(DocType.EXPLANATION)
            .content("# Complete generated explanation")
            .generatedBy(userId)
            .modelUsed("claude-test")
            .sourceArchVersion(7)
            .sourceShasFingerprint(fingerprint)
            .build();

    ProjectGeneratedDoc published =
        publisher.publish(
            candidate,
            ignored -> true,
            saved -> {
              if (!jobs.complete(lease, saved.getId(), saved.getVersion())) {
                throw new IllegalStateException("durable document publication fence was lost");
              }
            });

    assertThat(published.getId()).isNotNull();
    assertThat(documents.findById(published.getId())).isPresent();
    ProjectGeneratedDocJobStore.Job completed = jobs.get(jobId).orElseThrow();
    assertThat(completed.status()).isEqualTo(ProjectGeneratedDocJobStore.Status.SUCCEEDED);
    assertThat(completed.resultDocId()).isEqualTo(published.getId());
    assertThat(completed.resultDocVersion()).isEqualTo(published.getVersion());
  }

  @Test
  void confirmedRetryPreservesLifetimeAttemptsAndUsesANewLeaseBoundAttemptContext() {
    UUID jobId = UUID.randomUUID();
    ProjectGeneratedDocJobStore.Job created =
        jobs.createOrGet(
            jobId,
            projectId,
            orgId,
            userId,
            DocType.SETUP.name(),
            "DEEPSEEK",
            "deepseek-v4-pro-native",
            Duration.ofMinutes(30));

    ProjectGeneratedDocJobStore.Lease firstLease =
        jobs.tryAcquire(jobId, "doc-worker-one", Duration.ofMinutes(5)).orElseThrow();
    assertThat(firstLease.job().id()).isEqualTo(created.id());
    assertThat(firstLease.job().attemptCount()).isEqualTo(1);
    assertThat(firstLease.token()).isEqualTo(1);
    assertThat(jobs.markProviderStarted(firstLease)).isTrue();
    assertThat(
            jobs.needsConfirmation(
                firstLease,
                "PROVIDER_OUTPUT_INCOMPLETE",
                "No document was published after provider dispatch began; delivery outcome may be"
                    + " uncertain."))
        .isTrue();

    assertThat(
            jobs.confirmRetry(
                jobId,
                projectId,
                DocType.SETUP.name(),
                "different-requester",
                Duration.ofMinutes(30)))
        .isFalse();

    assertThat(
            jobs.confirmRetry(
                jobId, projectId, DocType.SETUP.name(), userId, Duration.ofMinutes(30)))
        .isTrue();

    ProjectGeneratedDocJobStore.Job requeued = jobs.get(jobId).orElseThrow();
    assertThat(requeued.id()).isEqualTo(created.id());
    assertThat(requeued.status()).isEqualTo(ProjectGeneratedDocJobStore.Status.QUEUED);
    assertThat(requeued.attemptCount()).isEqualTo(1);
    assertThat(requeued.leaseToken()).isEqualTo(firstLease.token());
    assertThat(requeued.providerStartedAt()).isNull();
    assertThat(requeued.startedAt()).isNull();
    assertThat(requeued.completedAt()).isNull();
    assertThat(requeued.errorCode()).isNull();
    assertThat(requeued.requestedProvider()).isEqualTo("DEEPSEEK");
    assertThat(requeued.requestedModel()).isEqualTo("deepseek-v4-pro-native");
    assertThat(jobs.markProviderStarted(firstLease)).isFalse();
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM project_generated_doc_job_retry_confirmation
                WHERE job_id=? AND project_id=? AND confirmed_by_user_id=?
                """,
                Integer.class,
                jobId,
                projectId,
                userId))
        .isEqualTo(1);

    // A separate store instance represents the later worker/JVM claiming the confirmed replay.
    ProjectGeneratedDocJobStore laterWorkerStore = new ProjectGeneratedDocJobStore(jdbc);
    ProjectGeneratedDocJobStore.Lease secondLease =
        laterWorkerStore.tryAcquire(jobId, "doc-worker-two", Duration.ofMinutes(5)).orElseThrow();
    assertThat(secondLease.job().id()).isEqualTo(firstLease.job().id());
    assertThat(secondLease.job().attemptCount()).isEqualTo(2);
    assertThat(secondLease.token()).isEqualTo(firstLease.token() + 1);
    assertThat(secondLease.job().requestedProvider())
        .isEqualTo(firstLease.job().requestedProvider());
    assertThat(secondLease.job().requestedModel()).isEqualTo(firstLease.job().requestedModel());

    String firstAttemptContext =
        ProjectDocExecutionPolicy.durableContextId(jobId + ":" + firstLease.token());
    String secondAttemptContext =
        ProjectDocExecutionPolicy.durableContextId(jobId + ":" + secondLease.token());
    assertThat(firstAttemptContext).isEqualTo("project-doc:" + jobId + ":1");
    assertThat(secondAttemptContext).isEqualTo("project-doc:" + jobId + ":2");
    assertThat(secondAttemptContext).isNotEqualTo(firstAttemptContext);
  }

  @Test
  void expiredPostDispatchLeasePersistsTruthfulUncertainDeliveryMessage() {
    UUID jobId = UUID.randomUUID();
    jobs.createOrGet(
        jobId,
        projectId,
        orgId,
        userId,
        DocType.EXPLANATION.name(),
        "DEEPSEEK",
        "deepseek-v4-pro-native",
        Duration.ofMinutes(30));
    ProjectGeneratedDocJobStore.Lease lease =
        jobs.tryAcquire(jobId, "expired-doc-worker", Duration.ofMinutes(5)).orElseThrow();
    assertThat(jobs.markProviderStarted(lease)).isTrue();
    jdbc.update(
        "UPDATE project_generated_doc_job SET lease_expires_at=clock_timestamp() - INTERVAL '1"
            + " second' WHERE id=?",
        jobId);

    assertThat(jobs.sweepExpired()).isEqualTo(1);

    ProjectGeneratedDocJobStore.Job swept = jobs.get(jobId).orElseThrow();
    assertThat(swept.status()).isEqualTo(ProjectGeneratedDocJobStore.Status.NEEDS_CONFIRMATION);
    assertThat(swept.errorCode()).isEqualTo("OUTCOME_UNCERTAIN");
    assertThat(swept.errorMessage())
        .isEqualTo(
            "The worker stopped after provider dispatch began; delivery outcome may be uncertain."
                + " Retry requires confirmation.");
  }
}
