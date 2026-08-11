package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.harness.TaskStatusPersistenceService;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.PostgresIntegrationBase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real transaction proof for the rollback-result to task-row durable publication boundary. */
class WorkspaceGenerationTaskTransitionPostgresTest extends PostgresIntegrationBase {

  @Autowired JdbcTemplate jdbc;
  @Autowired TaskStatusPersistenceService taskStatuses;
  @Autowired WorkspaceGenerationTaskTransitionService transitions;
  @Autowired WorkspaceGenerationRecoveryService recovery;
  @Autowired WorkspaceGenerationOperationReconciler operationReconciler;
  @Autowired CorrectionWorkspaceTransaction workspaces;
  @Autowired AgentProperties agentProperties;

  @Test
  void crashAfterTaskCommitBeforeReceiptIsIdempotentlyReplayed() {
    Fixture fixture = terminalRollback("replay", "WORKING", 2);

    assertThat(
            taskStatuses.publishWorkspaceGenerationTransition(
                fixture.runId(), "T1", "PARTIAL", "WORKING", 2, "runtime proof failed"))
        .isEqualTo(TaskStatusPersistenceService.Result.UPDATED);
    assertThat(taskStatus(fixture)).isEqualTo("PARTIAL");
    assertThat(transitionState(fixture)).isEqualTo("PENDING");

    // Simulates the scheduler after process death between the committed task mutation and the
    // attempt receipt. The exact target status is an idempotent success, not a stale overwrite.
    transitions.publish(fixture.attemptId());
    transitions.publish(fixture.attemptId());

    assertThat(taskStatus(fixture)).isEqualTo("PARTIAL");
    assertThat(transitionState(fixture)).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_completed_at IS NOT NULL"
                    + " FROM workspace_generation_attempt WHERE id=?",
                Boolean.class,
                fixture.attemptId()))
        .isTrue();
  }

  @Test
  void crashAfterRollbackResolutionBeforeTaskMutationIsDiscoverableAndReplayed() {
    Fixture fixture = terminalRollback("before-task", "WORKING", 0);

    assertThat(taskStatus(fixture)).isEqualTo("WORKING");
    assertThat(transitionState(fixture)).isEqualTo("PENDING");

    transitions.publish(fixture.attemptId());

    assertThat(taskStatus(fixture)).isEqualTo("PARTIAL");
    assertThat(transitionState(fixture)).isEqualTo("COMPLETED");
  }

  @Test
  void interveningTerminalTaskCannotBeOverwrittenByDelayedOutbox() {
    Fixture fixture = terminalRollback("stale", "WORKING", 0);
    jdbc.update(
        "UPDATE agent_task SET status='FAILED',completed_at=NOW() WHERE task_id=?",
        fixture.taskKey());

    assertThatThrownBy(() -> transitions.publish(fixture.attemptId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TASK_TRANSITION_TERMINAL_OVERWRITE");

    assertThat(taskStatus(fixture)).isEqualTo("FAILED");
    assertThat(transitionState(fixture)).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_failure_reason FROM workspace_generation_attempt"
                    + " WHERE id=?",
                String.class,
                fixture.attemptId()))
        .isEqualTo("TASK_TRANSITION_TERMINAL_OVERWRITE");
  }

  @Test
  void cancellationSupersedesDelayedRollbackTransitionAndCompletesItsReceipt() {
    Fixture fixture = terminalRollback("cancelled", "WORKING", 0);
    jdbc.update(
        "UPDATE agent_task SET status='CANCELLED',completed_at=NOW() WHERE task_id=?",
        fixture.taskKey());

    transitions.publish(fixture.attemptId());

    assertThat(taskStatus(fixture)).isEqualTo("CANCELLED");
    assertThat(transitionState(fixture)).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_failure_reason FROM workspace_generation_attempt"
                    + " WHERE id=?",
                String.class,
                fixture.attemptId()))
        .isEqualTo("TASK_TRANSITION_SUPERSEDED_BY_CANCELLATION");
  }

  @Test
  void replacementAdmissionWaitsForTaskReceiptAndExactLlmOperationReconciliation() {
    BoundFixture fixture = terminalRollbackWithRunningOperation("operation-reconcile");

    assertThatThrownBy(() -> insertReplacementAttempt(fixture))
        .rootCause()
        .hasMessageContaining("pending task transition");

    transitions.publish(fixture.attemptId());
    assertThatThrownBy(() -> insertReplacementAttempt(fixture))
        .rootCause()
        .hasMessageContaining("unreconciled LLM operation");

    assertThat(operationReconciler.reconcile(fixture.attemptId()))
        .isEqualTo(WorkspaceGenerationOperationReconciler.Outcome.RECONCILED);
    operationReconciler.assertRunConverged(fixture.runId());
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM llm_execution_operation WHERE id=?",
                String.class,
                fixture.operationId()))
        .isEqualTo("FAILED");

    assertThat(insertReplacementAttempt(fixture)).isOne();
  }

  @Test
  void crashAfterFilesystemRestoreBeforeTerminalUpdateIsRecoveredAndDuplicateReplayIsHarmless()
      throws Exception {
    String runId = UUID.randomUUID() + "-wg-restored-crash";
    String taskKey = runId.substring(0, 8) + "_T1";
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    UUID deadRecoveryToken = UUID.randomUUID();
    Path root = Path.of(agentProperties.getWorkspace()).toAbsolutePath().resolve(runId);
    Files.createDirectories(root);
    git(root, "init");
    git(root, "config", "user.email", "recovery-test@executespec.invalid");
    git(root, "config", "user.name", "Recovery Test");
    Files.writeString(root.resolve("main.py"), "print('accepted predecessor')\n");
    git(root, "add", "main.py");
    git(root, "commit", "-m", "accepted predecessor");
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = workspaces.begin(root);
    var repository = checkpoint.repositories().getFirst();
    String repositoriesJson =
        "[{\"repository\":\"\",\"head\":\""
            + repository.head()
            + "\",\"tree\":\""
            + repository.tree()
            + "\"}]";

    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES (?,?, 'WORKING',0,NOW())",
        taskKey,
        runId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'BUILD_GENERATION',?::uuid,"
            + "NOW()+INTERVAL '10 minutes',NOW())",
        runId,
        workspaceToken.toString());
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        attemptId,
        runId,
        "T1",
        0,
        1,
        root.toString(),
        workspaceToken,
        checkpoint.generation(),
        repositoriesJson,
        "2".repeat(64),
        "3".repeat(64),
        "{}",
        "4".repeat(64),
        "LOCAL_SAME_HOST",
        "local://before",
        "5".repeat(64));
    jdbc.update("UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", attemptId);
    jdbc.update("DELETE FROM run_workspace_authority WHERE run_id=?", runId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'RECOVERY',?::uuid,NOW()+INTERVAL '10 minutes',NOW())",
        runId,
        deadRecoveryToken.toString());
    // The filesystem is already at before_generation, but the dead process never committed the
    // ROLLING_BACK -> ROLLED_BACK transition. Its now-expired recovery token remains as evidence.
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
            + "rejected_diff_digest=repeat('6',64),rejected_diff_json='{}'::jsonb,"
            + "failure_code='INTERRUPTED',failure_reason='crashed after restore',"
            + "recovery_lease_token=?::uuid,recovery_started_at=NOW() WHERE id=?",
        deadRecoveryToken.toString(),
        attemptId);
    jdbc.update(
        "UPDATE run_workspace_authority SET lease_expires_at=NOW()-INTERVAL '1 second'"
            + " WHERE run_id=?",
        runId);

    recovery.recoverAll();
    recovery.recoverAll();

    assertThat(
            jdbc.queryForObject(
                "SELECT state FROM workspace_generation_attempt WHERE id=?",
                String.class,
                attemptId))
        .isEqualTo("ROLLED_BACK");
    assertThat(
            jdbc.queryForObject(
                "SELECT recovery_lease_token<>?::uuid FROM workspace_generation_attempt WHERE id=?",
                Boolean.class,
                deadRecoveryToken.toString(),
                attemptId))
        .isTrue();
    assertThat(taskStatus(new Fixture(runId, taskKey, attemptId))).isEqualTo("PENDING");
    assertThat(transitionState(new Fixture(runId, taskKey, attemptId))).isEqualTo("COMPLETED");
    assertThat(workspaces.captureGeneration(root)).isEqualTo(checkpoint.generation());
  }

  private Fixture terminalRollback(String suffix, String taskStatus, int recoveryAttempt) {
    String runId = UUID.randomUUID() + "-wg-task-" + suffix;
    String taskKey = runId.substring(0, 8) + "_T1";
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES (?,?,?,?,?)",
        taskKey,
        runId,
        taskStatus,
        recoveryAttempt,
        java.sql.Timestamp.from(Instant.now()));
    jdbc.update(
        "INSERT INTO"
            + " run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,heartbeat_at)"
            + " VALUES (?,'BUILD_GENERATION',?::uuid,NOW()+INTERVAL '10 minutes',NOW())",
        runId,
        workspaceToken.toString());
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        attemptId,
        runId,
        "T1",
        0,
        1,
        "/tmp/workspace",
        workspaceToken,
        "1".repeat(64),
        "[]",
        "2".repeat(64),
        "3".repeat(64),
        "{}",
        "4".repeat(64),
        "GCS",
        "gs://bucket/base.zip",
        "5".repeat(64));
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
            + " rejected_diff_digest=repeat('6',64),rejected_diff_json='{}'::jsonb,"
            + " failure_code='RUNTIME_CONTRACT_FAILED',failure_reason='runtime proof failed'"
            + " WHERE id=?",
        attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLED_BACK',resolved_at=NOW(),"
            + " task_transition_kind='MARK_PARTIAL',task_transition_state='PENDING' WHERE id=?",
        attemptId);
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_expected_status FROM workspace_generation_attempt"
                    + " WHERE id=?",
                String.class,
                attemptId))
        .isEqualTo(taskStatus);
    assertThat(
            jdbc.queryForObject(
                "SELECT task_transition_expected_recovery_attempt"
                    + " FROM workspace_generation_attempt WHERE id=?",
                Integer.class,
                attemptId))
        .isEqualTo(recoveryAttempt);
    return new Fixture(runId, taskKey, attemptId);
  }

  private BoundFixture terminalRollbackWithRunningOperation(String suffix) {
    String runId = UUID.randomUUID() + "-wg-task-" + suffix;
    String taskKey = runId.substring(0, 8) + "_T1";
    UUID attemptId = UUID.randomUUID();
    UUID workspaceToken = UUID.randomUUID();
    String contextId = "ctx-" + UUID.randomUUID();
    String operationId = "op-" + UUID.randomUUID();
    UUID operationToken = UUID.randomUUID();
    jdbc.update("INSERT INTO pipeline_run(run_id,status) VALUES (?, 'WORKING')", runId);
    jdbc.update(
        "INSERT INTO agent_task(task_id,run_id,status,recovery_attempt,started_at)"
            + " VALUES (?,?,'WORKING',0,NOW())",
        taskKey,
        runId);
    jdbc.update(
        "INSERT INTO run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,"
            + "heartbeat_at) VALUES (?,'BUILD_GENERATION',?::uuid,"
            + "NOW()+INTERVAL '10 minutes',NOW())",
        runId,
        workspaceToken.toString());
    WorkspaceGenerationPostgresFixture.insertPrepared(
        jdbc,
        attemptId,
        runId,
        "T1",
        0,
        1,
        "/tmp/workspace",
        workspaceToken,
        "1".repeat(64),
        "[]",
        "2".repeat(64),
        "3".repeat(64),
        "{}",
        "4".repeat(64),
        "GCS",
        "gs://bucket/base.zip",
        "5".repeat(64),
        contextId,
        operationId,
        operationToken,
        java.time.Duration.ofHours(4));
    jdbc.update("UPDATE workspace_generation_attempt SET state='MUTATING' WHERE id=?", attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLING_BACK',"
            + "rejected_diff_digest=repeat('6',64),rejected_diff_json='{}'::jsonb,"
            + "failure_code='RUNTIME_CONTRACT_FAILED',failure_reason='runtime proof failed'"
            + " WHERE id=?",
        attemptId);
    jdbc.update(
        "UPDATE workspace_generation_attempt SET state='ROLLED_BACK',resolved_at=NOW(),"
            + "task_transition_kind='MARK_PARTIAL',task_transition_state='PENDING' WHERE id=?",
        attemptId);
    return new BoundFixture(runId, taskKey, attemptId, operationId, operationToken);
  }

  private int insertReplacementAttempt(BoundFixture fixture) {
    return jdbc.update(
        """
        INSERT INTO workspace_generation_attempt(
          id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
          authority_digest,task_contract_digest)
        VALUES (?,?, 'T1',0,2,'BUILD_TASK','ADMITTED','/tmp/workspace',
          repeat('2',64),repeat('3',64))
        """,
        UUID.randomUUID(),
        fixture.runId());
  }

  private String taskStatus(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT status FROM agent_task WHERE task_id=?", String.class, fixture.taskKey());
  }

  private String transitionState(Fixture fixture) {
    return jdbc.queryForObject(
        "SELECT task_transition_state FROM workspace_generation_attempt WHERE id=?",
        String.class,
        fixture.attemptId());
  }

  private record Fixture(String runId, String taskKey, UUID attemptId) {}

  private record BoundFixture(
      String runId, String taskKey, UUID attemptId, String operationId, UUID operationToken) {}

  private static void git(Path root, String... arguments) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(arguments));
    Process process = new ProcessBuilder(command).directory(root.toFile()).start();
    String output = new String(process.getInputStream().readAllBytes());
    String error = new String(process.getErrorStream().readAllBytes());
    assertThat(process.waitFor())
        .as("git %s output=%s error=%s", arguments, output, error)
        .isZero();
  }
}
