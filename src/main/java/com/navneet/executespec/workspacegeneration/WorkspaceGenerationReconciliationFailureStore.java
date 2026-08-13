package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, secret-free diagnostics for scheduler poison records; never terminal authority. */
@Component
public class WorkspaceGenerationReconciliationFailureStore {

  public enum IdentityKind {
    ATTEMPT,
    RUN
  }

  public record Failure(
      UUID id,
      IdentityKind identityKind,
      String identityKey,
      int failureCount,
      String lastErrorCode,
      Instant firstFailedAt,
      Instant lastFailedAt,
      Instant deadLetterAt,
      Instant resolvedAt) {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate independent;

  public WorkspaceGenerationReconciliationFailureStore(
      JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.independent = new TransactionTemplate(transactionManager);
    this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public Failure recordFailure(
      IdentityKind kind, String identityKey, String safeErrorCode, int deadLetterThreshold) {
    if (deadLetterThreshold < 1) {
      throw new IllegalArgumentException("RECONCILIATION_DEAD_LETTER_THRESHOLD_INVALID");
    }
    String key = requireText(identityKey, "RECONCILIATION_IDENTITY_INVALID");
    String code = requireCode(safeErrorCode);
    return independent.execute(
        ignored ->
            jdbc.queryForObject(
                """
                INSERT INTO workspace_generation_reconciliation_failure(
                  id,identity_kind,identity_key,attempt_id,run_id,failure_count,last_error_code,
                  first_failed_at,last_failed_at,dead_letter_at,resolved_at)
                VALUES (?, ?, ?, ?::uuid, ?, 1, ?, NOW(), NOW(),
                  CASE WHEN ? <= 1 THEN NOW() ELSE NULL END, NULL)
ON CONFLICT (identity_kind,identity_key) DO UPDATE
   SET failure_count=workspace_generation_reconciliation_failure.failure_count+1,
       last_error_code=EXCLUDED.last_error_code,
       last_failed_at=NOW(),resolved_at=NULL,
       dead_letter_at=CASE
         WHEN workspace_generation_reconciliation_failure.dead_letter_at IS NOT NULL
           THEN workspace_generation_reconciliation_failure.dead_letter_at
         WHEN workspace_generation_reconciliation_failure.failure_count+1 >= ?
           THEN NOW()
         ELSE NULL END
RETURNING id,identity_kind,identity_key,failure_count,last_error_code,
          first_failed_at,last_failed_at,dead_letter_at,resolved_at
""",
                (rs, row) -> map(rs),
                UUID.randomUUID(),
                kind.name(),
                key,
                kind == IdentityKind.ATTEMPT ? key : null,
                kind == IdentityKind.RUN ? key : null,
                code,
                deadLetterThreshold,
                deadLetterThreshold));
  }

  public void recordSuccess(IdentityKind kind, String identityKey) {
    independent.executeWithoutResult(
        ignored ->
            jdbc.update(
                "UPDATE workspace_generation_reconciliation_failure SET resolved_at=NOW()"
                    + " WHERE identity_kind=? AND identity_key=? AND resolved_at IS NULL",
                kind.name(),
                requireText(identityKey, "RECONCILIATION_IDENTITY_INVALID")));
  }

  public List<Failure> readOpen() {
    return jdbc.query(
        "SELECT id,identity_kind,identity_key,failure_count,last_error_code,first_failed_at,"
            + "last_failed_at,dead_letter_at,resolved_at FROM"
            + " workspace_generation_reconciliation_failure WHERE resolved_at IS NULL"
            + " ORDER BY dead_letter_at NULLS LAST,last_failed_at,identity_kind,identity_key",
        (rs, row) -> map(rs));
  }

  private static Failure map(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Failure(
        rs.getObject("id", UUID.class),
        IdentityKind.valueOf(rs.getString("identity_kind")),
        rs.getString("identity_key"),
        rs.getInt("failure_count"),
        rs.getString("last_error_code"),
        rs.getTimestamp("first_failed_at").toInstant(),
        rs.getTimestamp("last_failed_at").toInstant(),
        instant(rs, "dead_letter_at"),
        instant(rs, "resolved_at"));
  }

  private static Instant instant(java.sql.ResultSet rs, String column)
      throws java.sql.SQLException {
    java.sql.Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String requireText(String value, String code) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new IllegalArgumentException(code);
    }
    return value;
  }

  private static String requireCode(String value) {
    if (value == null || !value.matches("[A-Z0-9_]{3,96}")) {
      throw new IllegalArgumentException("RECONCILIATION_ERROR_CODE_INVALID");
    }
    return value;
  }
}
