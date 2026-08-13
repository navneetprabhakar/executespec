package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import com.navneet.executespec.integration.PostgresIntegrationBase;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.sandbox.RunSandbox;
import com.navneet.executespec.sandbox.SandboxCommandJournalStore;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandbox.SandboxIsolationProfile;
import com.navneet.executespec.sandbox.SandboxNetworkPolicy;
import com.navneet.executespec.sandbox.SandboxPurpose;
import com.navneet.executespec.sandbox.SandboxRetentionState;
import com.navneet.executespec.sandbox.SandboxSecretPolicy;
import com.navneet.executespec.sandbox.SandboxState;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring Data/PostgreSQL proof for the pre-dispatch BUILD crash windows. */
class WorkspaceGenerationPreDispatchPostgresTest extends PostgresIntegrationBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired WorkspaceGenerationAttemptRepository attempts;
  @Autowired WorkspaceGenerationAdmissionStore admissionStore;
  @Autowired WorkspaceGenerationLifecycle lifecycle;
  @Autowired WorkspaceGenerationRecoveryService recoveryService;
  @Autowired RunLifecycleService runLifecycleService;
  @Autowired WorkspaceGenerationTerminalReconciler terminalReconciler;
  @Autowired BuildEnvironmentReceiptStore environmentReceipts;
  @Autowired SandboxCommandJournalStore commandJournal;
  @Autowired PipelineRunRepository runs;
  @Autowired PlatformTransactionManager transactionManager;
  @TempDir Path temporaryDirectory;

  @Test
  void modifyingAuthorityQueryRequiresAnExplicitTransactionBoundary() {
    Fixture fixture = insertOperationClaimed("tx-boundary");

    assertThatThrownBy(
            () ->
                attempts.acquireAuthority(
                    fixture.attemptId(),
                    UUID.randomUUID(),
                    fixture.operationId(),
                    1,
                    fixture.operationToken(),
                    WorkspaceGenerationState.OPERATION_CLAIMED,
                    WorkspaceGenerationState.AUTHORITY_ACQUIRED,
                    java.time.Instant.now()))
        .isInstanceOf(InvalidDataAccessApiUsageException.class)
        .hasMessageContaining("No EntityManager with actual transaction available");

    assertThat(state(fixture.attemptId())).isEqualTo("OPERATION_CLAIMED");
  }

  @Test
  void admissionStoreCommitsAuthorityAcquisitionInItsOwnTransaction() {
    Fixture fixture = insertOperationClaimed("transactional-store");
    UUID workspaceToken = UUID.randomUUID();
    insertAuthority(fixture.runId(), "BUILD_GENERATION", workspaceToken);

    admissionStore.acquireAuthority(
        fixture.attemptId(), workspaceToken, fixture.operationId(), 1, fixture.operationToken());

    assertThat(state(fixture.attemptId())).isEqualTo("AUTHORITY_ACQUIRED");
  }

  @Test
  void normalAdmissionReachesPreparedAndReplacementRecoveryConvergesIt() throws Exception {
    Path workspace = gitWorkspace("normal-prepare");
    Fixture fixture = insertOperationClaimed("normal-prepare", workspace);

    WorkspaceGenerationLifecycle.Prepared prepared =
        lifecycle.prepareBuild(
            fixture.attemptId(), workspace, fixture.operationId(), 1, fixture.operationToken());

    assertThat(state(fixture.attemptId())).isEqualTo("PREPARED");
    prepared.lease().close();
    recoveryService.recoverRunOrThrow(fixture.runId());
    assertRecovered(fixture);
  }

  @Test
  void cancellationRacingWithMutationAuthorityConvergesAfterOwnerUnwinds() throws Exception {
    Fixture fixture = insertOperationClaimed("cancel-mutation-race", null, "SHARED_KERNEL_V2");
    Path workspace = gitWorkspace(canonicalWorkspace(fixture.runId()));
    WorkspaceGenerationLifecycle.Prepared prepared =
        lifecycle.prepareBuild(
            fixture.attemptId(), workspace, fixture.operationId(), 1, fixture.operationToken());
    lifecycle.markMutating(prepared);

    assertThat(runLifecycleService.cancel(fixture.runId())).isTrue();
    assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
    assertThat(state(fixture.attemptId())).isEqualTo("MUTATING");
    assertThatThrownBy(() -> lifecycle.assertMutationDispatch(prepared))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("WORKSPACE_GENERATION_DISPATCH_AUTHORITY_LOST");

    prepared.lease().close();
    assertThat(recoveryService.convergeCancelledRun(fixture.runId()))
        .isEqualTo(WorkspaceGenerationRecoveryService.CancellationConvergence.CONVERGED);

    assertThat(
            jdbc.queryForMap(
                "SELECT a.state,a.task_transition_state,a.operation_transition_state,t.status AS"
                    + " task_status,o.status AS operation_status,p.status AS run_status FROM"
                    + " workspace_generation_attempt a JOIN agent_task t ON t.task_id=? JOIN"
                    + " llm_execution_operation o ON o.id=a.llm_operation_id JOIN pipeline_run p ON"
                    + " p.run_id=a.run_id WHERE a.id=?",
                fixture.runId().substring(0, 8) + "_T1",
                fixture.attemptId()))
        .containsEntry("state", "ROLLED_BACK")
        .containsEntry("task_transition_state", "COMPLETED")
        .containsEntry("operation_transition_state", "COMPLETED")
        .containsEntry("task_status", "CANCELLED")
        .containsEntry("operation_status", "FAILED")
        .containsEntry("run_status", "CANCELLED");
    assertThat(attempts.findFirstUnresolvedByRunId(fixture.runId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_workspace_authority WHERE run_id=?",
                Integer.class,
                fixture.runId()))
        .isZero();
  }

  @Test
  void cancellationConvergesEveryPostDispatchGenerationStateAfterOwnerUnwinds() throws Exception {
    for (WorkspaceGenerationState target :
        java.util.List.of(
            WorkspaceGenerationState.MUTATING,
            WorkspaceGenerationState.VERIFYING,
            WorkspaceGenerationState.VERIFIED,
            WorkspaceGenerationState.CHECKPOINTING)) {
      Fixture fixture =
          insertOperationClaimed("cancel-" + target.name().toLowerCase(), null, "SHARED_KERNEL_V2");
      Path workspace = gitWorkspace(canonicalWorkspace(fixture.runId()));
      WorkspaceGenerationLifecycle.Prepared prepared =
          lifecycle.prepareBuild(
              fixture.attemptId(), workspace, fixture.operationId(), 1, fixture.operationToken());
      lifecycle.markMutating(prepared);
      Files.writeString(workspace.resolve("README.md"), "rejected candidate\n");
      if (target != WorkspaceGenerationState.MUTATING) {
        recordPassedEnvironment(fixture.attemptId(), "INITIAL_PROBE");
        recordPassedEnvironment(fixture.attemptId(), "WORKER");
        String candidate = lifecycle.beginVerification(prepared);
        if (target == WorkspaceGenerationState.VERIFIED
            || target == WorkspaceGenerationState.CHECKPOINTING) {
          UUID commandReceipt = recordPassedCommand(fixture, candidate);
          recordPassedEnvironment(fixture.attemptId(), "FINAL_VERIFICATION");
          Instant started = Instant.now();
          lifecycle.recordVerification(
              prepared,
              new WorkspaceGenerationLifecycle.Verification(
                  "BUILD_AND_TEST",
                  "{\"build\":\"server-resolved\"}",
                  "PASSED",
                  null,
                  null,
                  "DISPOSABLE_EXACT_GENERATION",
                  true,
                  true,
                  2,
                  0,
                  0,
                  0,
                  "{\"commandReceiptIds\":[\"" + commandReceipt + "\"]}",
                  "TEST",
                  "TEST",
                  started,
                  started.plusSeconds(1)));
        }
      }
      if (target == WorkspaceGenerationState.CHECKPOINTING) {
        lifecycle.beginCheckpoint(prepared);
      }
      assertThat(state(fixture.attemptId())).isEqualTo(target.name());

      assertThat(runLifecycleService.cancel(fixture.runId())).isTrue();
      assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
      assertThat(taskStatus(fixture.runId())).isEqualTo("CANCELLED");
      assertThat(state(fixture.attemptId())).isEqualTo(target.name());

      prepared.lease().close();
      assertThat(recoveryService.convergeCancelledRun(fixture.runId()))
          .isEqualTo(WorkspaceGenerationRecoveryService.CancellationConvergence.CONVERGED);
      assertRecovered(fixture);
      assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
      assertThat(taskStatus(fixture.runId())).isEqualTo("CANCELLED");
      assertThat(operationStatus(fixture)).isEqualTo("FAILED");
      assertThat(Files.readString(workspace.resolve("README.md"))).isEqualTo("accepted baseline\n");
    }
  }

  @Test
  void cancellationTakesOverRollingBackAndRetryableRecoveryFailedWithoutWaitingForBackoff()
      throws Exception {
    for (WorkspaceGenerationState target :
        java.util.List.of(
            WorkspaceGenerationState.ROLLING_BACK, WorkspaceGenerationState.RECOVERY_FAILED)) {
      Fixture fixture =
          insertOperationClaimed("cancel-" + target.name().toLowerCase(), null, "SHARED_KERNEL_V2");
      Path workspace = gitWorkspace(canonicalWorkspace(fixture.runId()));
      WorkspaceGenerationLifecycle.Prepared prepared =
          lifecycle.prepareBuild(
              fixture.attemptId(), workspace, fixture.operationId(), 1, fixture.operationToken());
      Files.writeString(workspace.resolve("README.md"), "rejected candidate\n");
      prepared.lease().close();

      UUID recoveryToken = UUID.randomUUID();
      insertAuthority(fixture.runId(), "RECOVERY", recoveryToken);
      assertThat(
              inTransaction(
                  () ->
                      attempts.claimRecovery(
                          fixture.attemptId(),
                          WorkspaceGenerationState.PREPARED,
                          WorkspaceGenerationState.ROLLING_BACK,
                          recoveryToken,
                          UUID.randomUUID(),
                          java.time.Instant.now())))
          .isOne();
      if (target == WorkspaceGenerationState.RECOVERY_FAILED) {
        java.time.Instant now = java.time.Instant.now();
        assertThat(
                inTransaction(
                    () ->
                        attempts.markRecoveryRetry(
                            fixture.attemptId(),
                            recoveryToken,
                            "ROLLBACK_PROOF_FAILED",
                            "synthetic retryable failure",
                            WorkspaceGenerationState.ROLLING_BACK,
                            WorkspaceGenerationState.RECOVERY_FAILED,
                            3,
                            now.plusSeconds(3600),
                            now)))
            .isOne();
      }
      jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", fixture.runId());
      assertThat(state(fixture.attemptId())).isEqualTo(target.name());

      assertThat(runLifecycleService.cancel(fixture.runId())).isTrue();
      assertThat(recoveryService.convergeCancelledRun(fixture.runId()))
          .isEqualTo(WorkspaceGenerationRecoveryService.CancellationConvergence.CONVERGED);

      assertRecovered(fixture);
      assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
      assertThat(taskStatus(fixture.runId())).isEqualTo("CANCELLED");
      assertThat(operationStatus(fixture)).isEqualTo("FAILED");
      assertThat(Files.readString(workspace.resolve("README.md"))).isEqualTo("accepted baseline\n");
    }
  }

  @Test
  void cancellationConvergesEveryPreDispatchGenerationState() throws Exception {
    Fixture admitted = insertAdmitted("cancel-admitted");
    cancelAndAssertConverged(admitted);

    Fixture claimed = insertOperationClaimed("cancel-operation-claimed");
    cancelAndAssertConverged(claimed);

    Fixture acquired = insertOperationClaimed("cancel-authority-acquired");
    UUID workspaceToken = UUID.randomUUID();
    insertAuthority(acquired.runId(), "BUILD_GENERATION", workspaceToken);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='AUTHORITY_ACQUIRED',"
            + " workspace_lease_token=?::uuid WHERE id=?",
        workspaceToken.toString(),
        acquired.attemptId());
    jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", acquired.runId());
    cancelAndAssertConverged(acquired);

    Path workspace = gitWorkspace("cancel-prepared");
    Fixture preparedFixture = insertOperationClaimed("cancel-prepared", workspace);
    WorkspaceGenerationLifecycle.Prepared prepared =
        lifecycle.prepareBuild(
            preparedFixture.attemptId(),
            workspace,
            preparedFixture.operationId(),
            1,
            preparedFixture.operationToken());
    prepared.lease().close();
    cancelAndAssertConverged(preparedFixture);
  }

  @Test
  void cancellationAfterRecoveryStillTerminalizesTheReopenedTask() {
    Fixture fixture = insertOperationClaimed("recovery-before-cancel");

    recoveryService.recoverRunOrThrow(fixture.runId());
    assertRecovered(fixture);
    assertThat(taskStatus(fixture.runId())).isEqualTo("PENDING");

    assertThat(runLifecycleService.cancel(fixture.runId())).isTrue();

    assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
    assertThat(taskStatus(fixture.runId())).isEqualTo("CANCELLED");
    assertThat(attempts.findFirstUnresolvedByRunId(fixture.runId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_workspace_authority WHERE run_id=?",
                Integer.class,
                fixture.runId()))
        .isZero();
  }

  @Test
  void parentCancellationRollsBackWhenTaskConvergenceCannotCommit() {
    Fixture fixture = insertAdmitted("atomic-cancel-failure");
    jdbc.execute(
        """
        CREATE OR REPLACE FUNCTION test_reject_task_cancel() RETURNS trigger AS $$
        BEGIN
          IF OLD.run_id = '%s' AND NEW.status = 'CANCELLED' THEN
            RAISE EXCEPTION 'forced task cancellation failure';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """
            .formatted(fixture.runId()));
    jdbc.execute(
        "CREATE TRIGGER test_reject_task_cancel BEFORE UPDATE ON agent_task "
            + "FOR EACH ROW EXECUTE FUNCTION test_reject_task_cancel()");
    try {
      assertThatThrownBy(() -> runLifecycleService.cancel(fixture.runId()))
          .hasStackTraceContaining("forced task cancellation failure");
      assertThat(runStatus(fixture.runId())).isEqualTo("WORKING");
      assertThat(taskStatus(fixture.runId())).isEqualTo("WORKING");
      assertThat(state(fixture.attemptId())).isEqualTo("ADMITTED");
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS test_reject_task_cancel ON agent_task");
      jdbc.execute("DROP FUNCTION IF EXISTS test_reject_task_cancel()");
    }
  }

  @Test
  void recoveryServiceConvergesEveryPreDispatchCrashIncludingUnboundAdmitted() {
    Fixture admitted = insertAdmitted("recover-admitted");
    recoveryService.recoverRunOrThrow(admitted.runId());
    assertRecovered(admitted);

    Fixture claimed = insertOperationClaimed("recover-claimed");
    recoveryService.recoverRunOrThrow(claimed.runId());
    assertRecovered(claimed);

    Fixture acquired = insertOperationClaimed("recover-authority");
    UUID workspaceToken = UUID.randomUUID();
    insertAuthority(acquired.runId(), "BUILD_GENERATION", workspaceToken);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='AUTHORITY_ACQUIRED',"
            + " workspace_lease_token=?::uuid WHERE id=?",
        workspaceToken.toString(),
        acquired.attemptId());
    jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", acquired.runId());
    recoveryService.recoverRunOrThrow(acquired.runId());
    assertRecovered(acquired);
  }

  @Test
  void claimedAndAuthorityAcquiredCrashWindowsCanDurablyRollBack() {
    for (String crashState : new String[] {"OPERATION_CLAIMED", "AUTHORITY_ACQUIRED"}) {
      Fixture fixture = insertOperationClaimed("rollback-" + crashState.toLowerCase());
      UUID recoveryToken = UUID.randomUUID();
      if ("AUTHORITY_ACQUIRED".equals(crashState)) {
        UUID workspaceToken = UUID.randomUUID();
        insertAuthority(fixture.runId(), "BUILD_GENERATION", workspaceToken);
        jdbc.update(
            "UPDATE workspace_generation_attempt SET state='AUTHORITY_ACQUIRED',"
                + " workspace_lease_token=?::uuid WHERE id=?",
            workspaceToken.toString(),
            fixture.attemptId());
        jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", fixture.runId());
      }
      insertAuthority(fixture.runId(), "RECOVERY", recoveryToken);

      assertThat(
              jdbc.update(
                  "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
                      + " recovery_lease_token=?::uuid,recovery_started_at=NOW()"
                      + " WHERE id=? AND state=?",
                  recoveryToken.toString(),
                  fixture.attemptId(),
                  crashState))
          .isOne();
      assertThat(
              jdbc.update(
                  "UPDATE workspace_generation_attempt SET state='ROLLED_BACK',"
                      + " failure_code='PRE_DISPATCH_ATTEMPT_RECOVERED',"
                      + " failure_reason='Recovered before provider or product mutation dispatch',"
                      + " resolved_at=NOW(),task_transition_kind='REOPEN_PENDING',"
                      + " task_transition_state='PENDING',operation_transition_target='FAILED',"
                      + " operation_transition_state='PENDING'"
                      + " WHERE id=? AND state='ROLLING_BACK' AND recovery_lease_token=?::uuid",
                  fixture.attemptId(),
                  recoveryToken.toString()))
          .isOne();
      assertThat(state(fixture.attemptId())).isEqualTo("ROLLED_BACK");
    }
  }

  @Test
  void thirdFailedRecoveryIsQuarantinedAndConvergesTaskOperationAndParentRun() {
    Fixture fixture = insertOperationClaimed("bounded-exhaustion");
    UUID supportId = UUID.randomUUID();
    WorkspaceGenerationState expected = WorkspaceGenerationState.OPERATION_CLAIMED;
    for (int recoveryAttempt = 1;
        recoveryAttempt <= WorkspaceGenerationRecoveryService.MAX_RECOVERY_ATTEMPTS;
        recoveryAttempt++) {
      UUID recoveryToken = UUID.randomUUID();
      insertAuthority(fixture.runId(), "RECOVERY", recoveryToken);
      WorkspaceGenerationState expectedState = expected;
      assertThat(
              inTransaction(
                  () ->
                      attempts.claimRecovery(
                          fixture.attemptId(),
                          expectedState,
                          WorkspaceGenerationState.ROLLING_BACK,
                          recoveryToken,
                          supportId,
                          java.time.Instant.now())))
          .isOne();
      if (recoveryAttempt < WorkspaceGenerationRecoveryService.MAX_RECOVERY_ATTEMPTS) {
        assertThat(
                inTransaction(
                    () ->
                        attempts.markRecoveryRetry(
                            fixture.attemptId(),
                            recoveryToken,
                            "ROLLBACK_PROOF_FAILED",
                            "deterministic recovery failure",
                            WorkspaceGenerationState.ROLLING_BACK,
                            WorkspaceGenerationState.RECOVERY_FAILED,
                            WorkspaceGenerationRecoveryService.MAX_RECOVERY_ATTEMPTS,
                            java.time.Instant.now().plusSeconds(1),
                            java.time.Instant.now())))
            .isOne();
        expected = WorkspaceGenerationState.RECOVERY_FAILED;
      } else {
        assertThat(
                inTransaction(
                    () ->
                        attempts.markRecoveryExhausted(
                            fixture.attemptId(),
                            recoveryToken,
                            "ROLLBACK_PROOF_FAILED",
                            "deterministic recovery failure",
                            WorkspaceGenerationState.ROLLING_BACK,
                            WorkspaceGenerationState.RECOVERY_FAILED,
                            WorkspaceGenerationRecoveryService.MAX_RECOVERY_ATTEMPTS,
                            java.time.Instant.now())))
            .isOne();
      }
      jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", fixture.runId());
    }

    terminalReconciler.reconcileRun(fixture.runId());
    assertThat(
            runs.claimTerminalTransitionWithReasons(
                fixture.runId(),
                "FAILED",
                "WORKSPACE_GENERATION_RECOVERY_EXHAUSTED",
                "support id " + supportId,
                java.time.Instant.now()))
        .isOne();

    assertThat(
            jdbc.queryForMap(
                "SELECT a.state,a.recovery_attempt_count,a.resolved_at IS NOT NULL AS"
                    + " resolved,a.task_transition_state,a.operation_transition_state,t.status AS"
                    + " task_status,o.status AS operation_status,p.status AS run_status FROM"
                    + " workspace_generation_attempt a JOIN agent_task t ON t.task_id=? JOIN"
                    + " llm_execution_operation o ON o.id=a.llm_operation_id JOIN pipeline_run p ON"
                    + " p.run_id=a.run_id WHERE a.id=?",
                fixture.runId().substring(0, 8) + "_T1",
                fixture.attemptId()))
        .containsEntry("state", "RECOVERY_FAILED")
        .containsEntry("recovery_attempt_count", 3)
        .containsEntry("resolved", true)
        .containsEntry("task_transition_state", "COMPLETED")
        .containsEntry("operation_transition_state", "COMPLETED")
        .containsEntry("task_status", "PARTIAL")
        .containsEntry("operation_status", "FAILED")
        .containsEntry("run_status", "FAILED");
  }

  private Fixture insertOperationClaimed(String suffix) {
    return insertOperationClaimed(suffix, Path.of("/tmp/predispatch"));
  }

  private Fixture insertOperationClaimed(String suffix, Path workspace) {
    return insertOperationClaimed(suffix, workspace, "SHARED_KERNEL_V2");
  }

  private Fixture insertOperationClaimed(String suffix, Path workspace, String protocol) {
    Fixture fixture = insertAdmitted(suffix, workspace, protocol);
    String contextId = "ctx-" + UUID.randomUUID();
    String operationId = "op-" + UUID.randomUUID();
    UUID operationToken = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO llm_execution_context("
            + "id,context_type,feature,source_type,source_id,correlation_id,scope_mode,isolation_key,"
            + "key_source_mode,allowed_providers_json,allowed_provider_models_json,"
            + "provider_key_sources_json,model_overrides_json,routing_mode,route_snapshot_version,status,max_attempts,idempotency_key,request_fingerprint)"
            + " VALUES (?,'PIPELINE_RUN','worker','PIPELINE',?,'predispatch-postgres-test',"
            + "'PLATFORM_INTERNAL','platform:internal','SYSTEM','[]'::jsonb,'{}'::jsonb,"
            + "'{}'::jsonb,'{}'::jsonb,'PROVIDER_PREFERRED',1,'QUEUED',4,?,repeat('7',64))",
        contextId,
        fixture.runId(),
        "idem-" + UUID.randomUUID());
    jdbc.update(
        "INSERT INTO llm_execution_operation(id,llm_execution_context_id,operation_key,"
            + "operation_kind,invocation,operation_fingerprint,status,attempt,max_attempts,"
            + "lease_owner,lease_token,lease_expires_at,heartbeat_at,started_at,created_at,updated_at)"
            + " VALUES ("
            + " ?,?,?,'RUN_BUILD_TASK',1,repeat('8',64),'RUNNING',1,4,'test-owner',?::uuid,NOW()+INTERVAL"
            + " '10 minutes',NOW(),NOW(),NOW(),NOW())",
        operationId,
        contextId,
        "pipeline-run:" + fixture.runId() + ":build-task:T1",
        operationToken.toString());
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='OPERATION_CLAIMED',llm_operation_id=?,"
            + "llm_attempt_number=1,llm_lease_token=?::uuid WHERE id=?",
        operationId,
        operationToken.toString(),
        fixture.attemptId());
    return new Fixture(fixture.runId(), fixture.attemptId(), operationId, operationToken);
  }

  private Fixture insertAdmitted(String suffix) {
    return insertAdmitted(suffix, Path.of("/tmp/predispatch"));
  }

  private Fixture insertAdmitted(String suffix, Path workspace) {
    return insertAdmitted(suffix, workspace, "SHARED_KERNEL_V2");
  }

  private Fixture insertAdmitted(String suffix, Path workspace, String protocol) {
    String runId = UUID.randomUUID().toString().substring(0, 8) + "-predispatch-" + suffix;
    Path persistedWorkspace = workspace == null ? canonicalWorkspace(runId) : workspace;
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version)"
            + " VALUES (?,'WORKING','BUILD',?)",
        runId,
        protocol);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES (?,?,'WORKING',0,NOW())",
        runId.substring(0, 8) + "_T1",
        runId);
    jdbc.update(
        "INSERT INTO workspace_generation_attempt(id,run_id,task_id,task_index,attempt_number,"
            + "attempt_kind,state,workspace_root,authority_digest,task_contract_digest,"
            + "build_protocol_version,verification_command_protocol_version,created_at,updated_at)"
            + " VALUES (?,?,'T1',0,1,'BUILD_TASK','ADMITTED',?,repeat('1',64),"
            + "repeat('2',64),?,?,NOW(),NOW())",
        attemptId,
        runId,
        persistedWorkspace.toAbsolutePath().normalize().toString(),
        protocol,
        "SHARED_KERNEL_V2".equals(protocol) ? 2 : 1);
    return new Fixture(runId, attemptId, null, null);
  }

  private void assertRecovered(Fixture fixture) {
    assertThat(
            jdbc.queryForMap(
                "SELECT state,task_transition_state,operation_transition_state,resolved_at IS NOT"
                    + " NULL AS resolved FROM workspace_generation_attempt WHERE id=?",
                fixture.attemptId()))
        .containsEntry("state", "ROLLED_BACK")
        .containsEntry("task_transition_state", "COMPLETED")
        .containsEntry("operation_transition_state", "COMPLETED")
        .containsEntry("resolved", true);
    assertThat(attempts.findFirstUnresolvedByRunId(fixture.runId())).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_workspace_authority WHERE run_id=?",
                Integer.class,
                fixture.runId()))
        .isZero();
  }

  private void cancelAndAssertConverged(Fixture fixture) {
    assertThat(runLifecycleService.cancel(fixture.runId())).isTrue();
    assertThat(runStatus(fixture.runId())).isEqualTo("CANCELLED");
    assertThat(taskStatus(fixture.runId())).isEqualTo("CANCELLED");
    assertRecovered(fixture);
  }

  private Path gitWorkspace(String name) throws Exception {
    return gitWorkspace(temporaryDirectory.resolve(name));
  }

  private Path gitWorkspace(Path workspace) throws Exception {
    Files.createDirectories(workspace);
    try (org.eclipse.jgit.api.Git git =
        org.eclipse.jgit.api.Git.init().setDirectory(workspace.toFile()).call()) {
      Files.writeString(workspace.resolve("README.md"), "accepted baseline\n");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("baseline")
          .setAuthor("ExecuteSpec Test", "test@executespec.ai")
          .setCommitter("ExecuteSpec Test", "test@executespec.ai")
          .call();
    }
    return workspace;
  }

  private static Path canonicalWorkspace(String runId) {
    return Path.of(System.getProperty("java.io.tmpdir"), "it-workspace", runId);
  }

  private String runStatus(String runId) {
    return jdbc.queryForObject(
        "SELECT status FROM pipeline_run WHERE run_id=?", String.class, runId);
  }

  private String taskStatus(String runId) {
    return jdbc.queryForObject("SELECT status FROM agent_task WHERE run_id=?", String.class, runId);
  }

  private String operationStatus(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT status FROM llm_execution_operation WHERE id=?",
        String.class,
        fixture.operationId());
  }

  private void insertAuthority(String runId, String authorityType, UUID ownerToken) {
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,?,?::uuid,NOW()+INTERVAL '10 minutes',NOW())",
        runId,
        authorityType,
        ownerToken.toString());
  }

  private void recordPassedEnvironment(UUID attemptId, String phase) {
    BuildEnvironmentPlan plan =
        new BuildEnvironmentPlan(
            1,
            "toolchain@sha256:" + "f".repeat(64),
            "DENY_ALL",
            "IMAGE_PRESEEDED_OFFLINE_ONLY",
            java.util.List.of(),
            "1".repeat(64),
            "2".repeat(64));
    String evidence = "{}";
    environmentReceipts.recordMaterialization(
        attemptId,
        phase,
        plan,
        "PASSED",
        evidence,
        org.apache.commons.codec.digest.DigestUtils.sha256Hex(evidence));
  }

  private UUID recordPassedCommand(Fixture fixture, String candidate) {
    UUID sandboxId = UUID.randomUUID();
    String owner = "verification-jvm:" + fixture.runId();
    String image = "toolchain@sha256:" + "d".repeat(64);
    String config = "sha256:" + "e".repeat(64);
    jdbc.update(
        "INSERT INTO run_sandbox(id,run_id,requested_by_user_id,org_id,source_revision,"
            + "repository_set,state,isolation_profile,isolation_profile_version,runtime_owner,"
            + "lease_token,lease_expires_at,runtime_epoch,runtime_handle,image_digest,"
            + "config_digest,network_policy,secret_policy,expires_at,idempotency_key,ready_at,"
            + "purpose) VALUES (?,?,'user-1','org-1',?,'[]'::jsonb,'READY',"
            + "'CONTAINER_STRICT_V1',1,?,7,NOW()+INTERVAL '10 minutes',3,'runtime-1',?,?,"
            + "'DENY_ALL','NO_HOST_SECRETS',NOW()+INTERVAL '1 hour',?,NOW(),'VERIFICATION')",
        sandboxId,
        fixture.runId(),
        candidate,
        owner,
        image,
        config,
        "sandbox:" + sandboxId);
    Instant now = Instant.now();
    RunSandbox sandbox =
        new RunSandbox(
            sandboxId,
            fixture.runId(),
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
            now.plus(Duration.ofMinutes(10)),
            3,
            now,
            "runtime-1",
            image,
            config,
            SandboxNetworkPolicy.DENY_ALL,
            SandboxSecretPolicy.NO_HOST_SECRETS,
            now.plus(Duration.ofHours(1)),
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
            now,
            now,
            now,
            null,
            null);
    RunSandbox.Lease lease =
        new RunSandbox.Lease(sandboxId, owner, 7, now.plus(Duration.ofMinutes(10)));
    SandboxCommandJournalStore.Admission admission =
        commandJournal.admit(
            new SandboxCommandJournalStore.AdmissionRequest(
                fixture.runId(),
                fixture.attemptId(),
                candidate,
                SandboxCommandJournalStore.mountDigest("/owned/workspace", "/workspace"),
                "JVM_BUILD_OR_TEST",
                SandboxCommandJournalStore.argvDigest(java.util.List.of("mvn", "test")),
                Duration.ofMinutes(5),
                "command:" + sandboxId,
                "f".repeat(64)),
            sandbox,
            lease,
            3);
    commandJournal.markDispatched(admission, lease);
    return commandJournal.settle(
        admission,
        lease,
        SandboxCommandJournalStore.TerminalOutcome.SUCCEEDED,
        SandboxExecResult.completed(0, "green", "", false, Duration.ofSeconds(2)),
        "1".repeat(64),
        null);
  }

  private String state(UUID attemptId) {
    return jdbc.queryForObject(
        "SELECT state FROM workspace_generation_attempt WHERE id=?", String.class, attemptId);
  }

  private int inTransaction(Supplier<Integer> operation) {
    Integer result =
        new TransactionTemplate(transactionManager).execute(ignored -> operation.get());
    return result == null ? 0 : result;
  }

  private record Fixture(String runId, UUID attemptId, String operationId, UUID operationToken) {}
}
