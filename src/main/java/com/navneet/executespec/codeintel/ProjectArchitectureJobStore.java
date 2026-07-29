package com.navneet.executespec.codeintel;

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

/** V353 durable, database-clocked and lease-fenced project architecture state machine. */
@Component
@RequiredArgsConstructor
public class ProjectArchitectureJobStore {

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NEEDS_CONFIRMATION;

    public boolean terminal() {
      return this != QUEUED && this != RUNNING;
    }
  }

  public record Job(
      UUID id,
      String projectId,
      String orgId,
      String requestedByUserId,
      String requestedBranch,
      String sourceSnapshotJson,
      String sourceSnapshotDigest,
      String requestedProvider,
      String repoArchitectureModel,
      String projectArchitectureModel,
      String repoExecutionContextId,
      String projectExecutionContextId,
      Status status,
      String phase,
      int attemptCount,
      Instant nextAttemptAt,
      Instant deadlineAt,
      String leaseOwner,
      long leaseToken,
      Instant leaseExpiresAt,
      Instant cancelRequestedAt,
      String stagedArtifactsJson,
      String stagedArtifactsDigest,
      String qualityStatus,
      String qualityEvidenceJson,
      UUID resultArchitectureId,
      Integer resultArchitectureVersion,
      String resultSourceFingerprint,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant promotedAt) {}

  public record Lease(Job job, String owner, long fencingToken, Instant expiresAt) {}

  private final JdbcTemplate jdbc;

  /** Fast DB-only admission. Cross-JVM idempotency is the one-active-job partial index. */
  @Transactional
  public Job createOrGet(
      UUID id,
      String projectId,
      String orgId,
      String requestedByUserId,
      String requestedBranch,
      String provider,
      String repoModel,
      String projectModel,
      Duration deadline) {
    // Serialize admission with confirmation/retry for this project. NEEDS_CONFIRMATION is a
    // server-side spend gate, not merely a web label.
    jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", String.class, projectId);
    Optional<Job> latest = findLatest(projectId);
    if (latest.isPresent() && latest.get().status() == Status.NEEDS_CONFIRMATION) {
      return latest.get();
    }
    jdbc.update(
        """
        INSERT INTO project_architecture_job
          (id, project_id, org_id, requested_by_user_id, requested_branch, requested_provider,
           repo_architecture_model, project_architecture_model, deadline_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp() + (? * INTERVAL '1 millisecond'))
        ON CONFLICT DO NOTHING
        """,
        id,
        projectId,
        orgId,
        requestedByUserId,
        requestedBranch,
        provider,
        repoModel,
        projectModel,
        deadline.toMillis());
    return get(id).or(() -> findLatestActive(projectId)).orElseThrow();
  }

  public Optional<Job> get(UUID id) {
    return query("SELECT * FROM project_architecture_job WHERE id=?", id).stream().findFirst();
  }

  public Optional<Job> getScoped(UUID id, String projectId) {
    return query(
            "SELECT * FROM project_architecture_job WHERE id=? AND project_id=?", id, projectId)
        .stream()
        .findFirst();
  }

  public Optional<Job> findLatestActive(String projectId) {
    return query(
            """
            SELECT * FROM project_architecture_job
            WHERE project_id=? AND status IN ('RUNNING','QUEUED')
            ORDER BY CASE status WHEN 'RUNNING' THEN 0 ELSE 1 END, created_at DESC LIMIT 1
            """,
            projectId)
        .stream()
        .findFirst();
  }

  public Optional<Job> findLatest(String projectId) {
    return query(
            """
            SELECT * FROM project_architecture_job
            WHERE project_id=? ORDER BY created_at DESC LIMIT 1
            """,
            projectId)
        .stream()
        .findFirst();
  }

  /** Immediate cooperative fence used between every repository/provider operation. */
  public boolean isLive(Lease lease) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM project_architecture_job
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp() AND cancel_requested_at IS NULL
              AND deadline_at>clock_timestamp()
            """,
            Integer.class,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    return count != null && count == 1;
  }

  public List<UUID> claimCandidates(int limit, int maxAttempts) {
    return jdbc.query(
        """
        SELECT id FROM project_architecture_job
        WHERE attempt_count < ? AND cancel_requested_at IS NULL AND deadline_at>clock_timestamp()
          AND ((status='QUEUED' AND next_attempt_at<=clock_timestamp())
            OR (status='RUNNING' AND phase='PREPARING'
                AND lease_expires_at<clock_timestamp()))
        ORDER BY next_attempt_at, created_at LIMIT ?
        """,
        (rs, ignored) -> rs.getObject(1, UUID.class),
        maxAttempts,
        limit);
  }

  /**
   * Atomic first claim or PREPARING takeover. The database computes both expiry and returned time,
   * avoiding skew between JVM clocks.
   */
  public Optional<Lease> tryAcquire(
      UUID id, String owner, Duration leaseDuration, int maxAttempts) {
    List<Job> claimed =
        query(
            """
            UPDATE project_architecture_job SET
              status='RUNNING', lease_owner=?, lease_token=lease_token+1,
              lease_expires_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),
              attempt_count=attempt_count+1, started_at=COALESCE(started_at, clock_timestamp()),
              error_code=NULL, error_message=NULL
            WHERE id=? AND attempt_count<? AND cancel_requested_at IS NULL
              AND deadline_at>clock_timestamp()
              AND ((status='QUEUED' AND next_attempt_at<=clock_timestamp())
                OR (status='RUNNING' AND phase='PREPARING'
                    AND lease_expires_at<clock_timestamp()))
            RETURNING *
            """,
            owner,
            leaseDuration.toMillis(),
            id,
            maxAttempts);
    if (claimed.isEmpty()) return Optional.empty();
    Job job = claimed.getFirst();
    return Optional.of(new Lease(job, owner, job.leaseToken(), job.leaseExpiresAt()));
  }

  public boolean bindPrepared(
      Lease lease,
      String snapshotJson,
      String snapshotDigest,
      String repoContextId,
      String projectContextId) {
    return jdbc.update(
            """
            UPDATE project_architecture_job SET source_snapshot_json=?::jsonb,
              source_snapshot_digest=?, repo_execution_context_id=?,
              project_execution_context_id=?
            WHERE id=? AND status='RUNNING' AND phase='PREPARING'
              AND lease_owner=? AND lease_token=? AND lease_expires_at>clock_timestamp()
              AND cancel_requested_at IS NULL AND deadline_at>clock_timestamp()
            """,
            snapshotJson,
            snapshotDigest,
            repoContextId,
            projectContextId,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean renew(Lease lease, Duration leaseDuration) {
    return jdbc.update(
            """
            UPDATE project_architecture_job SET
              lease_expires_at=clock_timestamp() + (? * INTERVAL '1 millisecond')
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp() AND cancel_requested_at IS NULL
              AND deadline_at>clock_timestamp()
            """,
            leaseDuration.toMillis(),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean beginExecuting(Lease lease) {
    return transition(lease, "PREPARING", "EXECUTING", false);
  }

  public boolean stage(
      Lease lease,
      String artifactsJson,
      String artifactsDigest,
      String qualityStatus,
      String qualityEvidenceJson) {
    return jdbc.update(
            """
            UPDATE project_architecture_job SET staged_artifacts_json=?::jsonb,
              staged_artifacts_digest=?, quality_status=?, quality_evidence_json=?::jsonb
            WHERE id=? AND status='RUNNING' AND phase='EXECUTING'
              AND lease_owner=? AND lease_token=? AND lease_expires_at>clock_timestamp()
              AND cancel_requested_at IS NULL AND deadline_at>clock_timestamp()
            """,
            artifactsJson,
            artifactsDigest,
            qualityStatus,
            qualityEvidenceJson,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean beginPublishing(Lease lease) {
    return transition(lease, "EXECUTING", "PUBLISHING", true);
  }

  private boolean transition(Lease lease, String from, String to, boolean requireStage) {
    return jdbc.update(
            """
            UPDATE project_architecture_job SET phase=?
            WHERE id=? AND status='RUNNING' AND phase=? AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp() AND cancel_requested_at IS NULL
              AND deadline_at>clock_timestamp()
              AND (?=FALSE OR (staged_artifacts_json IS NOT NULL
                               AND staged_artifacts_digest IS NOT NULL))
            """,
            to,
            lease.job().id(),
            from,
            lease.owner(),
            lease.fencingToken(),
            requireStage)
        == 1;
  }

  /** Caller must already be in the promotion transaction. */
  public Optional<Job> lockLiveForPromotion(Lease lease) {
    return query(
            """
            SELECT * FROM project_architecture_job
            WHERE id=? AND status='RUNNING' AND phase='PUBLISHING'
              AND lease_owner=? AND lease_token=? AND lease_expires_at>clock_timestamp()
              AND cancel_requested_at IS NULL AND deadline_at>clock_timestamp()
            FOR UPDATE
            """,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        .stream()
        .findFirst();
  }

  /** Caller must already hold the row lock in the promotion transaction. */
  public boolean completePromoted(
      Lease lease, UUID architectureId, int architectureVersion, String sourceDigest) {
    return jdbc.update(
            """
            UPDATE project_architecture_job SET status='SUCCEEDED',
              result_architecture_id=?, result_architecture_version=?,
              result_source_fingerprint=?, promoted_at=clock_timestamp(),
              completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
              error_code=NULL, error_message=NULL
            WHERE id=? AND status='RUNNING' AND phase='PUBLISHING'
              AND lease_owner=? AND lease_token=? AND lease_expires_at>clock_timestamp()
              AND source_snapshot_digest=? AND cancel_requested_at IS NULL
            """,
            architectureId,
            architectureVersion,
            sourceDigest,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken(),
            sourceDigest)
        == 1;
  }

  @Transactional
  public Status failOrRequeue(
      Lease lease, String code, String message, int maxAttempts, Duration delay) {
    List<String> updated =
        jdbc.query(
            """
            WITH decision AS (
              SELECT id,
                CASE WHEN cancel_requested_at IS NOT NULL THEN 'CANCELLED'
                     WHEN attempt_count < ?
                           AND deadline_at > clock_timestamp()
                     THEN 'QUEUED' ELSE 'FAILED' END AS next_status
              FROM project_architecture_job
              WHERE id=? AND status='RUNNING' AND phase='PREPARING'
                AND lease_owner=? AND lease_token=? AND lease_expires_at>clock_timestamp()
              FOR UPDATE
            )
            UPDATE project_architecture_job job SET status=decision.next_status,
              error_code=CASE WHEN decision.next_status='CANCELLED'
                THEN 'CANCELLED' ELSE ? END,
              error_message=CASE WHEN decision.next_status='CANCELLED'
                THEN 'Map refresh was cancelled.' ELSE ? END,
              next_attempt_at=CASE WHEN decision.next_status='QUEUED'
                THEN clock_timestamp() + (? * INTERVAL '1 millisecond')
                ELSE job.next_attempt_at END,
              completed_at=CASE WHEN decision.next_status IN ('FAILED','CANCELLED')
                THEN clock_timestamp() ELSE NULL END,
              lease_owner=NULL, lease_expires_at=NULL
            FROM decision WHERE job.id=decision.id
            RETURNING job.status
            """,
            (rs, ignored) -> rs.getString(1),
            maxAttempts,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken(),
            bounded(code, 96),
            bounded(message, 512),
            delay.toMillis());
    return updated.isEmpty()
        ? get(lease.job().id()).orElseThrow().status()
        : Status.valueOf(updated.getFirst());
  }

  @Transactional
  public boolean fail(Lease lease, String code, String message) {
    return terminal(lease, "FAILED", code, message);
  }

  @Transactional
  public boolean needsConfirmation(Lease lease, String code, String message) {
    return terminal(lease, "NEEDS_CONFIRMATION", code, message);
  }

  private boolean terminal(Lease lease, String status, String code, String message) {
    return jdbc.update(
            """
            UPDATE project_architecture_job SET
              status=CASE WHEN cancel_requested_at IS NOT NULL THEN 'CANCELLED' ELSE ? END,
              error_code=CASE WHEN cancel_requested_at IS NOT NULL THEN 'CANCELLED' ELSE ? END,
              error_message=CASE WHEN cancel_requested_at IS NOT NULL
                THEN 'Map refresh was cancelled.' ELSE ? END,
              completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
              staged_artifacts_json=NULL, staged_artifacts_digest=NULL
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp()
            """,
            status,
            bounded(code, 96),
            bounded(message, 512),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  /** Queued cancellation is immediate; running cancellation is cooperative and blocks renewal. */
  @Transactional
  public boolean cancel(UUID id, String projectId) {
    int queued =
        jdbc.update(
            """
            UPDATE project_architecture_job SET status='CANCELLED',
              cancel_requested_at=clock_timestamp(), completed_at=clock_timestamp(),
              error_code='CANCELLED', error_message='Map refresh was cancelled.'
            WHERE id=? AND project_id=? AND status='QUEUED'
            """,
            id,
            projectId);
    if (queued == 1) return true;
    return jdbc.update(
            """
            UPDATE project_architecture_job SET cancel_requested_at=clock_timestamp()
            WHERE id=? AND project_id=? AND status='RUNNING' AND cancel_requested_at IS NULL
            """,
            id,
            projectId)
        == 1;
  }

  /** Explicit user confirmation reuses the same durable identity and exact route. */
  @Transactional
  public boolean confirmRetry(
      UUID id, String projectId, String confirmingUserId, Duration deadline) {
    jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", String.class, projectId);
    int confirmed =
        jdbc.update(
            """
            UPDATE project_architecture_job SET status='QUEUED', phase='PREPARING',
              attempt_count=0, next_attempt_at=clock_timestamp(),
              deadline_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),
              source_snapshot_json=NULL, source_snapshot_digest=NULL,
              repo_execution_context_id=NULL, project_execution_context_id=NULL,
              staged_artifacts_json=NULL, staged_artifacts_digest=NULL,
              quality_status=NULL, quality_evidence_json=NULL,
              result_architecture_id=NULL, result_architecture_version=NULL,
              result_source_fingerprint=NULL, error_code=NULL, error_message=NULL,
              completed_at=NULL, promoted_at=NULL, cancel_requested_at=NULL
            WHERE id=? AND project_id=? AND status='NEEDS_CONFIRMATION'
              AND requested_by_user_id=?
            """,
            deadline.toMillis(),
            id,
            projectId,
            confirmingUserId);
    if (confirmed != 1) return false;
    jdbc.update(
        """
        INSERT INTO project_architecture_job_retry_confirmation
          (id, job_id, project_id, confirmed_by_user_id)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        id,
        projectId,
        confirmingUserId);
    return true;
  }

  /**
   * Resolve cancellation/deadline and uncertain expired paid work without retrying provider spend.
   */
  @Transactional
  public int sweep(int maxAttempts) {
    int cancelled =
        jdbc.update(
            """
            UPDATE project_architecture_job SET status='CANCELLED',
              completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
              error_code='CANCELLED', error_message='Map refresh was cancelled.',
              staged_artifacts_json=NULL, staged_artifacts_digest=NULL
            WHERE status='RUNNING' AND cancel_requested_at IS NOT NULL
              AND lease_expires_at<clock_timestamp()
            """);
    int deadline =
        jdbc.update(
            """
            UPDATE project_architecture_job SET status='FAILED', completed_at=clock_timestamp(),
              lease_owner=NULL, lease_expires_at=NULL, error_code='DEADLINE_EXCEEDED',
              error_message='Map refresh exceeded its durable deadline.',
              staged_artifacts_json=NULL, staged_artifacts_digest=NULL
            WHERE deadline_at<=clock_timestamp()
              AND (status='QUEUED'
                OR (status='RUNNING' AND phase='PREPARING'
                  AND lease_expires_at<clock_timestamp()))
            """);
    int uncertain =
        jdbc.update(
            """
UPDATE project_architecture_job SET status='NEEDS_CONFIRMATION',
  completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
  error_code='OUTCOME_UNCERTAIN',
  error_message='The worker stopped after provider execution began; retry requires confirmation.',
  staged_artifacts_json=NULL, staged_artifacts_digest=NULL
WHERE status='RUNNING' AND phase IN ('EXECUTING','PUBLISHING')
  AND lease_expires_at<clock_timestamp()
""");
    int exhausted =
        jdbc.update(
            """
            UPDATE project_architecture_job SET status='FAILED', completed_at=clock_timestamp(),
              lease_owner=NULL, lease_expires_at=NULL, error_code='RETRY_EXHAUSTED',
              error_message='Map refresh could not be prepared.',
              staged_artifacts_json=NULL, staged_artifacts_digest=NULL
            WHERE status='RUNNING' AND phase='PREPARING' AND attempt_count>=?
              AND lease_expires_at<clock_timestamp()
            """,
            maxAttempts);
    return cancelled + deadline + uncertain + exhausted;
  }

  @Transactional
  public int deleteTerminalOlderThan(Duration retention) {
    return jdbc.update(
        """
        DELETE FROM project_architecture_job
        WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','NEEDS_CONFIRMATION')
          AND completed_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
        """,
        retention.toMillis());
  }

  private List<Job> query(String sql, Object... args) {
    return jdbc.query(sql, ProjectArchitectureJobStore::map, args);
  }

  private static Job map(ResultSet rs, int ignored) throws SQLException {
    return new Job(
        rs.getObject("id", UUID.class),
        rs.getString("project_id"),
        rs.getString("org_id"),
        rs.getString("requested_by_user_id"),
        rs.getString("requested_branch"),
        rs.getString("source_snapshot_json"),
        rs.getString("source_snapshot_digest"),
        rs.getString("requested_provider"),
        rs.getString("repo_architecture_model"),
        rs.getString("project_architecture_model"),
        rs.getString("repo_execution_context_id"),
        rs.getString("project_execution_context_id"),
        Status.valueOf(rs.getString("status")),
        rs.getString("phase"),
        rs.getInt("attempt_count"),
        instant(rs, "next_attempt_at"),
        instant(rs, "deadline_at"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        instant(rs, "cancel_requested_at"),
        rs.getString("staged_artifacts_json"),
        rs.getString("staged_artifacts_digest"),
        rs.getString("quality_status"),
        rs.getString("quality_evidence_json"),
        rs.getObject("result_architecture_id", UUID.class),
        (Integer) rs.getObject("result_architecture_version"),
        rs.getString("result_source_fingerprint"),
        rs.getString("error_code"),
        rs.getString("error_message"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"),
        instant(rs, "promoted_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String bounded(String value, int max) {
    if (value == null || value.isBlank()) return "UNKNOWN";
    return value.length() <= max ? value : value.substring(0, max);
  }
}
