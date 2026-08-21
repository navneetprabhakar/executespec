package com.navneet.executespec.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL proof of V389 command admission, settlement, recovery, and receipt binding. */
@Tag("integration")
@Testcontainers
class SandboxCommandJournalPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("sandbox_command_journal")
          .withUsername("test")
          .withPassword("test");

  @Test
  void commandOutcomeIsDurableAndPassingVerificationRequiresExactSuccessfulReceipts() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    SandboxCommandJournalStore journal = new SandboxCommandJournalStore(jdbc);

    String runId = "command-journal-run";
    UUID attemptId = UUID.randomUUID();
    UUID sandboxId = UUID.randomUUID();
    String candidate = "a".repeat(64);
    String contract = "b".repeat(64);
    String owner = "verification-jvm:test";
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        runId);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "build_protocol_version,verification_command_protocol_version) VALUES "
            + "(?,?,'T1',0,1,'BUILD_TASK','ADMITTED','/tmp/ws',repeat('c',64),?,"
            + "'SHARED_KERNEL_V2',2)",
        attemptId,
        runId,
        contract);
    jdbc.update(
        "INSERT INTO run_sandbox(id,run_id,requested_by_user_id,org_id,source_revision,"
            + "repository_set,state,isolation_profile,isolation_profile_version,runtime_owner,"
            + "lease_token,lease_expires_at,runtime_epoch,runtime_handle,image_digest,"
            + "config_digest,network_policy,secret_policy,expires_at,idempotency_key,ready_at,"
            + "purpose) VALUES (?,?, 'user-1','org-1',?,'[]'::jsonb,'READY',"
            + "'CONTAINER_STRICT_V1',1,?,7,NOW()+INTERVAL '10 minutes',3,'runtime-1',?,?,"
            + "'DENY_ALL','NO_HOST_SECRETS',NOW()+INTERVAL '1 hour',?,NOW(),'VERIFICATION')",
        sandboxId,
        runId,
        candidate,
        owner,
        "toolchain@sha256:" + "d".repeat(64),
        "sha256:" + "e".repeat(64),
        "sandbox:" + sandboxId);

    RunSandbox sandbox =
        new RunSandbox(
            sandboxId,
            runId,
            null,
            null,
            "user-1",
            "org-1",
            candidate,
            "[]",
            SandboxPurpose.VERIFICATION,
            SandboxState.READY,
            SandboxIsolationProfile.CONTAINER_STRICT_V1,
            1,
            owner,
            7,
            Instant.now().plus(Duration.ofMinutes(10)),
            3,
            Instant.now(),
            "runtime-1",
            "toolchain@sha256:" + "d".repeat(64),
            "sha256:" + "e".repeat(64),
            SandboxNetworkPolicy.DENY_ALL,
            SandboxSecretPolicy.NO_HOST_SECRETS,
            Instant.now().plus(Duration.ofHours(1)),
            null,
            null,
            null,
            null,
            null,
            0,
            null,
            SandboxRetentionState.RETAINED,
            null,
            "sandbox:" + sandboxId,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null);
    RunSandbox.Lease lease =
        new RunSandbox.Lease(sandboxId, owner, 7, Instant.now().plus(Duration.ofMinutes(10)));
    String before = "f".repeat(64);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO sandbox_command_execution(id,run_id,attempt_id,sandbox_id,state,"
                        + "candidate_generation,sandbox_purpose,runtime_owner_digest,lease_token,"
                        + "runtime_epoch,image_digest,config_digest,mount_digest,command_class,"
                        + "argv_digest,timeout_ms,idempotency_key,"
                        + "disposable_snapshot_before_sha256,dispatched_at) VALUES "
                        + "(?,?,?,?, 'DISPATCHED',?,'VERIFICATION',repeat('1',64),7,3,?,?,"
                        + "repeat('2',64),'FORGED_DIRECT_INSERT',repeat('3',64),1000,?,"
                        + "repeat('4',64),NOW())",
                    UUID.randomUUID(),
                    runId,
                    attemptId,
                    sandboxId,
                    candidate,
                    "toolchain@sha256:" + "d".repeat(64),
                    "sha256:" + "e".repeat(64),
                    "forged-direct-terminal:" + UUID.randomUUID()))
        .rootCause()
        .hasMessageContaining("must be inserted as bare ADMITTED intent");

    SandboxCommandJournalStore.Admission success =
        journal.admit(
            request(runId, attemptId, candidate, "command:success", before), sandbox, lease, 3);
    assertThat(state(jdbc, success.id())).isEqualTo("ADMITTED");
    journal.markDispatched(success, lease);
    assertThat(state(jdbc, success.id())).isEqualTo("DISPATCHED");
    UUID successReceipt =
        transaction.execute(
            ignored ->
                journal.settle(
                    success,
                    lease,
                    SandboxCommandJournalStore.TerminalOutcome.SUCCEEDED,
                    SandboxExecResult.completed(0, "green", "", false, Duration.ofSeconds(2)),
                    "1".repeat(64),
                    null));
    assertThat(successReceipt).isEqualTo(success.id());
    assertThat(state(jdbc, success.id())).isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForMap(
                "SELECT outcome,lease_token,runtime_epoch,candidate_generation"
                    + " FROM sandbox_command_receipt WHERE id=?",
                successReceipt))
        .containsEntry("outcome", "SUCCEEDED")
        .containsEntry("lease_token", 7L)
        .containsEntry("runtime_epoch", 3L)
        .containsEntry("candidate_generation", candidate);
    journal.requireSuccessfulReceipts(attemptId, candidate, List.of(successReceipt));

    SandboxCommandJournalStore.Admission forgedReceipt =
        journal.admit(
            request(runId, attemptId, candidate, "command:forged-receipt", "0".repeat(64)),
            sandbox,
            lease,
            3);
    journal.markDispatched(forgedReceipt, lease);
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    ignored -> {
                      jdbc.update(
                          "UPDATE sandbox_command_execution SET state='FAILED',"
                              + "disposable_snapshot_after_sha256=repeat('5',64),exit_code=1,"
                              + "output_digest=repeat('6',64),output_truncated=FALSE,duration_ms=1,"
                              + "settled_at=NOW() WHERE id=?",
                          forgedReceipt.id());
                      jdbc.update(
                          "INSERT INTO sandbox_command_receipt(id,command_id,receipt_version,"
                              + "run_id,attempt_id,sandbox_id,candidate_generation,sandbox_purpose,"
                              + "runtime_owner_digest,lease_token,runtime_epoch,image_digest,"
                              + "config_digest,mount_digest,command_class,argv_digest,timeout_ms,"
                              + "outcome,exit_code,output_digest,output_truncated,duration_ms,"
                              + "detail_digest,disposable_snapshot_before_sha256,"
                              + "disposable_snapshot_after_sha256,admitted_at,dispatched_at,settled_at)"
                              + " SELECT id,id,1,run_id,attempt_id,sandbox_id,"
                              + "candidate_generation,sandbox_purpose,runtime_owner_digest,"
                              + "lease_token,runtime_epoch,image_digest,config_digest,mount_digest,"
                              + "command_class,repeat('9',64),timeout_ms,state,exit_code,output_digest,"
                              + "output_truncated,duration_ms,detail_digest,"
                              + "disposable_snapshot_before_sha256,"
                              + "disposable_snapshot_after_sha256,admitted_at,dispatched_at,settled_at"
                              + " FROM sandbox_command_execution WHERE id=?",
                          forgedReceipt.id());
                    }))
        .rootCause()
        .hasMessageContaining("receipt must exactly match terminal command state");
    assertThat(state(jdbc, forgedReceipt.id())).isEqualTo("DISPATCHED");
    transaction.execute(
        ignored ->
            journal.settle(
                forgedReceipt,
                lease,
                SandboxCommandJournalStore.TerminalOutcome.FAILED,
                SandboxExecResult.completed(1, "", "failed", false, Duration.ofMillis(1)),
                "5".repeat(64),
                null));

    UUID highLevelReceipt = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO workspace_generation_verification_receipt(id,attempt_id,receipt_version,"
            + "candidate_generation,task_contract_digest,verifier_kind,command_source,"
            + "command_digest,outcome,build_passed,tests_executed,tests_passed,tests_failed,"
            + "tests_errors,tests_skipped,evidence_json,evidence_digest,started_at,completed_at,command_receipt_ids,execution_boundary)"
            + " VALUES (?,?,1,?,?,'BUILD_ONLY','SERVER_RESOLVED_CONTRACT',"
            + "repeat('2',64),'PASSED',TRUE,FALSE,0,0,0,0,'{}'::jsonb,repeat('3',64),NOW(),NOW(),"
            + "?::jsonb,'VERIFICATION_SANDBOX')",
        highLevelReceipt,
        attemptId,
        candidate,
        contract,
        "[\"" + successReceipt + "\"]");

    SandboxCommandJournalStore.Admission abandonedBeforeDispatch =
        journal.admit(
            request(runId, attemptId, candidate, "command:abandoned", "3".repeat(64)),
            sandbox,
            lease,
            3);
    SandboxCommandJournalStore.Admission unknown =
        journal.admit(
            request(runId, attemptId, candidate, "command:unknown", "4".repeat(64)),
            sandbox,
            lease,
            3);
    journal.markDispatched(unknown, lease);
    jdbc.update(
        "UPDATE run_sandbox SET lease_expires_at=NOW()-INTERVAL '1 second' WHERE id=?", sandboxId);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    ignored ->
                        journal.settle(
                            unknown,
                            lease,
                            SandboxCommandJournalStore.TerminalOutcome.SUCCEEDED,
                            SandboxExecResult.completed(
                                0, "late green", "", false, Duration.ofMillis(1)),
                            "7".repeat(64),
                            null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SANDBOX_COMMAND_SETTLEMENT_FENCE_REJECTED");
    SandboxCommandJournalStore.RecoveryResult recovered =
        transaction.execute(ignored -> journal.recoverStaleCommands(10));
    assertThat(recovered.admittedUnavailable()).isEqualTo(1);
    assertThat(recovered.dispatchedIndeterminate()).isEqualTo(1);
    assertThat(state(jdbc, abandonedBeforeDispatch.id())).isEqualTo("UNAVAILABLE");
    assertThat(state(jdbc, unknown.id())).isEqualTo("INDETERMINATE");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM sandbox_command_receipt WHERE id=?",
                String.class,
                unknown.id()))
        .isEqualTo("INDETERMINATE");
    assertThat(
            jdbc.queryForObject(
                "SELECT outcome FROM sandbox_command_receipt WHERE id=?",
                String.class,
                abandonedBeforeDispatch.id()))
        .isEqualTo("UNAVAILABLE");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO workspace_generation_verification_receipt(id,attempt_id,"
                        + "receipt_version,candidate_generation,task_contract_digest,verifier_kind,"
                        + "command_source,command_digest,outcome,build_passed,tests_executed,"
                        + "tests_passed,tests_failed,tests_errors,tests_skipped,evidence_json,evidence_digest,started_at,completed_at,command_receipt_ids,execution_boundary)"
                        + " VALUES "
                        + "(?,?,1,?,?,'BUILD_ONLY','SERVER_RESOLVED_CONTRACT',repeat('5',64),"
                        + "'PASSED',TRUE,FALSE,0,0,0,0,'{}'::jsonb,repeat('6',64),NOW(),NOW(),"
                        + "?::jsonb,'VERIFICATION_SANDBOX')",
                    UUID.randomUUID(),
                    attemptId,
                    candidate,
                    contract,
                    "[\"" + unknown.id() + "\"]"))
        .rootCause()
        .hasMessageContaining("requires all commands to succeed");
    assertThatThrownBy(
            () ->
                insertPassingWorkspaceReceipt(
                    jdbc, UUID.randomUUID(), attemptId, candidate, contract, "[]"))
        .rootCause()
        .hasMessageContaining("requires physical command receipts");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE sandbox_command_receipt SET output_digest=repeat('9',64) WHERE id=?",
                    successReceipt))
        .rootCause()
        .hasMessageContaining("receipts are immutable");

    // Expand/contract compatibility: a previous V388 binary omits the new attempt column and the
    // new receipt JSON. Database defaults pin that writer to protocol v1, so its in-flight attempt
    // can finish during a rolling deploy. New admissions above are explicitly v2 and fail closed.
    String previousRun = "command-journal-previous-writer";
    UUID previousAttempt = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD','SHARED_KERNEL_V2')",
        previousRun);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "build_protocol_version) VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED',"
            + "'/tmp/previous',repeat('7',64),?,'SHARED_KERNEL_V2')",
        previousAttempt,
        previousRun,
        contract);
    assertThat(
            jdbc.queryForObject(
                "SELECT verification_command_protocol_version FROM workspace_generation_attempt"
                    + " WHERE id=?",
                Short.class,
                previousAttempt))
        .isEqualTo((short) 1);
    insertPassingWorkspaceReceipt(
        jdbc, UUID.randomUUID(), previousAttempt, candidate, contract, "[]");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE workspace_generation_attempt SET"
                        + " verification_command_protocol_version=2 WHERE id=?",
                    previousAttempt))
        .rootCause()
        .hasMessageContaining("protocol identity is immutable");
  }

  private static SandboxCommandJournalStore.AdmissionRequest request(
      String runId,
      UUID attemptId,
      String candidate,
      String idempotencyKey,
      String beforeSnapshot) {
    return new SandboxCommandJournalStore.AdmissionRequest(
        runId,
        attemptId,
        candidate,
        SandboxCommandJournalStore.mountDigest("/owned/workspace", "/workspace"),
        "JVM_BUILD_OR_TEST",
        SandboxCommandJournalStore.argvDigest(List.of("mvn", "test")),
        Duration.ofMinutes(5),
        idempotencyKey,
        beforeSnapshot);
  }

  private static String state(JdbcTemplate jdbc, UUID id) {
    return jdbc.queryForObject(
        "SELECT state FROM sandbox_command_execution WHERE id=?", String.class, id);
  }

  private static void insertPassingWorkspaceReceipt(
      JdbcTemplate jdbc,
      UUID receiptId,
      UUID attemptId,
      String candidate,
      String contract,
      String commandReceiptIds) {
    jdbc.update(
        "INSERT INTO workspace_generation_verification_receipt(id,attempt_id,receipt_version,"
            + "candidate_generation,task_contract_digest,verifier_kind,command_source,"
            + "command_digest,outcome,build_passed,tests_executed,tests_passed,tests_failed,"
            + "tests_errors,tests_skipped,evidence_json,evidence_digest,started_at,completed_at,command_receipt_ids,execution_boundary)"
            + " VALUES (?,?,1,?,?,'BUILD_ONLY','SERVER_RESOLVED_CONTRACT',"
            + "repeat('8',64),'PASSED',TRUE,FALSE,0,0,0,0,'{}'::jsonb,repeat('9',64),NOW(),NOW(),"
            + "?::jsonb,'VERIFICATION_SANDBOX')",
        receiptId,
        attemptId,
        candidate,
        contract,
        commandReceiptIds);
  }
}
