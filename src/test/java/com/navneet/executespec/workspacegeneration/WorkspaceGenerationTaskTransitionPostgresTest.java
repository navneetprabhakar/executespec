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
        "UPDATE agent_task SET status='COMPLETE',completed_at=NOW() WHERE task_id=?",
        fixture.taskKey());

    assertThatThrownBy(() -> transitions.publish(fixture.attemptId()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("TASK_TRANSITION_TERMINAL_OVERWRITE");

    assertThat(taskStatus(fixture)).isEqualTo("COMPLETE");
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
    // The filesystem is already at before_generation, but the dead process never committed the
    // ROLLING_BACK -> ROLLED_BACK transition. Its stale recovery token remains on the attempt.
    jdbc.update(
        """
INSERT INTO workspace_generation_attempt(
  id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
  before_generation,repositories_json,authority_digest,task_contract_digest,
  ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
  workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
  before_checkpoint_digest,before_checkpoint_completed_at,rejected_diff_digest,
  rejected_diff_json,failure_code,failure_reason,recovery_lease_token,recovery_started_at)
VALUES (?,?,'T1',0,1,'BUILD_TASK','ROLLING_BACK',?,?,?::jsonb,repeat('2',64),
  repeat('3',64),'{}'::jsonb,repeat('4',64),?,'LOCAL_SAME_HOST','local://before',
  repeat('5',64),NOW(),repeat('6',64),'{}'::jsonb,'INTERRUPTED','crashed after restore',?,NOW())
""",
        attemptId,
        runId,
        root.toString(),
        checkpoint.generation(),
        repositoriesJson,
        workspaceToken,
        deadRecoveryToken);

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
    jdbc.update(
        """
        INSERT INTO workspace_generation_attempt(
          id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
          before_generation,repositories_json,authority_digest,task_contract_digest,
          ignored_artifact_baseline_json,ignored_artifact_baseline_digest,
          workspace_lease_token,before_checkpoint_kind,before_checkpoint_key,
          before_checkpoint_digest,before_checkpoint_completed_at)
        VALUES (?,?,'T1',0,1,'BUILD_TASK','PREPARED','/tmp/workspace',repeat('1',64),
          '[]'::jsonb,repeat('2',64),repeat('3',64),'{}'::jsonb,repeat('4',64),?,
          'GCS','gs://bucket/base.zip',repeat('5',64),NOW())
        """,
        attemptId,
        runId,
        workspaceToken);
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
