package com.navneet.executespec.workspacegeneration;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Durable, fenced claims for non-authoritative accepted-recovery artifact cleanup. */
@Component
@RequiredArgsConstructor
class WorkspaceGenerationRecoveryCleanupStore {

  private final JdbcTemplate jdbc;

  void enqueue(String runId, String path) {
    jdbc.update(
        """
        INSERT INTO workspace_generation_recovery_cleanup
          (id,run_id,artifact_path,artifact_path_digest)
        VALUES (?,?,?,?)
        ON CONFLICT (run_id,artifact_path_digest) DO NOTHING
        """,
        UUID.randomUUID(),
        runId,
        path,
        DigestUtils.sha256Hex(path));
  }

  @Transactional
  Optional<Claim> claimNext(Duration staleAfter) {
    List<Claim> rows =
        jdbc.query(
            """
            WITH candidate AS (
              SELECT id FROM workspace_generation_recovery_cleanup
              WHERE status='PENDING' OR (status='PROCESSING' AND claimed_at < ?)
              ORDER BY created_at,id
              FOR UPDATE SKIP LOCKED LIMIT 1
            )
            UPDATE workspace_generation_recovery_cleanup cleanup
            SET status='PROCESSING',attempts=attempts+1,claimed_at=NOW(),last_error=NULL
            FROM candidate WHERE cleanup.id=candidate.id
            RETURNING cleanup.id,cleanup.run_id,cleanup.artifact_path,cleanup.attempts
            """,
            (rs, row) ->
                new Claim(
                    rs.getObject("id", UUID.class),
                    rs.getString("run_id"),
                    rs.getString("artifact_path"),
                    rs.getInt("attempts")),
            Timestamp.from(Instant.now().minus(staleAfter)));
    return rows.stream().findFirst();
  }

  void complete(Claim claim) {
    int updated =
        jdbc.update(
            """
            UPDATE workspace_generation_recovery_cleanup
            SET status='COMPLETED',completed_at=NOW(),claimed_at=NULL,last_error=NULL
            WHERE id=? AND status='PROCESSING' AND attempts=?
            """,
            claim.id(),
            claim.attempt());
    if (updated != 1) throw new IllegalStateException("WORKSPACE_RECOVERY_CLEANUP_CLAIM_FENCED");
  }

  void fail(Claim claim, String detail) {
    int updated =
        jdbc.update(
            """
            UPDATE workspace_generation_recovery_cleanup
            SET status='PENDING',claimed_at=NULL,last_error=?
            WHERE id=? AND status='PROCESSING' AND attempts=?
            """,
            bounded(detail),
            claim.id(),
            claim.attempt());
    if (updated != 1) throw new IllegalStateException("WORKSPACE_RECOVERY_CLEANUP_CLAIM_FENCED");
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "WORKSPACE_RECOVERY_CLEANUP_FAILED";
    return value.substring(0, Math.min(1000, value.length()));
  }

  record Claim(UUID id, String runId, String path, int attempt) {}
}
