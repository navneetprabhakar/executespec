package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.ControlPlaneSchemaEvidenceExporter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof of V374 transition, receipt, checkpoint, and retention invariants. */
@Tag("integration")
@Testcontainers
class WorkspaceGenerationProtocolPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("workspace_generation_protocol")
          .withUsername("test")
          .withPassword("test");

  @Test
  void acceptsOnlyExactVerifiedCheckpointedGenerationAndRetainsEvidenceUntilRunDeletion() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

    WorkspaceGenerationSchemaAttestor.Attestation schema =
        new WorkspaceGenerationSchemaAttestor(jdbc).attest();
    assertThat(schema.ready()).isTrue();
    assertThat(schema.latestSuccessfulMigration()).isEqualTo("399");
    assertThat(schema.artifacts())
        .hasSize(62)
        .allMatch(
            artifact ->
                artifact.present()
                    && artifact.active()
                    && artifact.definitionSha256().length() == 64);
    var releaseSchema = new ControlPlaneSchemaEvidenceExporter(jdbc).export(schema);
    assertThat(releaseSchema.complete()).isTrue();
    assertThat(releaseSchema.appliedMigrations())
        .isNotEmpty()
        .allMatch(ControlPlaneSchemaEvidenceExporter.AppliedMigration::success)
        .last()
        .extracting(ControlPlaneSchemaEvidenceExporter.AppliedMigration::version)
        .isEqualTo("399");
    assertThat(releaseSchema.definitions())
        .hasSize(62)
        .allMatch(
            definition ->
                definition.present()
                    && definition.active()
                    && definition.digestMatchesAttestor()
                    && definition.definition() != null
                    && !definition.definition().isBlank());
    jdbc.execute(
        "ALTER TABLE pipeline_run DISABLE TRIGGER pipeline_run_build_protocol_version_immutable");
    var disabledSchema = new WorkspaceGenerationSchemaAttestor(jdbc).attest();
    assertThat(disabledSchema.ready()).isFalse();
    var disabledReleaseSchema = new ControlPlaneSchemaEvidenceExporter(jdbc).export(disabledSchema);
    assertThat(disabledReleaseSchema.complete()).isFalse();
    assertThat(disabledSchema.artifacts())
        .filteredOn(
            artifact -> "pipeline_run_build_protocol_version_immutable".equals(artifact.name()))
        .singleElement()
        .satisfies(
            artifact -> {
              assertThat(artifact.present()).isTrue();
              assertThat(artifact.active()).isFalse();
            });
    jdbc.execute(
        "ALTER TABLE pipeline_run ENABLE TRIGGER pipeline_run_build_protocol_version_immutable");

    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode) VALUES ('cleanup-run','FAILED','PATCH')");
    WorkspaceGenerationRecoveryCleanupStore cleanupStore =
        new WorkspaceGenerationRecoveryCleanupStore(jdbc);
    cleanupStore.enqueue("cleanup-run", "/tmp/cleanup-run.displaced-fixture");
    cleanupStore.enqueue("cleanup-run", "/tmp/cleanup-run.displaced-fixture");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_generation_recovery_cleanup WHERE"
                    + " run_id='cleanup-run'",
                Integer.class))
        .isEqualTo(1);
    var cleanupClaim1 = cleanupStore.claimNext(java.time.Duration.ZERO).orElseThrow();
    cleanupStore.fail(cleanupClaim1, "fixture failure");
    var cleanupClaim2 = cleanupStore.claimNext(java.time.Duration.ZERO).orElseThrow();
    assertThat(cleanupClaim2.attempt()).isEqualTo(2);
    cleanupStore.complete(cleanupClaim2);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM workspace_generation_recovery_cleanup WHERE"
                    + " run_id='cleanup-run'",
                String.class))
        .isEqualTo("COMPLETED");

    String pinnedRun = "run-v385-pinned";
    UUID pinnedAttempt = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        pinnedRun);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "build_protocol_version) VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED',"
            + "'/tmp/pinned',repeat('1',64),repeat('2',64),'SHARED_KERNEL_V2')",
        pinnedAttempt,
        pinnedRun);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,"
                        + "attempt_number,attempt_kind,state,workspace_root,authority_digest,"
                        + "task_contract_digest,build_protocol_version) VALUES (?,?,'T2',1,1,"
                        + "'BUILD_TASK','ADMITTED','/tmp/mismatch',repeat('1',64),repeat('2',64),"
                        + "'LEGACY_V1')",
                    UUID.randomUUID(),
                    pinnedRun))
        .rootCause()
        .hasMessageContaining("does not match parent run");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    typedUnavailableReceiptInsert(null, null),
                    UUID.randomUUID(),
                    pinnedAttempt,
                    "3".repeat(64),
                    "2".repeat(64),
                    "4".repeat(64),
                    "5".repeat(64)))
        .rootCause()
        .hasMessageContaining("protocol-v2 workspace verification causality is incomplete");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET build_protocol_version='LEGACY_V1' WHERE run_id=?",
                    pinnedRun))
        .rootCause()
        .hasMessageContaining("BUILD protocol identity/disposition is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
                        + " VALUES ('run-v385-patch','WORKING','PATCH','SHARED_KERNEL_V2')"))
        .rootCause()
        .hasMessageContaining("non-BUILD run cannot carry a BUILD protocol version");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
                        + " VALUES ('run-v385-null-mode','WORKING',NULL,'SHARED_KERNEL_V2')"))
        .rootCause()
        .hasMessageContaining("non-BUILD run cannot carry a BUILD protocol version");

    String runId = "run-v374";
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    UUID llmToken = UUID.randomUUID();
    String llmContextId = "llmctx-v374";
    String llmOperationId = "llmop-v374";
    String before = "a".repeat(64);
    String candidate = "b".repeat(64);
    String contract = "c".repeat(64);
    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES ('run-v374_T1',?,'WORKING',0,NOW())",
        runId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'BUILD_GENERATION',?::uuid,"
            + "now() + interval '10 minutes',now())",
        runId,
        workspaceToken.toString());
    String unboundRun = "run-v383-unbound";
    UUID unboundAttempt = UUID.randomUUID();
    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", unboundRun);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET build_protocol_version='SHARED_KERNEL_V2'"
                        + " WHERE run_id=?",
                    unboundRun))
        .rootCause()
        .hasMessageContaining("BUILD protocol identity/disposition is immutable");
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest)"
            + " VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED','/tmp/unbound',repeat('1',64),"
            + "repeat('2',64))",
        unboundAttempt,
        unboundRun);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET"
                        + " build_protocol_version='SHARED_KERNEL_V2' WHERE id=?",
                    unboundAttempt))
        .rootCause()
        .hasMessageContaining("BUILD protocol identity/disposition is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + "candidate_generation=? WHERE id=?",
                    candidate,
                    unboundAttempt))
        .rootCause()
        .hasMessageContaining("lacks exact RUNNING LLM operation");
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        attemptId,
        runId,
        "T1",
        0,
        1,
        "/tmp/run-v374",
        workspaceToken,
        before,
        "[]",
        "d".repeat(64),
        contract,
        "{}",
        "8".repeat(64),
        "GCS",
        "gs://bucket/base.zip",
        "9".repeat(64),
        llmContextId,
        llmOperationId,
        llmToken,
        java.time.Duration.ofSeconds(2));
    jdbc.update(
        "UPDATE pipeline_run SET accepted_workspace_generation=?,accepted_task_index=-1"
            + " WHERE run_id=?",
        before,
        runId);

    assertThatThrownBy(
            () ->
                jdbc.update("UPDATE pipeline_run SET accepted_task_index=1 WHERE run_id=?", runId))
        .rootCause()
        .hasMessageContaining("accepted workspace generation frontier cannot skip task indexes");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='ACCEPTED' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("illegal workspace generation transition");

    jdbc.update("UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET task_contract_json='{}'::jsonb,"
            + "initial_failure_evidence_json='{}'::jsonb,requested_provider='openai',"
            + "requested_model='gpt-5.4',effective_provider='openai',effective_model='gpt-5.4',"
            + "worker_iterations=3,worker_tool_calls=5,model_calls=4,input_tokens=100,"
            + "output_tokens=20,cache_read_tokens=10,cache_write_tokens=2,reasoning_tokens=7,"
            + "cost_usd=0.125 WHERE id=?",
        attemptId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET"
                        + " initial_failure_observed_at=now() WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("observed initial failure fields must be written together");
    jdbc.update(
        "UPDATE workspace_generation_attempt SET observed_initial_failure_evidence_json="
            + "'{\"outcome\":\"FAILURE_OBSERVED\",\"code\":\"INITIAL_TESTS_FAILED\"}'::jsonb,"
            + "initial_failure_observed_at=now() WHERE id=?",
        attemptId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET"
                        + " observed_initial_failure_evidence_json="
                        + "'{\"outcome\":\"ALREADY_PASSED\"}'::jsonb WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("observed initial failure is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET effective_model='gpt-5.4-mini'"
                        + " WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation execution evidence is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET task_contract_json="
                        + "'{\"changed\":true}'::jsonb WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation task contract is immutable");
    UUID competingAttemptId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
INSERT INTO workspace_generation_attempt(
  id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
  authority_digest,task_contract_digest)
SELECT ?,run_id,'T-race',0,1,'BUILD_TASK','ADMITTED',workspace_root,
  authority_digest,task_contract_digest
FROM workspace_generation_attempt WHERE id=?
""",
                    competingAttemptId,
                    attemptId))
        .rootCause()
        .hasMessageContaining("uq_workspace_generation_attempt_active_run");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    candidate,
                    unboundAttempt))
        .rootCause()
        .hasMessageContaining("lacks exact RUNNING LLM operation");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET ignored_artifact_baseline_json="
                        + " '{\"changed\":true}'::jsonb WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("immutable identity");

    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isEqualTo(1);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM llm_execution_operation WHERE id=?", llmOperationId))
        .rootCause()
        .hasMessageContaining("workspace_generation_attempt");

    UUID wrongToken = UUID.randomUUID();
    jdbc.update(
        "UPDATE run_workspace_authority SET owner_token=?::uuid WHERE run_id=?",
        wrongToken.toString(),
        runId);
    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isZero();
    jdbc.update(
        "UPDATE run_workspace_authority SET owner_token=?::uuid WHERE run_id=?",
        workspaceToken.toString(),
        runId);
    jdbc.update(
        "UPDATE run_workspace_authority SET lease_expires_at=now() - interval '1 second'"
            + " WHERE run_id=?",
        runId);
    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isZero();
    jdbc.update(
        "UPDATE run_workspace_authority SET lease_expires_at=now() + interval '10 minutes'"
            + " WHERE run_id=?",
        runId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE llm_execution_operation SET lease_token=?::uuid WHERE id=?",
                    wrongToken.toString(),
                    llmOperationId))
        .rootCause()
        .hasMessageContaining("illegal RUNNING operation heartbeat");
    try {
      Thread.sleep(2100);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    candidate,
                    attemptId))
        .rootCause()
        .hasMessageContaining("lacks exact RUNNING LLM operation");
    jdbc.update(
        "UPDATE llm_execution_operation SET heartbeat_at=now(),"
            + "lease_expires_at=now() + interval '10 minutes',updated_at=now() WHERE id=?",
        llmOperationId);
    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isEqualTo(1);
    jdbc.update("UPDATE pipeline_run SET status='PAUSED' WHERE run_id=?", runId);
    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    candidate,
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation admission requires WORKING run");
    jdbc.update("UPDATE pipeline_run SET status='WORKING' WHERE run_id=?", runId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='VERIFYING', candidate_generation=?"
            + " WHERE id=?",
        candidate,
        attemptId);
    assertThat(authorityCount(jdbc, attemptId, "MUTATING")).isZero();
    assertThat(authorityCount(jdbc, attemptId, "VERIFYING")).isEqualTo(1);
    jdbc.update(
        receiptInsert(),
        UUID.randomUUID(),
        attemptId,
        candidate,
        contract,
        "e".repeat(64),
        "2".repeat(64));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFIED' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("lacks an exact passing verification receipt");

    UUID passingReceipt = UUID.randomUUID();
    jdbc.update(
        receiptInsert().replace("'FAILED',FALSE", "'PASSED',TRUE"),
        passingReceipt,
        attemptId,
        candidate,
        contract,
        "f".repeat(64),
        "3".repeat(64));
    jdbc.update("UPDATE workspace_generation_attempt SET state='VERIFIED' WHERE id=?", attemptId);
    assertThat(authorityCount(jdbc, attemptId, "VERIFIED")).isEqualTo(1);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='CHECKPOINTING' WHERE id=?", attemptId);
    assertThat(authorityCount(jdbc, attemptId, "CHECKPOINTING")).isEqualTo(1);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='ACCEPTED' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("lacks an exact completed checkpoint");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET checkpoint_kind='GCS',"
                        + " checkpoint_key='gs://bucket/unbound.tar.gz',"
                        + " checkpoint_generation=?,checkpoint_digest=?,"
                        + " checkpoint_completed_at=NOW() WHERE id=?",
                    candidate,
                    "1".repeat(64),
                    attemptId))
        .rootCause()
        .hasMessageContaining("checkpoint storage identity is incomplete");

    jdbc.update(
        """
        UPDATE workspace_generation_attempt
           SET checkpoint_kind='GCS', checkpoint_key='gs://bucket/run-v374.zip',
               checkpoint_generation=?, checkpoint_digest=?,
               checkpoint_storage_generation='fixture-generation', checkpoint_size_bytes=1024,
               checkpoint_crc32c='fixture-crc', checkpoint_etag='fixture-etag',
               checkpoint_completed_at=NOW()
         WHERE id=?
        """,
        candidate,
        "1".repeat(64),
        attemptId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET checkpoint_etag='replaced' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("checkpoint storage identity is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET checkpoint_key="
                        + "'gs://bucket/replaced.zip' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("checkpoint receipt is immutable");
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ACCEPTED',resolved_at=NOW(),"
            + "task_transition_kind='MARK_COMPLETE',task_transition_state='PENDING' WHERE id=?",
        attemptId);
    assertThat(
            jdbc.queryForMap(
                "SELECT task_transition_kind,task_transition_state,"
                    + "task_transition_expected_status,task_transition_expected_recovery_attempt,"
                    + "operation_transition_target,operation_transition_state "
                    + "FROM workspace_generation_attempt WHERE id=?",
                attemptId))
        .containsEntry("task_transition_kind", "MARK_COMPLETE")
        .containsEntry("task_transition_state", "PENDING")
        .containsEntry("task_transition_expected_status", "WORKING")
        .containsEntry("task_transition_expected_recovery_attempt", 0)
        .containsEntry("operation_transition_target", "SUCCEEDED")
        .containsEntry("operation_transition_state", "PENDING");
    jdbc.update(
        "UPDATE pipeline_run SET accepted_workspace_generation=?,accepted_task_index=0,"
            + " accepted_generation_attempt_id=?,accepted_checkpoint_kind='GCS',"
            + " accepted_checkpoint_key='gs://bucket/run-v374.zip',"
            + " accepted_checkpoint_digest=?,accepted_checkpoint_completed_at=(SELECT"
            + " checkpoint_completed_at FROM workspace_generation_attempt WHERE id=?)"
            + " WHERE run_id=?",
        candidate,
        attemptId,
        "1".repeat(64),
        attemptId,
        runId);

    assertThatThrownBy(
            () -> jdbc.update("UPDATE pipeline_run SET status='COMPLETE' WHERE run_id=?", runId))
        .rootCause()
        .hasMessageContaining("terminal BUILD run retains unresolved generation state");

    WorkspaceGenerationOperationReconciler operationReconciler =
        new WorkspaceGenerationOperationReconciler(jdbc);
    assertThat(operationReconciler.reconcile(attemptId))
        .isEqualTo(WorkspaceGenerationOperationReconciler.Outcome.RECONCILED);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM llm_execution_operation WHERE id=?",
                String.class,
                llmOperationId))
        .isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForMap(
                "SELECT operation_transition_target,operation_transition_state,"
                    + "operation_transition_completed_at IS NOT NULL AS receipted "
                    + "FROM workspace_generation_attempt WHERE id=?",
                attemptId))
        .containsEntry("operation_transition_target", "SUCCEEDED")
        .containsEntry("operation_transition_state", "COMPLETED")
        .containsEntry("receipted", true);
    operationReconciler.assertRunConverged(runId);

    assertThat(
            jdbc.queryForObject(
                "SELECT gcs_workspace_key FROM pipeline_run WHERE run_id=?", String.class, runId))
        .isNull();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET accepted_generation_attempt_id=NULL WHERE run_id=?",
                    runId))
        .rootCause()
        .hasMessageContaining("accepted workspace generation frontier is immutable");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET accepted_workspace_generation=NULL,"
                        + "accepted_task_index=NULL,accepted_generation_attempt_id=NULL,"
                        + "accepted_checkpoint_kind=NULL,accepted_checkpoint_key=NULL,"
                        + "accepted_checkpoint_digest=NULL,accepted_checkpoint_completed_at=NULL"
                        + " WHERE run_id=?",
                    runId))
        .rootCause()
        .hasMessageContaining("accepted workspace generation frontier cannot be cleared");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET accepted_checkpoint_key="
                        + "'gs://bucket/provisional.zip'"
                        + " WHERE run_id=?",
                    runId))
        .rootCause()
        .hasMessageContaining("accepted workspace generation frontier is immutable");

    UUID nextAttemptId = UUID.randomUUID();
    String nextCandidate = "6".repeat(64);
    String nextContract = "4".repeat(64);
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        nextAttemptId,
        runId,
        "T-checkpoint-proof",
        1,
        1,
        "/tmp/run-v374",
        workspaceToken,
        candidate,
        "[]",
        "d".repeat(64),
        nextContract,
        "{}",
        "8".repeat(64),
        "GCS",
        "gs://bucket/run-v374.zip",
        "1".repeat(64));
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", nextAttemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='VERIFYING',candidate_generation=? WHERE"
            + " id=?",
        nextCandidate,
        nextAttemptId);
    jdbc.update(
        receiptInsert().replace("'FAILED',FALSE", "'PASSED',TRUE"),
        UUID.randomUUID(),
        nextAttemptId,
        nextCandidate,
        nextContract,
        "5".repeat(64),
        "6".repeat(64));
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='VERIFIED' WHERE id=?", nextAttemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='CHECKPOINTING' WHERE id=?", nextAttemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET checkpoint_kind='GCS',"
            + "checkpoint_key='gs://bucket/run-v374-next.zip',checkpoint_generation=?,"
            + "checkpoint_digest=?,checkpoint_storage_generation='fixture-generation',"
            + "checkpoint_size_bytes=1024,checkpoint_crc32c='fixture-crc',"
            + "checkpoint_etag='fixture-etag',checkpoint_completed_at=NOW() WHERE id=?",
        nextCandidate,
        "7".repeat(64),
        nextAttemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ACCEPTED',resolved_at=NOW() WHERE id=?",
        nextAttemptId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE pipeline_run SET accepted_workspace_generation=?,accepted_task_index=1,"
                        + "accepted_generation_attempt_id=?,accepted_checkpoint_kind='GCS',"
                        + "accepted_checkpoint_key='gs://bucket/wrong-next.zip',"
                        + "accepted_checkpoint_digest=?,accepted_checkpoint_completed_at=(SELECT "
                        + "checkpoint_completed_at FROM workspace_generation_attempt WHERE id=?) "
                        + "WHERE run_id=?",
                    nextCandidate,
                    nextAttemptId,
                    "7".repeat(64),
                    nextAttemptId,
                    runId))
        .rootCause()
        .hasMessageContaining("lacks exact checkpoint receipt");
    // The intentionally rejected frontier write leaves a terminal attempt in this fixture. Its
    // exact physical operation still has to consume the durable terminal intent before any later
    // sequential BUILD attempt may be admitted.
    assertThat(operationReconciler.reconcile(nextAttemptId))
        .isEqualTo(WorkspaceGenerationOperationReconciler.Outcome.RECONCILED);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_verification_receipt SET evidence_json='{}'"
                        + " WHERE id=?",
                    passingReceipt))
        .rootCause()
        .hasMessageContaining("receipts are immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    typedUnavailableReceiptInsert("PRODUCT", "BUILD_POLICY_DENIED"),
                    UUID.randomUUID(),
                    nextAttemptId,
                    nextCandidate,
                    nextContract,
                    "7".repeat(64),
                    "8".repeat(64)))
        .rootCause()
        .hasMessageContaining("workspace_generation_verification_failure_shape_chk");
    UUID unavailableReceipt = UUID.randomUUID();
    assertThat(
            jdbc.update(
                typedUnavailableReceiptInsert("ENVIRONMENT", "BUILD_POLICY_DENIED"),
                unavailableReceipt,
                nextAttemptId,
                nextCandidate,
                nextContract,
                "9".repeat(64),
                "a".repeat(64)))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_verification_receipt"
                        + " SET failure_category='INTERNAL_PROTOCOL' WHERE id=?",
                    unavailableReceipt))
        .rootCause()
        .hasMessageContaining("receipts are immutable");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM workspace_generation_attempt WHERE id=?", attemptId))
        .rootCause()
        .hasMessageContaining("cannot be deleted while its run exists");

    UUID cancelledAttemptId = UUID.randomUUID();
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        cancelledAttemptId,
        runId,
        "T2",
        1,
        1,
        "/tmp/run-v374",
        workspaceToken,
        candidate,
        "[]",
        "4".repeat(64),
        contract,
        "{}",
        "5".repeat(64),
        "GCS",
        "gs://bucket/task-2-base.zip",
        "6".repeat(64));
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", cancelledAttemptId);
    jdbc.update("UPDATE pipeline_run SET status='CANCELLED' WHERE run_id=?", runId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
INSERT INTO workspace_generation_attempt(
  id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
  authority_digest,task_contract_digest)
VALUES (?,'run-v374','T3',2,1,'BUILD_TASK','ADMITTED','/tmp/run-v374',?,?)
""",
                    UUID.randomUUID(),
                    "4".repeat(64),
                    contract))
        .rootCause()
        .hasMessageContaining("requires WORKING run");
    assertThat(authorityCount(jdbc, cancelledAttemptId, "MUTATING")).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    "7".repeat(64),
                    cancelledAttemptId))
        .rootCause()
        .hasMessageContaining("workspace generation admission requires WORKING run");

    String recoveryRunId = "run-v374-prebind-recovery";
    UUID recoveryAttemptId = UUID.randomUUID();
    UUID recoveryWorkspaceToken = UUID.randomUUID();
    UUID recoveryToken = UUID.randomUUID();
    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", recoveryRunId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'BUILD_GENERATION',?::uuid,"
            + "now() + interval '10 minutes',now())",
        recoveryRunId,
        recoveryWorkspaceToken.toString());
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        recoveryAttemptId,
        recoveryRunId,
        "T1",
        0,
        1,
        "/tmp/run-v374-recovery",
        recoveryWorkspaceToken,
        before,
        "[]",
        "6".repeat(64),
        contract,
        "{}",
        "7".repeat(64),
        "GCS",
        "gs://bucket/recovery-base.tar.gz",
        "8".repeat(64));
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", recoveryAttemptId);
    jdbc.update(
        "UPDATE run_workspace_authority SET authority_type='RECOVERY',owner_token=?::uuid,"
            + "lease_expires_at=now() + interval '10 minutes',heartbeat_at=now() "
            + "WHERE run_id=?",
        recoveryToken.toString(),
        recoveryRunId);
    String recoveryClaim =
        "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
            + "recovery_lease_token=?::uuid,recovery_started_at=now()"
            + " WHERE id=? AND state='MUTATING'";
    assertThat(jdbc.update(recoveryClaim, recoveryToken.toString(), recoveryAttemptId)).isOne();
    assertThat(jdbc.update(recoveryClaim, recoveryToken.toString(), recoveryAttemptId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM workspace_generation_attempt WHERE id=?",
                String.class,
                recoveryAttemptId))
        .isEqualTo("ROLLING_BACK");
    jdbc.update(
        "UPDATE workspace_generation_attempt SET rejected_diff_digest=?,"
            + "rejected_diff_json='{\"changedFiles\":[\"tracked.txt\"]}'::jsonb,"
            + "failure_code='INTERRUPTED_GENERATION_RECOVERED',"
            + "failure_reason='original interrupted generation' WHERE id=?",
        "a".repeat(64),
        recoveryAttemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='RECOVERY_FAILED',"
            + "recovery_failure_code='ROLLBACK_PROOF_FAILED',"
            + "recovery_failure_reason='archive unavailable',recovery_failure_at=NOW()"
            + " WHERE id=?",
        recoveryAttemptId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET failure_reason='overwritten'"
                        + " WHERE id=?",
                    recoveryAttemptId))
        .rootCause()
        .hasMessageContaining("original failure evidence is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET rejected_diff_digest=? WHERE id=?",
                    "b".repeat(64),
                    recoveryAttemptId))
        .rootCause()
        .hasMessageContaining("rejected diff evidence is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET recovery_failure_reason='new error'"
                        + " WHERE id=?",
                    recoveryAttemptId))
        .rootCause()
        .hasMessageContaining("recovery failure evidence is immutable");
    assertThat(
            jdbc.queryForObject(
                "SELECT failure_code FROM workspace_generation_attempt WHERE id=?",
                String.class,
                recoveryAttemptId))
        .isEqualTo("INTERRUPTED_GENERATION_RECOVERED");

    WorkspaceGenerationHistoricalAdjudicationService adjudication =
        new WorkspaceGenerationHistoricalAdjudicationService(jdbc, new ObjectMapper());
    for (String historicalRun : java.util.List.of(runId, recoveryRunId)) {
      var release =
          adjudication.dryRun(
              WorkspaceGenerationHistoricalAdjudicationService.RecordKind.RUN,
              historicalRun,
              WorkspaceGenerationHistoricalAdjudicationService.Action.RELEASE_FOR_DELETION,
              "Protocol fixture explicitly releases retained legacy evidence after verification.",
              "postgres-fixture");
      adjudication.apply(
          release.id(),
          "Protocol fixture explicitly applies the exact retained-evidence deletion release.",
          "postgres-fixture");
      jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", historicalRun);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_generation_attempt WHERE id=?",
                Integer.class,
                attemptId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM workspace_generation_verification_receipt WHERE id=?",
                Integer.class,
                passingReceipt))
        .isZero();
  }

  private static String receiptInsert() {
    return """
INSERT INTO workspace_generation_verification_receipt(
  id,attempt_id,candidate_generation,task_contract_digest,verifier_kind,command_source,
  command_digest,outcome,build_passed,tests_executed,tests_passed,tests_failed,tests_errors,
  tests_skipped,evidence_json,evidence_digest,started_at,completed_at)
VALUES (?,?,?,?,'BUILD_ONLY','SERVER_RESOLVED_CONTRACT',?,'FAILED',FALSE,FALSE,
  0,0,0,0,'{}'::jsonb,?,NOW(),NOW())
""";
  }

  private static String typedUnavailableReceiptInsert(String category, String environmentCode) {
    String categorySql = category == null ? "NULL" : "'" + category + "'";
    String environmentSql = environmentCode == null ? "NULL" : "'" + environmentCode + "'";
    return """
INSERT INTO workspace_generation_verification_receipt(
  id,attempt_id,candidate_generation,task_contract_digest,verifier_kind,command_source,
  command_digest,outcome,failure_category,environment_code,execution_boundary,build_passed,
  tests_executed,tests_passed,tests_failed,tests_errors,tests_skipped,evidence_json,evidence_digest,
  started_at,completed_at)
VALUES (?,?,?,?,'BUILD_ONLY','SERVER_RESOLVED_CONTRACT',?,'UNAVAILABLE',
  %s,%s,'DISPOSABLE_EXACT_GENERATION',FALSE,FALSE,0,0,0,0,'{}'::jsonb,?,NOW(),NOW())
"""
        .formatted(categorySql, environmentSql);
  }

  private static int authorityCount(JdbcTemplate jdbc, UUID attemptId, String expectedState) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*)
          FROM workspace_generation_attempt a
          JOIN pipeline_run p ON p.run_id = a.run_id
          JOIN run_workspace_authority w ON w.run_id = a.run_id
          JOIN llm_execution_operation o ON o.id = a.llm_operation_id
         WHERE a.id = ?
           AND a.state = ?
           AND p.status = 'WORKING'
           AND w.owner_token = a.workspace_lease_token
           AND w.authority_type = 'BUILD_GENERATION'
           AND w.lease_expires_at >= NOW()
           AND o.attempt = a.llm_attempt_number
           AND o.lease_token = a.llm_lease_token
           AND o.operation_kind = 'RUN_BUILD_TASK'
           AND o.operation_key = CONCAT('pipeline-run:', a.run_id, ':build-task:', a.task_id)
           AND o.status = 'RUNNING'
           AND o.lease_expires_at >= NOW()
        """,
        Integer.class,
        attemptId,
        expectedState);
  }
}
