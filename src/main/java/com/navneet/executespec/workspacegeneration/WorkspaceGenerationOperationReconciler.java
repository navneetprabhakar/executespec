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
                SELECT a.state,a.llm_operation_id,a.llm_attempt_number,a.llm_lease_token,
                       a.operation_transition_target,a.operation_transition_state,o.status
                  FROM workspace_generation_attempt a
                  LEFT JOIN llm_execution_operation o ON o.id=a.llm_operation_id
                 WHERE a.id=?
                """,
                (rs, row) -> binding(rs),
                attemptId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
    String desired = requireIntent(binding);
    if (binding.operationId() == null) {
      completeUnboundReceipt(attemptId, binding, desired);
      return Outcome.UNBOUND;
    }
    if ("COMPLETED".equals(binding.transitionState())) {
      if (!desired.equals(binding.operationStatus())) {
        throw new IllegalStateException("WORKSPACE_GENERATION_LLM_RECEIPT_CONFLICT");
      }
      return Outcome.ALREADY_TERMINAL;
    }
    if (!"PENDING".equals(binding.transitionState())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_INTENT_STATE_INVALID");
    }
    if (isTerminal(binding.operationStatus())) {
      if (!desired.equals(binding.operationStatus())) {
        throw new IllegalStateException(
            "WORKSPACE_GENERATION_LLM_TERMINAL_CONFLICT:"
                + binding.state()
                + ":"
                + binding.operationStatus());
      }
    } else {
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
   AND a.operation_transition_state='PENDING'
   AND a.operation_transition_target=?
   AND o.attempt=a.llm_attempt_number
   AND (o.status='QUEUED'
     OR (o.status IN ('RUNNING','CANCEL_REQUESTED')
       AND o.lease_token=a.llm_lease_token))
""",
              desired,
              desired,
              desired,
              attemptId,
              desired);
      if (updated != 1) {
        throw new IllegalStateException("WORKSPACE_GENERATION_LLM_RECONCILIATION_CAS_FAILED");
      }
    }

    int receipted =
        jdbc.update(
            """
            UPDATE workspace_generation_attempt
               SET operation_transition_state='COMPLETED',
                   operation_transition_completed_at=NOW(),updated_at=NOW()
             WHERE id=? AND operation_transition_target=?
               AND operation_transition_state='PENDING'
            """,
            attemptId,
            desired);
    if (receipted != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_RECEIPT_CAS_FAILED");
    }
    return Outcome.RECONCILED;
  }

  private void completeUnboundReceipt(UUID attemptId, Binding binding, String desired) {
    if ("COMPLETED".equals(binding.transitionState())) return;
    if (!"PENDING".equals(binding.transitionState())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_INTENT_STATE_INVALID");
    }
    int receipted =
        jdbc.update(
            """
            UPDATE workspace_generation_attempt
               SET operation_transition_state='COMPLETED',
                   operation_transition_completed_at=NOW(),updated_at=NOW()
             WHERE id=? AND llm_operation_id IS NULL
               AND operation_transition_target=? AND operation_transition_state='PENDING'
            """,
            attemptId,
            desired);
    if (receipted != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_RECEIPT_CAS_FAILED");
    }
  }

  @Transactional
  public void reconcileRun(String runId) {
    List<UUID> ids =
        jdbc.query(
            """
            SELECT a.id
             FROM workspace_generation_attempt a
             WHERE a.run_id=? AND (a.state IN ('ACCEPTED','ROLLED_BACK')
                 OR (a.state='RECOVERY_FAILED' AND a.resolved_at IS NOT NULL))
               AND a.operation_transition_state='PENDING'
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
              LEFT JOIN llm_execution_operation o ON o.id=a.llm_operation_id
             WHERE a.run_id=? AND (a.state IN ('ACCEPTED','ROLLED_BACK')
                 OR (a.state='RECOVERY_FAILED' AND a.resolved_at IS NOT NULL))
               AND (a.operation_transition_state IS DISTINCT FROM 'COMPLETED'
                 OR (a.llm_operation_id IS NOT NULL
                   AND ((a.operation_transition_target='SUCCEEDED'
                         AND o.status IS DISTINCT FROM 'SUCCEEDED')
                     OR (a.operation_transition_target='FAILED'
                         AND o.status IS DISTINCT FROM 'FAILED')))
                 OR a.operation_transition_target NOT IN ('SUCCEEDED','FAILED')
                 OR a.operation_transition_target IS NULL)
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
        rs.getString("operation_transition_target"),
        rs.getString("operation_transition_state"),
        rs.getString("status"));
  }

  private static String requireIntent(Binding binding) {
    String expected =
        switch (binding.state()) {
          case "ACCEPTED" -> "SUCCEEDED";
          case "ROLLED_BACK", "RECOVERY_FAILED" -> "FAILED";
          default -> throw new IllegalStateException("WORKSPACE_GENERATION_NOT_TERMINAL");
        };
    if (!expected.equals(binding.transitionTarget())) {
      throw new IllegalStateException("WORKSPACE_GENERATION_LLM_INTENT_MISSING_OR_INVALID");
    }
    return binding.transitionTarget();
  }

  private static boolean isTerminal(String status) {
    return status != null && List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
  }

  private record Binding(
      String state,
      String operationId,
      Integer operationAttempt,
      UUID operationToken,
      String transitionTarget,
      String transitionState,
      String operationStatus) {}
}
