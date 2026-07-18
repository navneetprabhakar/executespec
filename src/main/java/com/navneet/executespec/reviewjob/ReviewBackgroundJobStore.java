package com.navneet.executespec.reviewjob;

import java.sql.ResultSet;
import java.sql.SQLException;
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

/** V299 fenced database state machine for Code Review and PR Review background work. */
@Component
@RequiredArgsConstructor
public class ReviewBackgroundJobStore {

  private static final int MAX_PAYLOAD_CHARS = 1_000_000;
  private static final int MAX_RESULT_PAYLOAD_CHARS = 8_000_000;

  public enum Type {
    CODE_REVIEW,
    PR_REVIEW
  }

  public enum Status {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLED,
    SUCCEEDED,
    FAILED;

    public boolean terminal() {
      return this == CANCELLED || this == SUCCEEDED || this == FAILED;
    }
  }

  public record Job(
      UUID id,
      Type type,
      UUID subjectId,
      String projectId,
      String orgId,
      UUID repoId,
      String requestedByUserId,
      String idempotencyKey,
      String provider,
      String model,
      String executionContextId,
      String executionScopeMode,
      String executionScopeOrgId,
      String payload,
      String resultPayload,
      Instant resultReadyAt,
      Status status,
      int attemptCount,
      int maxAttempts,
      Instant nextAttemptAt,
      String leaseOwner,
      long leaseToken,
      Instant leaseExpiresAt,
      Instant heartbeatAt,
      Instant cancelRequestedAt,
      String lastErrorCode,
      String lastErrorMessage,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public record Lease(Job job, String owner, long fencingToken, Instant expiresAt) {}

  private final JdbcTemplate jdbc;

  /** Insert once. A duplicate subject or idempotency key returns the canonical existing job. */
  @Transactional
  public Job createOrGet(Job request) {
    requireBoundedJson(request.payload(), MAX_PAYLOAD_CHARS, "review job payload");
    int inserted =
        jdbc.update(
            """
            INSERT INTO review_background_job
              (id, job_type, subject_id, project_id, org_id, repo_id,
               requested_by_user_id, idempotency_key, provider, model,
               execution_context_id, execution_scope_mode, execution_scope_org_id,
               payload, max_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
            ON CONFLICT DO NOTHING
            """,
            request.id(),
            request.type().name(),
            request.subjectId(),
            request.projectId(),
            request.orgId(),
            request.repoId(),
            request.requestedByUserId(),
            request.idempotencyKey(),
            request.provider(),
            request.model(),
            request.executionContextId(),
            request.executionScopeMode(),
            request.executionScopeOrgId(),
            request.payload(),
            request.maxAttempts());
    if (inserted == 1) return get(request.id()).orElseThrow();
    return findBySubject(request.type(), request.subjectId())
        .or(() -> findByIdempotency(request.idempotencyKey()))
        .orElseThrow(() -> new IllegalStateException("review job insert conflict was lost"));
  }

  public Optional<Job> get(UUID id) {
    return query("SELECT * FROM review_background_job WHERE id=?", id).stream().findFirst();
  }

  public Optional<Job> findBySubject(Type type, UUID subjectId) {
    return query(
            "SELECT * FROM review_background_job WHERE job_type=? AND subject_id=?",
            type.name(),
            subjectId)
        .stream()
        .findFirst();
  }

  public Optional<Job> findByIdempotency(String idempotencyKey) {
    return query("SELECT * FROM review_background_job WHERE idempotency_key=?", idempotencyKey)
        .stream()
        .findFirst();
  }

  public List<UUID> claimCandidates(int limit) {
    return jdbc.query(
        """
        SELECT id FROM review_background_job
        WHERE attempt_count < max_attempts AND (
          (status='QUEUED' AND next_attempt_at<=NOW())
          OR (status='RUNNING' AND lease_expires_at<NOW())
        )
        ORDER BY next_attempt_at, created_at LIMIT ?
        """,
        (rs, ignored) -> rs.getObject(1, UUID.class),
        limit);
  }

  /** Atomically claims queued work or takes over an expired lease, incrementing its fence. */
  @Transactional
  public Optional<Lease> tryAcquire(UUID id, String owner, Duration leaseDuration) {
    Instant expiresAt = Instant.now().plus(leaseDuration);
    List<Job> claimed =
        jdbc.query(
            """
            UPDATE review_background_job SET
              status='RUNNING', lease_owner=?, lease_token=lease_token+1,
              lease_expires_at=?, heartbeat_at=NOW(), attempt_count=attempt_count+1,
              started_at=COALESCE(started_at, NOW()), last_error_code=NULL,
              last_error_message=NULL
            WHERE id=? AND attempt_count<max_attempts AND (
              (status='QUEUED' AND next_attempt_at<=NOW())
              OR (status='RUNNING' AND lease_expires_at<NOW())
            )
            RETURNING *
            """,
            ReviewBackgroundJobStore::map,
            owner,
            Timestamp.from(expiresAt),
            id);
    if (claimed.isEmpty()) return Optional.empty();
    Job job = claimed.getFirst();
    return Optional.of(new Lease(job, owner, job.leaseToken(), expiresAt));
  }

  public boolean heartbeat(Lease lease, Duration leaseDuration) {
    return jdbc.update(
            """
            UPDATE review_background_job SET heartbeat_at=NOW(), lease_expires_at=?
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            Timestamp.from(Instant.now().plus(leaseDuration)),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  /** Locks and validates the exact live fence for an atomic domain-result publication. */
  public void requireLiveLeaseForUpdate(Lease lease) {
    List<Boolean> rows =
        jdbc.query(
            """
            SELECT TRUE FROM review_background_job
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            FOR UPDATE
            """,
            (rs, ignored) -> rs.getBoolean(1),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    if (rows.isEmpty()) throw new LeaseFencedException();
  }

  /** Intermediate progress is also fenced so a stale JVM cannot regress a takeover's phase. */
  public boolean updateCodeReviewStatus(Lease lease, String status) {
    return jdbc.update(
            """
            UPDATE code_review_scan SET status=? WHERE id=? AND EXISTS (
              SELECT 1 FROM review_background_job j
              WHERE j.id=? AND j.status='RUNNING' AND j.lease_owner=? AND j.lease_token=?
                AND j.lease_expires_at>NOW()
            )
            """,
            status,
            lease.job().subjectId(),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean updatePrReviewStatus(Lease lease, String status) {
    return jdbc.update(
            """
            UPDATE pr_review SET status=? WHERE id=? AND EXISTS (
              SELECT 1 FROM review_background_job j
              WHERE j.id=? AND j.status='RUNNING' AND j.lease_owner=? AND j.lease_token=?
                AND j.lease_expires_at>NOW()
            )
            """,
            status,
            lease.job().subjectId(),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean cancellationRequested(Lease lease) {
    List<Boolean> rows =
        jdbc.query(
            """
            SELECT status='CANCEL_REQUESTED' FROM review_background_job
            WHERE id=? AND lease_owner=? AND lease_token=?
            """,
            (rs, ignored) -> rs.getBoolean(1),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    return !rows.isEmpty() && rows.getFirst();
  }

  /** Stages a successful provider result under the live fence before domain publication. */
  public boolean stageResult(Lease lease, String resultPayload) {
    requireBoundedJson(resultPayload, MAX_RESULT_PAYLOAD_CHARS, "review job result payload");
    return jdbc.update(
            """
UPDATE review_background_job SET result_payload=CAST(? AS JSONB), result_ready_at=NOW(),
  heartbeat_at=NOW()
WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
  AND lease_expires_at>NOW() AND result_payload IS NULL
""",
            resultPayload,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  @Transactional
  public Optional<Job> requestCancellation(Type type, UUID subjectId) {
    List<Job> changed =
        jdbc.query(
            """
            UPDATE review_background_job SET
              status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
              cancel_requested_at=COALESCE(cancel_requested_at, NOW()),
              completed_at=CASE WHEN status='QUEUED' THEN NOW() ELSE completed_at END,
              lease_owner=CASE WHEN status='QUEUED' THEN NULL ELSE lease_owner END,
              lease_expires_at=CASE WHEN status='QUEUED' THEN NULL ELSE lease_expires_at END
            WHERE job_type=? AND subject_id=? AND status IN ('QUEUED','RUNNING')
            RETURNING *
            """,
            ReviewBackgroundJobStore::map,
            type.name(),
            subjectId);
    Optional<Job> result = changed.stream().findFirst().or(() -> findBySubject(type, subjectId));
    result
        .filter(job -> job.status() == Status.CANCELLED)
        .ifPresent(job -> updateSubjectTerminal(job, "CANCELLED", "cancelled by user"));
    return result;
  }

  /**
   * Explicit retry preserves the immutable actor, route, context and input snapshots. A prepared
   * result is intentionally retained and reused so retrying a cancellation/finalisation failure
   * cannot repeat provider spend.
   */
  @Transactional
  public Optional<Job> retry(Type type, UUID subjectId) {
    List<Job> changed =
        jdbc.query(
            """
            UPDATE review_background_job SET status='QUEUED', next_attempt_at=NOW(),
              completed_at=NULL, cancel_requested_at=NULL, lease_owner=NULL,
              lease_expires_at=NULL, last_error_code=NULL, last_error_message=NULL
            WHERE job_type=? AND subject_id=? AND status IN ('FAILED','CANCELLED')
              AND attempt_count < max_attempts
            RETURNING *
            """,
            ReviewBackgroundJobStore::map,
            type.name(),
            subjectId);
    Optional<Job> result = changed.stream().findFirst().or(() -> findBySubject(type, subjectId));
    result.filter(job -> job.status() == Status.QUEUED).ifPresent(this::resetSubjectForRetry);
    return result;
  }

  public boolean complete(Lease lease) {
    return jdbc.update(
            """
            UPDATE review_background_job SET status='SUCCEEDED', completed_at=NOW(),
              heartbeat_at=NOW(), lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  @Transactional
  public boolean finishCancellation(Lease lease) {
    int changed =
        jdbc.update(
            """
            UPDATE review_background_job SET status='CANCELLED', completed_at=NOW(),
              heartbeat_at=NOW(), lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='CANCEL_REQUESTED' AND lease_owner=? AND lease_token=?
            """,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    if (changed == 1) {
      updateSubjectTerminal(lease.job(), "CANCELLED", "cancelled by user");
      return true;
    }
    return false;
  }

  /** Fenced retry/failure transition; stale JVM owners cannot overwrite a takeover. */
  @Transactional
  public Status failOrRequeue(Lease lease, String code, String message, Duration delay) {
    boolean retry = lease.job().attemptCount() < lease.job().maxAttempts();
    String status = retry ? "QUEUED" : "FAILED";
    int changed =
        jdbc.update(
            """
            UPDATE review_background_job SET status=?, next_attempt_at=?,
              completed_at=CASE WHEN ?='FAILED' THEN NOW() ELSE NULL END,
              lease_owner=NULL, lease_expires_at=NULL, last_error_code=?,
              last_error_message=?
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
            """,
            status,
            Timestamp.from(Instant.now().plus(delay)),
            status,
            bounded(code, 64),
            bounded(message, 2000),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    if (changed == 1) {
      if (retry) resetSubjectForRetry(lease.job());
      else
        updateSubjectTerminal(
            lease.job(), "FAILED", "background job exhausted retries: " + bounded(code, 64));
      return Status.valueOf(status);
    }
    return get(lease.job().id()).orElseThrow().status();
  }

  @Transactional
  public void finishAbandonedCancellations() {
    List<Job> abandoned =
        jdbc.query(
            """
            UPDATE review_background_job SET status='CANCELLED', completed_at=NOW(),
              lease_owner=NULL, lease_expires_at=NULL
            WHERE status='CANCEL_REQUESTED' AND lease_expires_at<NOW()
            RETURNING *
            """,
            ReviewBackgroundJobStore::map);
    abandoned.forEach(job -> updateSubjectTerminal(job, "CANCELLED", "cancelled by user"));
  }

  private void resetSubjectForRetry(Job job) {
    String table = subjectTable(job.type());
    jdbc.update(
        "UPDATE "
            + table
            + " SET status='QUEUED', finished_at=NULL, failure_reason=NULL WHERE id=?",
        job.subjectId());
  }

  private void updateSubjectTerminal(Job job, String status, String reason) {
    String table = subjectTable(job.type());
    jdbc.update(
        "UPDATE "
            + table
            + " SET status=?, failure_reason=?, finished_at=NOW(), "
            + "duration_ms=CASE WHEN started_at IS NULL THEN duration_ms "
            + "ELSE CAST(EXTRACT(EPOCH FROM (NOW()-started_at))*1000 AS BIGINT) END WHERE id=?",
        status,
        bounded(reason, 2000),
        job.subjectId());
  }

  private static String subjectTable(Type type) {
    return type == Type.CODE_REVIEW ? "code_review_scan" : "pr_review";
  }

  private List<Job> query(String sql, Object... args) {
    return jdbc.query(sql, ReviewBackgroundJobStore::map, args);
  }

  private static Job map(ResultSet rs, int ignored) throws SQLException {
    return new Job(
        rs.getObject("id", UUID.class),
        Type.valueOf(rs.getString("job_type")),
        rs.getObject("subject_id", UUID.class),
        rs.getString("project_id"),
        rs.getString("org_id"),
        rs.getObject("repo_id", UUID.class),
        rs.getString("requested_by_user_id"),
        rs.getString("idempotency_key"),
        rs.getString("provider"),
        rs.getString("model"),
        rs.getString("execution_context_id"),
        rs.getString("execution_scope_mode"),
        rs.getString("execution_scope_org_id"),
        rs.getString("payload"),
        rs.getString("result_payload"),
        instant(rs, "result_ready_at"),
        Status.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"),
        rs.getInt("max_attempts"),
        instant(rs, "next_attempt_at"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        instant(rs, "heartbeat_at"),
        instant(rs, "cancel_requested_at"),
        rs.getString("last_error_code"),
        rs.getString("last_error_message"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String bounded(String value, int max) {
    if (value == null || value.isBlank()) return "UNCLASSIFIED";
    String clean =
        value.replaceAll("(?i)(api[-_ ]?key|token|secret|bearer)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
    return clean.length() <= max ? clean : clean.substring(0, max);
  }

  private static void requireBoundedJson(String value, int maxChars, String label) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(label + " is required");
    if (value.length() > maxChars) {
      throw new IllegalArgumentException(label + " exceeds " + maxChars + " characters");
    }
  }

  public static final class LeaseFencedException extends RuntimeException {}
}
