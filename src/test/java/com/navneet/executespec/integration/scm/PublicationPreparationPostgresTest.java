package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof for V390 preparation, artifact, publication, and recovery fences. */
@Tag("integration")
@Testcontainers
class PublicationPreparationPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("publication_preparation")
          .withUsername("test")
          .withPassword("test");

  @Test
  void exactPreparedCommitIsImmutableAndRemoteOutcomeCannotBeGuessed() throws Exception {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate bootstrapJdbc = new JdbcTemplate(dataSource);
    bootstrapJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    JdbcTemplate jdbc =
        new JdbcTemplate(new SingleConnectionDataSource(dataSource.getConnection(), true));
    PublicationPreparationStore store = new PublicationPreparationStore(jdbc);

    String runId = "publication-preparation-run";
    String accepted = "a".repeat(64);
    String checkpoint = "b".repeat(64);
    UUID attemptId = UUID.randomUUID();
    Instant checkpointAt = Instant.now().minusSeconds(30);
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'COMPLETE','BUILD','SHARED_KERNEL_V2')",
        runId);
    jdbc.execute("SET session_replication_role = replica");
    try {
      jdbc.update(
          """
          INSERT INTO workspace_generation_attempt
            (id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
             before_generation,candidate_generation,repositories_json,
             ignored_artifact_baseline_json,ignored_artifact_baseline_digest,authority_digest,
             task_contract_digest,workspace_lease_token,llm_operation_id,llm_attempt_number,
             llm_lease_token,before_checkpoint_kind,
             before_checkpoint_key,before_checkpoint_digest,before_checkpoint_completed_at,
             checkpoint_kind,checkpoint_key,checkpoint_generation,checkpoint_digest,
             checkpoint_completed_at,resolved_at,build_protocol_version,
             verification_command_protocol_version)
          VALUES (?,?,'T1',0,1,'BUILD_TASK','ACCEPTED','/tmp/publication',repeat('0',64),?,
             '[]'::jsonb,'{}'::jsonb,repeat('1',64),repeat('2',64),repeat('3',64),?,?,1,?,
             'GCS','gs://bucket/before',repeat('4',64),NOW()-INTERVAL '1 minute',
             'GCS','gs://bucket/accepted',?,?,?,NOW(),'SHARED_KERNEL_V2',2)
          """,
          attemptId,
          runId,
          accepted,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          accepted,
          checkpoint,
          Timestamp.from(checkpointAt));
      jdbc.update(
          """
          UPDATE pipeline_run
             SET accepted_workspace_generation=?, accepted_task_index=0,
                 accepted_generation_attempt_id=?, accepted_checkpoint_kind='GCS',
                 accepted_checkpoint_key='gs://bucket/accepted', accepted_checkpoint_digest=?,
                 accepted_checkpoint_completed_at=?
           WHERE run_id=?
          """,
          accepted,
          attemptId,
          checkpoint,
          Timestamp.from(checkpointAt),
          runId);
    } finally {
      jdbc.execute("SET session_replication_role = origin");
    }

    String reportId = "report-publication-v390";
    Instant reportUpdated = Instant.now().minusSeconds(5);
    jdbc.update(
        """
        INSERT INTO validation_report
          (id,run_id,workspace_generation,status,pipeline_result,final_build_passed,
           tests_passed,tests_total,tests_skipped,test_execution_verified,test_evidence_scope,
           updated_at)
        VALUES (?,? ,?,'COMPLETE','PASSED',TRUE,2,2,0,TRUE,'WORKSPACE_TEST_SUITE',?)
        """,
        reportId,
        runId,
        accepted,
        Timestamp.from(reportUpdated));

    String evidenceJson = "{\"report\":\"" + reportId + "\"}";
    String evidenceDigest = sha256(evidenceJson);
    UUID repositoryReceiptId = UUID.randomUUID();
    PublicationPreparationStore.PreparationIntent intent =
        new PublicationPreparationStore.PreparationIntent(
            runId,
            accepted,
            attemptId,
            "GCS",
            "gs://bucket/accepted",
            checkpoint,
            checkpointAt,
            reportId,
            evidenceDigest,
            evidenceJson,
            reportUpdated,
            UUID.randomUUID());
    PublicationPreparationStore.RepositoryIntent repository =
        new PublicationPreparationStore.RepositoryIntent(
            repositoryReceiptId,
            "GITHUB:owner/repository",
            sha256("GITHUB:owner/repository"),
            ".",
            "executespec/run",
            true,
            "prepared output",
            sha256("prepared output"),
            Instant.parse("2026-08-12T12:00:00Z"));
    UUID secondRepositoryReceiptId = UUID.randomUUID();
    PublicationPreparationStore.RepositoryIntent secondRepository =
        new PublicationPreparationStore.RepositoryIntent(
            secondRepositoryReceiptId,
            "GITHUB:owner/second-repository",
            sha256("GITHUB:owner/second-repository"),
            "repos/second-repository",
            "executespec/run",
            true,
            "prepared second output",
            sha256("prepared second output"),
            Instant.parse("2026-08-12T12:00:00Z"));

    UUID preparationId = store.admit(intent, List.of(repository, secondRepository));
    assertThat(state(jdbc, "workspace_publication_preparation", preparationId))
        .isEqualTo("PREPARING");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_publication_preparation SET state='PREPARED',"
                        + " prepared_at=NOW() WHERE id=?",
                    preparationId))
        .rootCause()
        .hasMessageContaining("repository set is incomplete");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_publication_repository_receipt SET repository_identity="
                        + "'GITHUB:forged/repository',state='PREPARED',"
                        + "parent_commit_id=repeat('1',40),tree_id=repeat('2',40),"
                        + "commit_id=repeat('3',40),has_changes=TRUE,prepared_at=NOW() WHERE id=?",
                    repositoryReceiptId))
        .rootCause()
        .hasMessageContaining("identity is immutable");

    store.recordPreparedRepository(
        repositoryReceiptId, "1".repeat(40), "2".repeat(40), "3".repeat(40), true);
    store.recordPreparedRepository(
        secondRepositoryReceiptId, "1".repeat(40), "4".repeat(40), "5".repeat(40), true);
    store.markPrepared(preparationId);
    assertThat(state(jdbc, "workspace_publication_preparation", preparationId))
        .isEqualTo("PREPARED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_publication_repository_receipt SET commit_id=repeat('4',40)"
                        + " WHERE id=?",
                    repositoryReceiptId))
        .rootCause()
        .hasMessageContaining("illegal publication repository transition");

    PublicationPreparationStore.PreparedRepository prepared =
        store.preparedRepositories(preparationId).stream()
            .filter(candidate -> candidate.repositoryReceiptId().equals(repositoryReceiptId))
            .findFirst()
            .orElseThrow();
    PublicationPreparationStore.PreparedRepository secondPrepared =
        store.preparedRepositories(preparationId).stream()
            .filter(candidate -> candidate.repositoryReceiptId().equals(secondRepositoryReceiptId))
            .findFirst()
            .orElseThrow();

    PublicationPreparationStore.RepositoryProvision provision =
        store.admitRepositoryProvision(preparationId, prepared, "GITHUB");
    assertThat(provision.state()).isEqualTo("ADMITTED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO workspace_publication_repository_provision(id,preparation_id,"
                        + "repository_receipt_id,state,provider,target_repository_identity,"
                        + "target_repository_identity_digest,idempotency_key,remote_url,"
                        + "remote_identity_digest,dispatched_at,settled_at) VALUES (?,?,?,"
                        + "'PROVISIONED','GITHUB',?,?,?,'https://github.com/owner/repository',"
                        + "?,NOW(),NOW())",
                    UUID.randomUUID(),
                    preparationId,
                    repositoryReceiptId,
                    prepared.repositoryIdentity(),
                    prepared.repositoryIdentityDigest(),
                    "forged-provision:" + UUID.randomUUID(),
                    prepared.repositoryIdentityDigest()))
        .rootCause()
        .hasMessageContaining("bare ADMITTED intent");
    store.markRepositoryProvisionDispatched(provision.provisionId());
    RunWorkspaceAuthorityService.Lease publicationLease =
        publicationLease(jdbc, runId, UUID.randomUUID());
    store.settleRepositoryProvision(
        provision.provisionId(),
        publicationLease,
        true,
        "https://github.com/owner/repository",
        prepared.repositoryIdentityDigest(),
        null);
    assertThat(state(jdbc, "workspace_publication_repository_provision", provision.provisionId()))
        .isEqualTo("PROVISIONED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_publication_repository_provision SET remote_url="
                        + "'https://github.com/owner/forged' WHERE id=?",
                    provision.provisionId()))
        .rootCause()
        .hasMessageContaining("terminal repository provision is immutable");

    PublicationPreparationStore.RepositoryProvision staleProvision =
        store.admitRepositoryProvision(preparationId, secondPrepared, "GITHUB");
    assertThat(runRecoveryProbe()).contains("provisionsFailed=1");
    assertThat(
            state(jdbc, "workspace_publication_repository_provision", staleProvision.provisionId()))
        .isEqualTo("FAILED");
    PublicationPreparationStore.RepositoryProvision provisionRetry =
        store.admitRepositoryProvision(preparationId, secondPrepared, "GITHUB");
    assertThat(provisionRetry.attemptNumber()).isEqualTo(2);
    store.markRepositoryProvisionDispatched(provisionRetry.provisionId());
    assertThat(runRecoveryProbe()).contains("provisionsIndeterminate=1");
    assertThat(
            state(jdbc, "workspace_publication_repository_provision", provisionRetry.provisionId()))
        .isEqualTo("INDETERMINATE");

    PublicationPreparationStore.PublicationAttempt publication =
        store.admitPublicationAttempt(
            preparationId, prepared, sha256("https://github.com/owner/repository"), null);
    assertThat(publication.state()).isEqualTo("ADMITTED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO workspace_publication_attempt(id,preparation_id,"
                        + "repository_receipt_id,state,repository_identity_digest,"
                        + "publication_branch,prepared_commit_id,remote_identity_digest,"
                        + "idempotency_key,dispatched_at,settled_at) VALUES (?,?,?,'PUBLISHED',"
                        + "?,?,?,repeat('5',64),?,NOW(),NOW())",
                    UUID.randomUUID(),
                    preparationId,
                    repositoryReceiptId,
                    prepared.repositoryIdentityDigest(),
                    prepared.publicationBranch(),
                    prepared.commitId(),
                    "forged-published:" + UUID.randomUUID()))
        .rootCause()
        .hasMessageContaining("bare ADMITTED intent");

    store.markPublicationDispatched(publication.attemptId());
    store.settlePublication(publication.attemptId(), publicationLease, true, "7".repeat(64), null);
    assertThat(state(jdbc, "workspace_publication_attempt", publication.attemptId()))
        .isEqualTo("PUBLISHED");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_publication_attempt SET state='PUBLISHED',"
                        + "provider_receipt_digest=repeat('6',64) WHERE id=?",
                    publication.attemptId()))
        .rootCause()
        .hasMessageContaining("terminal publication attempt is immutable");

    PublicationPreparationStore.PublicationAttempt preDispatchCrash =
        store.admitPublicationAttempt(
            preparationId,
            secondPrepared,
            sha256("https://github.com/owner/second-repository"),
            null);
    assertThat(runRecoveryProbe()).contains("attemptsFailed=1");
    assertThat(state(jdbc, "workspace_publication_attempt", preDispatchCrash.attemptId()))
        .isEqualTo("FAILED");
    PublicationPreparationStore.PublicationAttempt publicationRetry =
        store.admitPublicationAttempt(
            preparationId,
            secondPrepared,
            sha256("https://github.com/owner/second-repository"),
            null);
    assertThat(publicationRetry.attemptNumber()).isEqualTo(2);
    store.markPublicationDispatched(publicationRetry.attemptId());
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS qualification_remote_effect"
            + " (repository_receipt_id UUID PRIMARY KEY, prepared_commit_id VARCHAR(64) NOT NULL)");
    jdbc.update(
        "INSERT INTO qualification_remote_effect(repository_receipt_id,prepared_commit_id)"
            + " VALUES (?,?)",
        secondPrepared.repositoryReceiptId(),
        secondPrepared.commitId());
    publicationLease(jdbc, runId, UUID.randomUUID());
    assertThatThrownBy(
            () ->
                store.settlePublication(
                    publicationRetry.attemptId(), publicationLease, true, "6".repeat(64), null))
        .hasMessage("PUBLICATION_ATTEMPT_SETTLEMENT_FENCE_REJECTED");
    assertThat(state(jdbc, "workspace_publication_attempt", publicationRetry.attemptId()))
        .isEqualTo("DISPATCHED");
    assertThat(runRecoveryProbe()).contains("attemptsIndeterminate=1");
    assertThat(state(jdbc, "workspace_publication_attempt", publicationRetry.attemptId()))
        .isEqualTo("INDETERMINATE");
    assertThat(state(jdbc, "workspace_publication_attempt", publication.attemptId()))
        .isEqualTo("PUBLISHED");
    assertThat(
            jdbc.queryForObject(
                "SELECT prepared_commit_id FROM qualification_remote_effect"
                    + " WHERE repository_receipt_id=?",
                String.class,
                secondPrepared.repositoryReceiptId()))
        .isEqualTo(secondPrepared.commitId());
    assertThat(
            store
                .admitPublicationAttempt(
                    preparationId,
                    secondPrepared,
                    sha256("https://github.com/owner/second-repository"),
                    null)
                .state())
        .isEqualTo("INDETERMINATE");
  }

  private static String state(JdbcTemplate jdbc, String table, UUID id) {
    return jdbc.queryForObject("SELECT state FROM " + table + " WHERE id=?", String.class, id);
  }

  private static RunWorkspaceAuthorityService.Lease publicationLease(
      JdbcTemplate jdbc, String runId, UUID ownerToken) {
    jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", runId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at,updated_at) VALUES (?,'PUBLICATION',?,NOW()+INTERVAL '5 minutes',"
            + "NOW(),NOW())",
        runId,
        ownerToken);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn(runId);
    when(lease.ownerToken()).thenReturn(ownerToken);
    when(lease.authority()).thenReturn(RunWorkspaceAuthorityService.Authority.PUBLICATION);
    return lease;
  }

  private static String runRecoveryProbe() throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath,
                PublicationRecoveryTakeoverProbe.class.getName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                Long.toString(Instant.now().plusSeconds(2).toEpochMilli()))
            .redirectErrorStream(true)
            .start();
    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(finished).as("replacement JVM finished: %s", output).isTrue();
    assertThat(process.exitValue()).as("replacement JVM output: %s", output).isZero();
    assertThat(output).contains("PUBLICATION_TAKEOVER_PASSED");
    return output;
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
