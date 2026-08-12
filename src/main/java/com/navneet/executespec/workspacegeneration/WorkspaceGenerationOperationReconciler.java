package com.navneet.executespec.workspacegeneration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Reconciles a terminal BUILD generation with its exact bound physical LLM operation. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationOperationReconciler {

  private final JdbcTemplate jdbc;

  public enum Outcome {
    RECONCILED,
    ALREADY_TERMINAL,
    UNBOUND
  }

  @Transactional
  public Outcome reconcile(UUID attemptId) {
    Binding binding =
        jdbc
            .query(
                """
                SELECT a.state,a.llm_operation_id,a.llm_attempt_number,a.llm_lease_token,o.status
                  FROM workspace_generation_attempt a
                  LEFT JOIN llm_execution_operation o ON o.id=a.llm_operation_id
                 WHERE a.id=?
                """,
                (rs, row) -> binding(rs),
                attemptId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
    if (binding.operationId() == null) return Outcome.UNBOUND;
    String desired = desiredStatus(binding.state());
    if (desired.equals(binding.operationStatus())) return Outcome.ALREADY_TERMINAL;
    if (isTerminal(binding.operationStatus())) {
      throw new IllegalStateException(
          "WORKSPACE_GENERATION_LLM_TERMINAL_CONFLICT:"
              + binding.state()
              + ":"
              + binding.operationStatus());
    }
    int updated =
        jdbc.update(
            """
            UPDATE llm_execution_operation o
               SET status=?,completed_at=NOW(),updated_at=NOW(),
                   last_error_code=CASE WHEN ?='FAILED' THEN 'WORKSPACE_GENERATION_ROLLED_BACK'
                                        ELSE last_error_code END,
                   last_error_redacted=CASE WHEN ?='FAILED'
                       THEN 'Durable workspace-generation evidence rejected this physical attempt'
                       ELSE last_error_redacted END,
                   lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,
                   not_reclaim_before=NULL,next_attempt_at=NULL,version=version+1
              FROM workspace_generation_attempt a
             WHERE a.id=? AND o.id=a.llm_operation_id
               AND a.state IN ('ACCEPTED','ROLLED_BACK')
               AND o.attempt=a.llm_attempt_number
               AND (o.status='QUEUED'
                 OR (o.status IN ('RUNNING','CANCEL_REQUESTED')
                   AND o.lease_token=a.llm_lease_token))
            """,
            desired,
            desired,
            desired,
            attemptId);
    if (updated != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_RECONCILIATION_CAS_FAILED");
    }
    return Outcome.RECONCILED;
  }

  @Transactional
  public void reconcileRun(String runId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT a.id
              FROM workspace_generation_attempt a
              JOIN llm_execution_operation o ON o.id=a.llm_operation_id
             WHERE a.run_id=? AND a.state IN ('ACCEPTED','ROLLED_BACK')
               AND ((a.state='ACCEPTED' AND o.status<>'SUCCEEDED')
                 OR (a.state='ROLLED_BACK' AND o.status<>'FAILED'))
             ORDER BY a.attempt_number
            """,
            (rs, row) -> rs.getObject(1, UUID.class),
            runId);
    ids.forEach(this::reconcile);
  }

  public void assertRunConverged(String runId) {
    Integer mismatches =
        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM workspace_generation_attempt a
              JOIN llm_execution_operation o ON o.id=a.llm_operation_id
             WHERE a.run_id=? AND a.state IN ('ACCEPTED','ROLLED_BACK')
               AND ((a.state='ACCEPTED' AND o.status<>'SUCCEEDED')
                 OR (a.state='ROLLED_BACK' AND o.status<>'FAILED'))
            """,
            Integer.class,
            runId);
    if (mismatches == null || mismatches != 0) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_OPERATION_UNRECONCILED");
    }
  }

  private static Binding binding(ResultSet rs) throws SQLException {
    return new Binding(
        rs.getString("state"),
        rs.getString("llm_operation_id"),
        (Integer) rs.getObject("llm_attempt_number"),
        rs.getObject("llm_lease_token", UUID.class),
        rs.getString("status"));
  }

  private static String desiredStatus(String state) {
    return switch (state) {
      case "ACCEPTED" -> "SUCCEEDED";
      case "ROLLED_BACK" -> "FAILED";
      default -> throw new IllegalStateException("WORKSPACE_GENERATION_NOT_TERMINAL");
    };
  }

  private static boolean isTerminal(String status) {
    return status != null && List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
  }

  private record Binding(
      String state,
      String operationId,
      Integer operationAttempt,
      UUID operationToken,
      String operationStatus) {}
}
