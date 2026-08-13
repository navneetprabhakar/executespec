package com.navneet.executespec.workspacegeneration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate JVM used to kill and replay every durable terminal-generation boundary. */
public final class WorkspaceGenerationTerminalKillProbe {

  private WorkspaceGenerationTerminalKillProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 7) {
      throw new IllegalArgumentException("expected mode,stage,jdbc,user,password,runId,attemptId");
    }
    String mode = args[0];
    String stage = args[1];
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(args[2]);
    dataSource.setUsername(args[3]);
    dataSource.setPassword(args[4]);
    try (Connection connection = dataSource.getConnection()) {
      if ("CUT".equals(mode)) cut(connection, stage, args[5], args[6]);
      else if ("RECOVER".equals(mode)) recover(connection, args[5], args[6]);
      else throw new IllegalArgumentException("unknown mode " + mode);
    }
  }

  private static void cut(Connection connection, String stage, String runId, String attemptId)
      throws Exception {
    accept(connection, runId, attemptId, stage);
    if ("AFTER_ACCEPT_COMMIT".equals(stage)) halt(stage);

    transaction(
        connection,
        () ->
            update(
                connection,
                "UPDATE agent_task SET status='COMPLETE',completed_at=COALESCE(completed_at,NOW())"
                    + " WHERE run_id=? AND status IN ('WORKING','COMPLETE')",
                runId));
    if ("AFTER_TASK_ROW_COMMIT".equals(stage)) halt(stage);

    transaction(
        connection,
        () ->
            update(
                connection,
                "UPDATE workspace_generation_attempt SET task_transition_state='COMPLETED',"
                    + "task_transition_completed_at=NOW(),updated_at=NOW() WHERE id=?::uuid"
                    + " AND task_transition_state='PENDING'",
                attemptId));
    if ("AFTER_TASK_RECEIPT_COMMIT".equals(stage)) halt(stage);

    connection.setAutoCommit(false);
    String operationId =
        scalar(
            connection,
            "SELECT llm_operation_id FROM workspace_generation_attempt WHERE id=?::uuid",
            attemptId);
    update(
        connection,
        "UPDATE llm_execution_operation SET status='SUCCEEDED',completed_at=NOW(),updated_at=NOW(),"
            + "lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL WHERE id=?",
        operationId);
    if ("AFTER_OPERATION_ROW_BEFORE_RECEIPT".equals(stage)) halt(stage);
    update(
        connection,
        "UPDATE workspace_generation_attempt SET operation_transition_state='COMPLETED',"
            + "operation_transition_completed_at=NOW(),updated_at=NOW() WHERE id=?::uuid"
            + " AND operation_transition_state='PENDING'",
        attemptId);
    connection.commit();
    connection.setAutoCommit(true);
    if ("AFTER_OPERATION_RECEIPT_COMMIT".equals(stage)) halt(stage);

    transaction(
        connection,
        () ->
            update(
                connection,
                "DELETE FROM run_workspace_authority w WHERE w.run_id=?"
                    + " AND w.authority_type='BUILD_GENERATION' AND EXISTS (SELECT 1 FROM"
                    + " workspace_generation_attempt a WHERE a.id=?::uuid"
                    + " AND a.workspace_lease_token=w.owner_token"
                    + " AND a.task_transition_state='COMPLETED'"
                    + " AND a.operation_transition_state='COMPLETED')",
                runId,
                attemptId));
    halt("AFTER_AUTHORITY_RELEASE");
  }

  private static void accept(Connection connection, String runId, String attemptId, String stage)
      throws Exception {
    if (!"CHECKPOINTING"
        .equals(
            scalar(
                connection,
                "SELECT state FROM workspace_generation_attempt WHERE id=?::uuid",
                attemptId))) {
      return;
    }
    connection.setAutoCommit(false);
    update(
        connection,
        "UPDATE workspace_generation_attempt SET state='ACCEPTED',resolved_at=NOW(),"
            + "task_transition_kind='MARK_COMPLETE',task_transition_state='PENDING',"
            + "operation_transition_target='SUCCEEDED',operation_transition_state='PENDING',"
            + "updated_at=NOW() WHERE id=?::uuid AND state='CHECKPOINTING'",
        attemptId);
    if ("AFTER_ATTEMPT_UPDATE_BEFORE_FRONTIER".equals(stage)) halt(stage);
    update(
        connection,
        "UPDATE pipeline_run p SET accepted_workspace_generation=a.candidate_generation,"
            + "accepted_task_index=a.task_index,accepted_generation_attempt_id=a.id,"
            + "accepted_checkpoint_kind=a.checkpoint_kind,accepted_checkpoint_key=a.checkpoint_key,"
            + "accepted_checkpoint_digest=a.checkpoint_digest,"
            + "accepted_checkpoint_completed_at=a.checkpoint_completed_at FROM"
            + " workspace_generation_attempt a WHERE p.run_id=? AND a.id=?::uuid",
        runId,
        attemptId);
    if ("AFTER_FRONTIER_UPDATE_BEFORE_COMMIT".equals(stage)) halt(stage);
    connection.commit();
    connection.setAutoCommit(true);
  }

  private static void recover(Connection connection, String runId, String attemptId)
      throws Exception {
    accept(connection, runId, attemptId, "RECOVER");
    transaction(
        connection,
        () -> {
          update(
              connection,
              "UPDATE agent_task SET status='COMPLETE',completed_at=COALESCE(completed_at,NOW())"
                  + " WHERE run_id=? AND status IN ('WORKING','COMPLETE')",
              runId);
          update(
              connection,
              "UPDATE workspace_generation_attempt SET task_transition_state='COMPLETED',"
                  + "task_transition_completed_at=NOW(),updated_at=NOW() WHERE id=?::uuid"
                  + " AND task_transition_state='PENDING'",
              attemptId);
        });
    transaction(
        connection,
        () -> {
          String operationId =
              scalar(
                  connection,
                  "SELECT llm_operation_id FROM workspace_generation_attempt WHERE id=?::uuid",
                  attemptId);
          update(
              connection,
              "UPDATE llm_execution_operation SET status='SUCCEEDED',"
                  + "completed_at=NOW(),updated_at=NOW(),lease_owner=NULL,"
                  + "lease_token=NULL,lease_expires_at=NULL WHERE id=? AND status IN"
                  + " ('RUNNING')",
              operationId);
          update(
              connection,
              "UPDATE workspace_generation_attempt SET operation_transition_state='COMPLETED',"
                  + "operation_transition_completed_at=NOW(),updated_at=NOW() WHERE id=?::uuid"
                  + " AND operation_transition_state='PENDING'",
              attemptId);
        });
    transaction(
        connection,
        () ->
            update(
                connection,
                "DELETE FROM run_workspace_authority w WHERE w.run_id=?"
                    + " AND w.authority_type='BUILD_GENERATION' AND EXISTS (SELECT 1 FROM"
                    + " workspace_generation_attempt a JOIN llm_execution_operation o"
                    + " ON o.id=a.llm_operation_id WHERE a.id=?::uuid"
                    + " AND a.workspace_lease_token=w.owner_token AND a.state='ACCEPTED'"
                    + " AND a.task_transition_state='COMPLETED'"
                    + " AND a.operation_transition_state='COMPLETED' AND o.status='SUCCEEDED')",
                runId,
                attemptId));
    int contradictions =
        integer(
            connection,
            "SELECT COUNT(*) FROM workspace_generation_attempt a JOIN agent_task t"
                + " ON t.run_id=a.run_id JOIN llm_execution_operation o ON o.id=a.llm_operation_id"
                + " LEFT JOIN run_workspace_authority w ON w.run_id=a.run_id WHERE a.id=?::uuid"
                + " AND NOT (a.state='ACCEPTED' AND a.task_transition_state='COMPLETED'"
                + " AND t.status='COMPLETE' AND a.operation_transition_state='COMPLETED'"
                + " AND o.status='SUCCEEDED' AND w.run_id IS NULL)",
            attemptId);
    if (contradictions != 0) throw new IllegalStateException("TERMINAL_RECOVERY_CONTRADICTION");
    System.out.println("TERMINAL_RECOVERY_PASSED run=" + runId);
  }

  private static void transaction(Connection connection, SqlRunnable work) throws Exception {
    connection.setAutoCommit(false);
    try {
      work.run();
      connection.commit();
    } catch (Exception failure) {
      connection.rollback();
      throw failure;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private static int update(Connection connection, String sql, Object... values) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++)
        statement.setObject(index + 1, values[index]);
      return statement.executeUpdate();
    }
  }

  private static String scalar(Connection connection, String sql, Object value) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, value);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalStateException("probe row missing");
        return result.getString(1);
      }
    }
  }

  private static int integer(Connection connection, String sql, Object value) throws Exception {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, value);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) throw new IllegalStateException("probe row missing");
        return result.getInt(1);
      }
    }
  }

  private static void halt(String stage) {
    System.out.println("TERMINAL_KILL_MARKER " + stage);
    System.out.flush();
    Runtime.getRuntime().halt(137);
  }

  @FunctionalInterface
  private interface SqlRunnable {
    void run() throws Exception;
  }
}
