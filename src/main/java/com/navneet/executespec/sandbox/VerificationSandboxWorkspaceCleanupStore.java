package com.navneet.executespec.sandbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Durable, poison-isolated cleanup claims for terminal verification-sandbox workspaces. */
@Component
@RequiredArgsConstructor
public class VerificationSandboxWorkspaceCleanupStore {

  private final JdbcTemplate jdbc;

  @Transactional
  public Optional<Claim> claimNext(Duration staleAfter) {
    Instant staleBefore = Instant.now().minus(staleAfter);
    List<Claim> claimed =
        jdbc.query(
            """
            WITH candidate AS (
              SELECT c.sandbox_id
              FROM run_sandbox_workspace_cleanup c
              JOIN run_sandbox s ON s.id=c.sandbox_id
              WHERE s.purpose='VERIFICATION'
                AND s.state IN ('TERMINATED','CANCELLED','EXPIRED','FAILED')
                AND (c.status='PENDING'
                     OR (c.status='PROCESSING' AND c.claimed_at < ?))
              ORDER BY c.created_at, c.sandbox_id
              FOR UPDATE OF c SKIP LOCKED
              LIMIT 1
            )
            UPDATE run_sandbox_workspace_cleanup c
            SET status='PROCESSING', attempts=attempts+1, claimed_at=NOW(), last_error=NULL
            FROM candidate
            WHERE c.sandbox_id=candidate.sandbox_id
            RETURNING c.sandbox_id, c.attempts
            """,
            (rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getInt(2)),
            Timestamp.from(staleBefore));
    return claimed.stream().findFirst();
  }

  public void complete(Claim claim) {
    int updated =
        jdbc.update(
            """
            UPDATE run_sandbox_workspace_cleanup
            SET status='COMPLETED', completed_at=NOW(), claimed_at=NULL, last_error=NULL
            WHERE sandbox_id=? AND status='PROCESSING' AND attempts=?
            """,
            claim.sandboxId(),
            claim.attempt());
    if (updated != 1) throw new IllegalStateException("VERIFICATION_CLEANUP_CLAIM_FENCED");
  }

  public void fail(Claim claim, String detail) {
    int updated =
        jdbc.update(
            """
            UPDATE run_sandbox_workspace_cleanup
            SET status='PENDING', claimed_at=NULL, last_error=?
            WHERE sandbox_id=? AND status='PROCESSING' AND attempts=?
            """,
            RunSandboxPersistenceCodec.bounded(detail, 1000),
            claim.sandboxId(),
            claim.attempt());
    if (updated != 1) throw new IllegalStateException("VERIFICATION_CLEANUP_CLAIM_FENCED");
  }

  public record Claim(UUID sandboxId, int attempt) {}
}
