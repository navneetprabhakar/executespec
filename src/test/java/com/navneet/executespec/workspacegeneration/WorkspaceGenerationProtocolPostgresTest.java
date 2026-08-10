package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

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
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'BUILD_GENERATION',?::uuid,"
            + "now() + interval '10 minutes',now())",
        runId,
        workspaceToken.toString());
    jdbc.update(
        """
        INSERT INTO workspace_generation_attempt(
          id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
          before_generation,repositories_json,authority_digest,task_contract_digest,
          ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
          workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
          before_checkpoint_digest,before_checkpoint_completed_at)
        VALUES (?,'run-v374','T1',0,1,'BUILD_TASK','PREPARED','/tmp/run-v374',?,
          '[]'::jsonb,?,?,'{}'::jsonb,?,?,'GCS','gs://bucket/base.zip',?,NOW())
        """,
        attemptId,
        before,
        "d".repeat(64),
        contract,
        "8".repeat(64),
        workspaceToken,
        "9".repeat(64));
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
  before_generation,repositories_json,authority_digest,task_contract_digest,
  ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
  workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
  before_checkpoint_digest,before_checkpoint_completed_at)
SELECT ?,run_id,'T-race',0,1,'BUILD_TASK','PREPARED',workspace_root,
  before_generation,repositories_json,authority_digest,task_contract_digest,
  ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
  workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
  before_checkpoint_digest,before_checkpoint_completed_at
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
                    attemptId))
        .rootCause()
        .hasMessageContaining("without an exact LLM attempt binding");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET ignored_artifact_baseline_json="
                        + " '{\"changed\":true}'::jsonb WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("immutable identity");

    jdbc.update(
        "INSERT INTO llm_execution_context("
            + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
            + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
            + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,status,max_attempts,idempotency_key,request_fingerprint)"
            + " VALUES (?,'PIPELINE_RUN','worker','PIPELINE',?,'correlation-v374',"
            + "'PLATFORM_INTERNAL','platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,"
            + "'{}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,'QUEUED',1,?,repeat('4',64))",
        llmContextId,
        runId,
        "idem-v374");
    jdbc.update(
        "INSERT INTO llm_execution_operation("
            + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
            + "operation_fingerprint,status,attempt,max_attempts,next_attempt_at,created_at,updated_at)"
            + " VALUES (?,?,?,'RUN_BUILD_TASK',1,repeat('5',64),'QUEUED',0,1,now(),now(),now())",
        llmOperationId,
        llmContextId,
        "pipeline-run:run-v374:build-task:T1");
    jdbc.update(
        "UPDATE llm_execution_operation SET status='RUNNING',attempt=1,lease_owner='owner-v374',"
            + "lease_token=?::uuid,lease_expires_at=now() + interval '500 milliseconds',"
            + "heartbeat_at=now(),not_reclaim_before=now() + interval '10 minutes',"
            + "next_attempt_at=NULL,started_at=now(),updated_at=now() WHERE id=?",
        llmToken.toString(),
        llmOperationId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET llm_operation_id=?,llm_attempt_number=1,"
            + "llm_lease_token=?::uuid WHERE id=?",
        llmOperationId,
        llmToken.toString(),
        attemptId);
    assertThat(dispatchAuthorityCount(jdbc, attemptId)).isEqualTo(1);
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM llm_execution_operation WHERE id=?", llmOperationId))
        .rootCause()
        .hasMessageContaining("workspace_generation_attempt");

    UUID wrongToken = UUID.randomUUID();
    jdbc.update(
        "UPDATE run_workspace_authority SET owner_token=?::uuid WHERE run_id=?",
        wrongToken.toString(),
        runId);
    assertThat(dispatchAuthorityCount(jdbc, attemptId)).isZero();
    jdbc.update(
        "UPDATE run_workspace_authority SET owner_token=?::uuid WHERE run_id=?",
        workspaceToken.toString(),
        runId);
    jdbc.update(
        "UPDATE run_workspace_authority SET lease_expires_at=now() - interval '1 second'"
            + " WHERE run_id=?",
        runId);
    assertThat(dispatchAuthorityCount(jdbc, attemptId)).isZero();
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
      Thread.sleep(600);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
    assertThat(dispatchAuthorityCount(jdbc, attemptId)).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    candidate,
                    attemptId))
        .rootCause()
        .hasMessageContaining("LLM attempt lease is not current");
    jdbc.update(
        "UPDATE llm_execution_operation SET heartbeat_at=now(),"
            + "lease_expires_at=now() + interval '10 minutes',updated_at=now() WHERE id=?",
        llmOperationId);
    assertThat(dispatchAuthorityCount(jdbc, attemptId)).isEqualTo(1);
    jdbc.update("UPDATE pipeline_run SET status='PAUSED' WHERE run_id=?", runId);
    assertThat(dispatchAuthorityCount(jdbc, attemptId)).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    candidate,
                    attemptId))
        .rootCause()
        .hasMessageContaining("workspace generation run is not working");
    jdbc.update("UPDATE pipeline_run SET status='WORKING' WHERE run_id=?", runId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='VERIFYING', candidate_generation=?"
            + " WHERE id=?",
        candidate,
        attemptId);
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
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='CHECKPOINTING' WHERE id=?", attemptId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='ACCEPTED' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("lacks an exact completed checkpoint");

    jdbc.update(
        """
        UPDATE workspace_generation_attempt
           SET checkpoint_kind='GCS', checkpoint_key='gs://bucket/run-v374.zip',
               checkpoint_generation=?, checkpoint_digest=?, checkpoint_completed_at=NOW()
         WHERE id=?
        """,
        candidate,
        "1".repeat(64),
        attemptId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET checkpoint_key="
                        + "'gs://bucket/replaced.zip' WHERE id=?",
                    attemptId))
        .rootCause()
        .hasMessageContaining("checkpoint receipt is immutable");
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ACCEPTED',resolved_at=NOW() WHERE id=?",
        attemptId);
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
        .hasMessageContaining("lacks exact checkpoint receipt");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_verification_receipt SET evidence_json='{}'"
                        + " WHERE id=?",
                    passingReceipt))
        .rootCause()
        .hasMessageContaining("receipts are immutable");
    assertThatThrownBy(
            () -> jdbc.update("DELETE FROM workspace_generation_attempt WHERE id=?", attemptId))
        .rootCause()
        .hasMessageContaining("cannot be deleted while its run exists");

    UUID cancelledAttemptId = UUID.randomUUID();
    UUID cancelledLlmToken = UUID.randomUUID();
    String cancelledLlmOperationId = "llmop-v374-cancelled";
    jdbc.update(
        "INSERT INTO llm_execution_operation("
            + "id,llm_execution_context_id,operation_key,operation_kind,invocation,"
            + "operation_fingerprint,status,attempt,max_attempts,next_attempt_at,created_at,updated_at)"
            + " VALUES (?,?,?,'RUN_BUILD_TASK',1,repeat('7',64),'QUEUED',0,1,now(),now(),now())",
        cancelledLlmOperationId,
        llmContextId,
        "pipeline-run:run-v374:build-task:T2");
    jdbc.update(
        "UPDATE llm_execution_operation SET status='RUNNING',attempt=1,"
            + "lease_owner='owner-v374-cancelled',"
            + "lease_token=?::uuid,lease_expires_at=now() + interval '10 minutes',"
            + "heartbeat_at=now(),not_reclaim_before=now() + interval '10 minutes',"
            + "next_attempt_at=NULL,started_at=now(),updated_at=now() WHERE id=?",
        cancelledLlmToken.toString(),
        cancelledLlmOperationId);
    jdbc.update(
        """
        INSERT INTO workspace_generation_attempt(
          id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
          before_generation,repositories_json,authority_digest,task_contract_digest,
          ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
          workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
          before_checkpoint_digest,before_checkpoint_completed_at)
        VALUES (?,'run-v374','T2',1,1,'BUILD_TASK','PREPARED','/tmp/run-v374',?,
          '[]'::jsonb,?,?,'{}'::jsonb,?,?,'GCS','gs://bucket/task-2-base.zip',?,NOW())
        """,
        cancelledAttemptId,
        candidate,
        "4".repeat(64),
        contract,
        "5".repeat(64),
        workspaceToken,
        "6".repeat(64));
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", cancelledAttemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET llm_operation_id=?,"
            + "llm_attempt_number=1,llm_lease_token=?::uuid WHERE id=?",
        cancelledLlmOperationId,
        cancelledLlmToken.toString(),
        cancelledAttemptId);
    jdbc.update("UPDATE pipeline_run SET status='CANCELLED' WHERE run_id=?", runId);
    assertThat(dispatchAuthorityCount(jdbc, cancelledAttemptId)).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET state='VERIFYING',"
                        + " candidate_generation=? WHERE id=?",
                    "7".repeat(64),
                    cancelledAttemptId))
        .rootCause()
        .hasMessageContaining("workspace generation run is not working");

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
    jdbc.update(
        """
        INSERT INTO workspace_generation_attempt(
          id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
          before_generation,repositories_json,authority_digest,task_contract_digest,
          ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
          workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
          before_checkpoint_digest,before_checkpoint_completed_at)
        VALUES (?,?,'T1',0,1,'BUILD_TASK','PREPARED','/tmp/run-v374-recovery',?,
          '[]'::jsonb,?,?,'{}'::jsonb,?,?,'GCS','gs://bucket/recovery-base.tar.gz',?,NOW())
        """,
        recoveryAttemptId,
        recoveryRunId,
        before,
        "6".repeat(64),
        contract,
        "7".repeat(64),
        recoveryWorkspaceToken,
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

    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", runId);
    jdbc.update("DELETE FROM pipeline_run WHERE run_id=?", recoveryRunId);
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

  private static int dispatchAuthorityCount(JdbcTemplate jdbc, UUID attemptId) {
    return jdbc.queryForObject(
        """
        SELECT COUNT(*)
          FROM workspace_generation_attempt a
          JOIN pipeline_run p ON p.run_id = a.run_id
          JOIN run_workspace_authority w ON w.run_id = a.run_id
          JOIN llm_execution_operation o ON o.id = a.llm_operation_id
         WHERE a.id = ?
           AND a.state = 'MUTATING'
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
        attemptId);
  }
}
