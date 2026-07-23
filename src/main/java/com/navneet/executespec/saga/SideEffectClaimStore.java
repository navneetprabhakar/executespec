package com.navneet.executespec.saga;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * V291 durable claim/fencing boundary for {@code side_effect_outbox}.
 *
 * <p>This bean is deliberately separate from {@link SideEffectExecutor}. Spring therefore proxies
 * each {@code REQUIRES_NEW} method and commits the claim before the caller invokes an external
 * handler. Every later mutation compares the lease owner and monotonically increasing token; a
 * stale JVM cannot publish success, failure, or a heartbeat after another JVM takes over.
 */
@Component
@RequiredArgsConstructor
public class SideEffectClaimStore {

  public enum DeliveryResult {
    RETRY_SCHEDULED,
    FAILED,
    LEASE_LOST
  }

  public record Lease(
      UUID id,
      String runId,
      String effectType,
      String payload,
      int attempt,
      int maxAttempts,
      String owner,
      long fencingToken,
      Instant expiresAt,
      String idempotencyKey,
      String handlerContractVersion) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  /**
   * Claims a bounded due batch with {@code FOR UPDATE SKIP LOCKED}. Expired PROCESSING rows are
   * normal candidates, which makes rolling restarts and crashed workers recoverable.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<Lease> claimDue(String owner, int requestedLimit, Duration leaseDuration) {
    String effectiveOwner = requireOwner(owner);
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    Instant expiresAt = Instant.now().plus(requireLeaseDuration(leaseDuration));
    return jdbc.query(
        """
        WITH candidates AS (
          SELECT id FROM side_effect_outbox
          WHERE attempt < max_attempts AND (
            (status='PENDING' AND next_retry_at<=NOW())
            OR (status='PROCESSING' AND lease_expires_at<=NOW())
          )
          ORDER BY
            CASE WHEN status='PROCESSING' THEN 0 ELSE 1 END,
            COALESCE(lease_expires_at, next_retry_at), created_at
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE side_effect_outbox outbox SET
          status='PROCESSING', lease_owner=?, lease_token=outbox.lease_token+1,
          lease_expires_at=?, heartbeat_at=NOW(), attempt=outbox.attempt+1,
          last_attempt_started_at=NOW(), last_attempt_completed_at=NULL,
          last_attempt_evidence=NULL, terminal_evidence=NULL, last_error=NULL
        FROM candidates
        WHERE outbox.id=candidates.id
        RETURNING outbox.*
        """,
        SideEffectClaimStore::mapLease,
        limit,
        effectiveOwner,
        Timestamp.from(expiresAt));
  }

  /** Compatibility claim for a caller holding one previously loaded entry. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Lease> tryClaim(
      UUID id, String owner, Duration leaseDuration, int expectedMaxAttempts) {
    String effectiveOwner = requireOwner(owner);
    Instant expiresAt = Instant.now().plus(requireLeaseDuration(leaseDuration));
    List<Lease> claimed =
        jdbc.query(
            """
            UPDATE side_effect_outbox SET
              status='PROCESSING', lease_owner=?, lease_token=lease_token+1,
              lease_expires_at=?, heartbeat_at=NOW(), attempt=attempt+1,
              last_attempt_started_at=NOW(), last_attempt_completed_at=NULL,
              last_attempt_evidence=NULL, terminal_evidence=NULL, last_error=NULL
            WHERE id=? AND attempt<max_attempts AND max_attempts=? AND (
              (status='PENDING' AND next_retry_at<=NOW())
              OR (status='PROCESSING' AND lease_expires_at<=NOW())
            )
            RETURNING *
            """,
            SideEffectClaimStore::mapLease,
            effectiveOwner,
            Timestamp.from(expiresAt),
            id,
            expectedMaxAttempts);
    return claimed.stream().findFirst();
  }

  /** Persist liveness without allowing an already-expired or fenced owner to revive itself. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean heartbeat(Lease lease, Duration leaseDuration) {
    Instant expiresAt = Instant.now().plus(requireLeaseDuration(leaseDuration));
    return jdbc.update(
            """
            UPDATE side_effect_outbox SET heartbeat_at=NOW(), lease_expires_at=?
            WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            Timestamp.from(expiresAt),
            lease.id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  /** Complete only while this exact lease generation is still current. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean complete(Lease lease, String handlerResult) {
    String evidence = evidence(lease, "COMPLETED", handlerResult, null);
    return jdbc.update(
            """
            UPDATE side_effect_outbox SET status='COMPLETED', completed_at=NOW(),
              last_attempt_completed_at=NOW(), last_attempt_evidence=CAST(? AS jsonb),
              terminal_evidence=CAST(? AS jsonb), last_error=NULL,
              lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            evidence,
            evidence,
            lease.id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  /** Retry with backoff, or persist a terminal exhausted-attempt failure. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeliveryResult failOrRetry(Lease lease, Throwable failure, Duration retryDelay) {
    boolean retry = lease.attempt() < lease.maxAttempts();
    String status = retry ? "PENDING" : "FAILED";
    String error = boundedError(failure);
    String evidence = evidence(lease, status, null, error);
    int updated =
        jdbc.update(
            """
            UPDATE side_effect_outbox SET status=?, next_retry_at=?,
              last_attempt_completed_at=NOW(), last_attempt_evidence=CAST(? AS jsonb),
              terminal_evidence=CASE WHEN ?='FAILED' THEN CAST(? AS jsonb) ELSE NULL END,
              completed_at=CASE WHEN ?='FAILED' THEN NOW() ELSE NULL END,
              last_error=?, lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            status,
            Timestamp.from(Instant.now().plus(retryDelay)),
            evidence,
            status,
            evidence,
            status,
            error,
            lease.id(),
            lease.owner(),
            lease.fencingToken());
    if (updated == 0) return DeliveryResult.LEASE_LOST;
    return retry ? DeliveryResult.RETRY_SCHEDULED : DeliveryResult.FAILED;
  }

  /** Persist an honest terminal failure for malformed or unsupported non-retryable work. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeliveryResult failPermanently(Lease lease, Throwable failure) {
    String error = boundedError(failure);
    String evidence = evidence(lease, "FAILED_NON_RETRYABLE", null, error);
    int updated =
        jdbc.update(
            """
            UPDATE side_effect_outbox SET status='FAILED', completed_at=NOW(),
              last_attempt_completed_at=NOW(), last_attempt_evidence=CAST(? AS jsonb),
              terminal_evidence=CAST(? AS jsonb), last_error=?,
              lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            evidence,
            evidence,
            error,
            lease.id(),
            lease.owner(),
            lease.fencingToken());
    return updated == 1 ? DeliveryResult.FAILED : DeliveryResult.LEASE_LOST;
  }

  /**
   * Bounds cleanup of stale rows that consumed their final attempt before crashing. These rows are
   * not claimable again, but must not remain PROCESSING forever.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int failExpiredExhausted(int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    return jdbc.update(
        """
        WITH exhausted AS (
          SELECT id FROM side_effect_outbox
          WHERE status='PROCESSING' AND lease_expires_at<=NOW() AND attempt>=max_attempts
          ORDER BY lease_expires_at, created_at
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        UPDATE side_effect_outbox outbox SET status='FAILED', completed_at=NOW(),
          last_attempt_completed_at=NOW(), last_error='MAX_ATTEMPTS_EXHAUSTED_AFTER_WORKER_LOSS',
          last_attempt_evidence=jsonb_build_object(
            'outcome', 'FAILED', 'reason', 'MAX_ATTEMPTS_EXHAUSTED_AFTER_WORKER_LOSS',
            'attempt', outbox.attempt, 'leaseToken', outbox.lease_token),
          terminal_evidence=jsonb_build_object(
            'outcome', 'FAILED', 'reason', 'MAX_ATTEMPTS_EXHAUSTED_AFTER_WORKER_LOSS',
            'attempt', outbox.attempt, 'leaseToken', outbox.lease_token),
          lease_owner=NULL, lease_expires_at=NULL
        FROM exhausted WHERE outbox.id=exhausted.id
        """,
        limit);
  }

  private String evidence(Lease lease, String outcome, String handlerResult, String error) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("outcome", outcome);
    evidence.put("attempt", lease.attempt());
    evidence.put("leaseToken", lease.fencingToken());
    evidence.put("idempotencyKey", lease.idempotencyKey());
    evidence.put("handlerContractVersion", lease.handlerContractVersion());
    if (handlerResult != null) evidence.put("handlerResult", handlerResult);
    if (error != null) evidence.put("error", error);
    try {
      return mapper.writeValueAsString(evidence);
    } catch (JsonProcessingException impossibleForScalarEvidence) {
      throw new IllegalStateException(
          "Could not serialize side-effect delivery evidence", impossibleForScalarEvidence);
    }
  }

  private static Lease mapLease(ResultSet rs, int ignored) throws SQLException {
    return new Lease(
        rs.getObject("id", UUID.class),
        rs.getString("run_id"),
        rs.getString("effect_type"),
        rs.getString("payload"),
        rs.getInt("attempt"),
        rs.getInt("max_attempts"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        rs.getTimestamp("lease_expires_at").toInstant(),
        rs.getString("idempotency_key"),
        rs.getString("handler_contract_version"));
  }

  private static String requireOwner(String owner) {
    if (owner == null || owner.isBlank() || owner.length() > 160) {
      throw new IllegalArgumentException("lease owner must contain 1-160 characters");
    }
    return owner.trim();
  }

  private static Duration requireLeaseDuration(Duration duration) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      throw new IllegalArgumentException("lease duration must be positive");
    }
    return duration;
  }

  private static String boundedError(Throwable failure) {
    String message = failure == null ? "UNKNOWN_FAILURE" : failure.getMessage();
    if (message == null || message.isBlank()) {
      message = failure == null ? "UNKNOWN_FAILURE" : failure.getClass().getSimpleName();
    }
    return message.length() <= 2000 ? message : message.substring(0, 2000);
  }
}
