package com.navneet.executespec.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * V303 durable claim primitive for run submission.
 *
 * <p>This class owns exactly one responsibility: the atomic state transitions of {@code
 * run_submission_idempotency}. All policy (how long to wait, what to do with a loser, how a request
 * body is fingerprinted) lives in {@link RunIdempotencyService}.
 *
 * <p>The atomicity is supplied by {@code uq_run_submission_idempotency_actor_key} plus a single
 * {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING} statement. There is deliberately no
 * read-then-write pair anywhere in the claim path — that separation was the defect being removed.
 */
@Component
@RequiredArgsConstructor
public class RunSubmissionClaimStore {

  /** A persisted idempotency record. */
  public record Record(
      String actorId,
      String idempotencyKey,
      String requestHash,
      String state,
      String runId,
      String leaseOwner,
      long fencingToken,
      Instant leaseExpiresAt,
      String failureReason) {}

  public static final String STATE_CLAIMED = "CLAIMED";
  public static final String STATE_COMPLETED = "COMPLETED";
  public static final String STATE_FAILED_RETRYABLE = "FAILED_RETRYABLE";
  public static final String STATE_FAILED_FINAL = "FAILED_FINAL";

  private final JdbcTemplate jdbc;

  /**
   * Atomically take the claim for {@code (actorId, key)}.
   *
   * <p>Succeeds when the row does not exist, when a previous attempt failed retryably, or when a
   * previous claimant's lease has elapsed (dead-winner recovery). In every one of those cases the
   * fencing token advances, so the superseded claimant can no longer publish a terminal state.
   *
   * @return the new fencing token, or empty when the row exists and is not claimable by this caller
   */
  public Optional<Long> tryClaim(
      String actorId,
      String key,
      String requestHash,
      String owner,
      long leaseMillis,
      long ttlMillis) {
    var tokens =
        jdbc.queryForList(
            """
            INSERT INTO run_submission_idempotency
              (id, actor_id, idempotency_key, request_hash, state,
               lease_owner, fencing_token, lease_expires_at, expires_at)
            VALUES (?, ?, ?, ?, 'CLAIMED', ?, 1,
                    NOW() + (? * INTERVAL '1 millisecond'),
                    NOW() + (? * INTERVAL '1 millisecond'))
            ON CONFLICT (actor_id, idempotency_key) DO UPDATE
               SET state = 'CLAIMED',
                   lease_owner = EXCLUDED.lease_owner,
                   fencing_token = run_submission_idempotency.fencing_token + 1,
                   lease_expires_at = EXCLUDED.lease_expires_at,
                   failure_reason = NULL,
                   updated_at = NOW()
             WHERE run_submission_idempotency.request_hash = EXCLUDED.request_hash
               AND (run_submission_idempotency.state = 'FAILED_RETRYABLE'
                    OR (run_submission_idempotency.state = 'CLAIMED'
                        AND run_submission_idempotency.lease_expires_at <= NOW()))
            RETURNING fencing_token
            """,
            Long.class,
            UUID.randomUUID(),
            actorId,
            key,
            requestHash,
            owner,
            leaseMillis,
            ttlMillis);
    return tokens.isEmpty() ? Optional.empty() : Optional.of(tokens.get(0));
  }

  /** Read the current record, if any. Used only to explain a lost claim, never to take one. */
  public Optional<Record> find(String actorId, String key) {
    return jdbc
        .query(
            """
            SELECT actor_id, idempotency_key, request_hash, state, run_id,
                   lease_owner, fencing_token, lease_expires_at, failure_reason
              FROM run_submission_idempotency
             WHERE actor_id = ? AND idempotency_key = ?
            """,
            RunSubmissionClaimStore::map,
            actorId,
            key)
        .stream()
        .findFirst();
  }

  /** Publish the created run. Fenced: a superseded claimant cannot overwrite its replacement. */
  public boolean complete(String actorId, String key, String owner, long token, String runId) {
    return jdbc.update(
            """
            UPDATE run_submission_idempotency
               SET state = 'COMPLETED', run_id = ?, lease_owner = NULL,
                   lease_expires_at = NULL, failure_reason = NULL, updated_at = NOW()
             WHERE actor_id = ? AND idempotency_key = ? AND state = 'CLAIMED'
               AND lease_owner = ? AND fencing_token = ?
            """,
            runId,
            actorId,
            key,
            owner,
            token)
        == 1;
  }

  /** Publish a terminal failure. Fenced identically to {@link #complete}. */
  public boolean fail(
      String actorId, String key, String owner, long token, String state, String reason) {
    return jdbc.update(
            """
            UPDATE run_submission_idempotency
               SET state = ?, lease_owner = NULL, lease_expires_at = NULL,
                   failure_reason = ?, updated_at = NOW()
             WHERE actor_id = ? AND idempotency_key = ? AND state = 'CLAIMED'
               AND lease_owner = ? AND fencing_token = ?
            """,
            state,
            reason,
            actorId,
            key,
            owner,
            token)
        == 1;
  }

  /** Retention sweep for records past their TTL. */
  public int deleteExpired(int limit) {
    return jdbc.update(
        """
        DELETE FROM run_submission_idempotency
         WHERE id IN (SELECT id FROM run_submission_idempotency
                       WHERE expires_at <= NOW() AND state <> 'CLAIMED' LIMIT ?)
        """,
        limit);
  }

  private static Record map(ResultSet rs, int rowNum) throws SQLException {
    var lease = rs.getTimestamp("lease_expires_at");
    return new Record(
        rs.getString("actor_id"),
        rs.getString("idempotency_key"),
        rs.getString("request_hash"),
        rs.getString("state"),
        rs.getString("run_id"),
        rs.getString("lease_owner"),
        rs.getLong("fencing_token"),
        lease == null ? null : lease.toInstant(),
        rs.getString("failure_reason"));
  }
}
