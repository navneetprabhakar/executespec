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

/** V358 database-clocked state machine for durable project-document generation. */
@Component
@RequiredArgsConstructor
public class ProjectGeneratedDocJobStore {

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    NEEDS_CONFIRMATION;

    public boolean terminal() {
      return this == SUCCEEDED || this == FAILED || this == NEEDS_CONFIRMATION;
    }
  }

  public record Job(
      UUID id,
      String projectId,
      String orgId,
      String requestedByUserId,
      String docType,
      String requestedProvider,
      String requestedModel,
      Status status,
      int attemptCount,
      Instant nextAttemptAt,
      Instant deadlineAt,
      String leaseOwner,
      long leaseToken,
      Instant leaseExpiresAt,
      Instant providerStartedAt,
      UUID resultDocId,
      Integer resultDocVersion,
      String errorCode,
      String errorMessage,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}

  public record Lease(Job job, String owner, long token) {}

  private final JdbcTemplate jdbc;

  @Transactional
  public Job createOrGet(
      UUID id,
      String projectId,
      String orgId,
      String requestedByUserId,
      String docType,
      String provider,
      String model,
      Duration deadline) {
    jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", String.class, projectId);
    Optional<Job> latest = latest(projectId, docType);
    if (latest.isPresent() && latest.get().status() == Status.NEEDS_CONFIRMATION) {
      return latest.get();
    }
    jdbc.update(
        """
        INSERT INTO project_generated_doc_job
          (id, project_id, org_id, requested_by_user_id, doc_type,
           requested_provider, requested_model, deadline_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp() + (? * INTERVAL '1 millisecond'))
        ON CONFLICT DO NOTHING
        """,
        id,
        projectId,
        orgId,
        requestedByUserId,
        docType,
        provider,
        model,
        deadline.toMillis());
    return get(id).or(() -> latestActive(projectId, docType)).orElseThrow();
  }

  public Optional<Job> get(UUID id) {
    return query("SELECT * FROM project_generated_doc_job WHERE id=?", id).stream().findFirst();
  }

  public Optional<Job> getScoped(UUID id, String projectId) {
    return query(
            "SELECT * FROM project_generated_doc_job WHERE id=? AND project_id=?", id, projectId)
        .stream()
        .findFirst();
  }

  public Optional<Job> latestActive(String projectId, String docType) {
    return query(
            """
            SELECT * FROM project_generated_doc_job
            WHERE project_id=? AND doc_type=? AND status IN ('QUEUED','RUNNING')
            ORDER BY created_at DESC LIMIT 1
            """,
            projectId,
            docType)
        .stream()
        .findFirst();
  }

  public Optional<Job> latest(String projectId, String docType) {
    return query(
            """
            SELECT * FROM project_generated_doc_job
            WHERE project_id=? AND doc_type=? ORDER BY created_at DESC LIMIT 1
            """,
            projectId,
            docType)
        .stream()
        .findFirst();
  }

  public List<UUID> claimCandidates(int limit) {
    return jdbc.query(
        """
        SELECT id FROM project_generated_doc_job
        WHERE status='QUEUED' AND next_attempt_at<=clock_timestamp()
          AND deadline_at>clock_timestamp()
        ORDER BY next_attempt_at, created_at LIMIT ?
        """,
        (rs, ignored) -> rs.getObject(1, UUID.class),
        limit);
  }

  public Optional<Lease> tryAcquire(UUID id, String owner, Duration leaseDuration) {
    List<Job> rows =
        query(
            """
            UPDATE project_generated_doc_job SET status='RUNNING', lease_owner=?,
              lease_token=lease_token+1,
              lease_expires_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),
              attempt_count=attempt_count+1, started_at=COALESCE(started_at, clock_timestamp()),
              error_code=NULL, error_message=NULL
            WHERE id=? AND status='QUEUED' AND deadline_at>clock_timestamp()
            RETURNING *
            """,
            owner,
            leaseDuration.toMillis(),
            id);
    if (rows.isEmpty()) return Optional.empty();
    Job job = rows.getFirst();
    return Optional.of(new Lease(job, owner, job.leaseToken()));
  }

  public boolean renew(Lease lease, Duration leaseDuration) {
    return jdbc.update(
            """
            UPDATE project_generated_doc_job
            SET lease_expires_at=clock_timestamp() + (? * INTERVAL '1 millisecond')
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp()
            """,
            leaseDuration.toMillis(),
            lease.job().id(),
            lease.owner(),
            lease.token())
        == 1;
  }

  /** Durable pre-dispatch fence. The provider call must not begin unless this write succeeds. */
  public boolean markProviderStarted(Lease lease) {
    return jdbc.update(
            """
            UPDATE project_generated_doc_job
            SET provider_started_at=COALESCE(provider_started_at, clock_timestamp())
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp()
            """,
            lease.job().id(),
            lease.owner(),
            lease.token())
        == 1;
  }

  public boolean complete(Lease lease, UUID docId, int version) {
    return jdbc.update(
            """
            UPDATE project_generated_doc_job SET status='SUCCEEDED',
              result_doc_id=?, result_doc_version=?, completed_at=clock_timestamp(),
              lease_owner=NULL, lease_expires_at=NULL, error_code=NULL, error_message=NULL
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>clock_timestamp() AND deadline_at>clock_timestamp()
            """,
            docId,
            version,
            lease.job().id(),
            lease.owner(),
            lease.token())
        == 1;
  }

  public boolean fail(Lease lease, String code, String message) {
    return jdbc.update(
            """
            UPDATE project_generated_doc_job SET status='FAILED',
              completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
              error_code=?, error_message=?
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
            """,
            bounded(code, 96),
            bounded(message, 512),
            lease.job().id(),
            lease.owner(),
            lease.token())
        == 1;
  }

  public boolean needsConfirmation(Lease lease, String code, String message) {
    return jdbc.update(
            """
            UPDATE project_generated_doc_job SET status='NEEDS_CONFIRMATION',
              completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
              error_code=?, error_message=?
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND provider_started_at IS NOT NULL
            """,
            bounded(code, 96),
            bounded(message, 512),
            lease.job().id(),
            lease.owner(),
            lease.token())
        == 1;
  }

  /** Explicit requester confirmation reuses the same durable identity and exact route. */
  @Transactional
  public boolean confirmRetry(
      UUID id, String projectId, String docType, String confirmingUserId, Duration deadline) {
    jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", String.class, projectId);
    int confirmed =
        jdbc.update(
            """
            UPDATE project_generated_doc_job SET status='QUEUED', attempt_count=0,
              next_attempt_at=clock_timestamp(),
              deadline_at=clock_timestamp() + (? * INTERVAL '1 millisecond'),
              provider_started_at=NULL, result_doc_id=NULL, result_doc_version=NULL,
              error_code=NULL, error_message=NULL, started_at=NULL, completed_at=NULL
            WHERE id=? AND project_id=? AND doc_type=? AND status='NEEDS_CONFIRMATION'
              AND requested_by_user_id=?
            """,
            deadline.toMillis(),
            id,
            projectId,
            docType,
            confirmingUserId);
    if (confirmed != 1) return false;
    jdbc.update(
        """
        INSERT INTO project_generated_doc_job_retry_confirmation
          (id, job_id, project_id, confirmed_by_user_id)
        VALUES (?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        id,
        projectId,
        confirmingUserId);
    return true;
  }

  public int sweepExpired() {
    int uncertain =
        jdbc.update(
            """
UPDATE project_generated_doc_job SET status='NEEDS_CONFIRMATION',
  completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
  error_code='OUTCOME_UNCERTAIN',
  error_message='The worker stopped after provider execution began; retry requires confirmation.'
WHERE status='RUNNING' AND lease_expires_at<=clock_timestamp()
  AND provider_started_at IS NOT NULL
""");
    int failed =
        jdbc.update(
            """
            UPDATE project_generated_doc_job SET status='FAILED',
              completed_at=clock_timestamp(), lease_owner=NULL, lease_expires_at=NULL,
              error_code=CASE WHEN deadline_at<=clock_timestamp()
                THEN 'DEADLINE_EXCEEDED' ELSE 'WORKER_LEASE_EXPIRED' END,
              error_message=CASE WHEN deadline_at<=clock_timestamp()
                THEN 'Document generation exceeded its durable deadline.'
                ELSE 'Document generation worker stopped before publishing a result.' END
            WHERE (status='QUEUED' AND deadline_at<=clock_timestamp())
               OR (status='RUNNING' AND lease_expires_at<=clock_timestamp()
                   AND provider_started_at IS NULL)
            """);
    return uncertain + failed;
  }

  public int deleteTerminalOlderThan(Duration age) {
    return jdbc.update(
        """
        DELETE FROM project_generated_doc_job
        WHERE status IN ('SUCCEEDED','FAILED','NEEDS_CONFIRMATION')
          AND completed_at < clock_timestamp() - (? * INTERVAL '1 millisecond')
        """,
        age.toMillis());
  }

  private List<Job> query(String sql, Object... args) {
    return jdbc.query(sql, ProjectGeneratedDocJobStore::map, args);
  }

  private static Job map(ResultSet rs, int ignored) throws SQLException {
    return new Job(
        rs.getObject("id", UUID.class),
        rs.getString("project_id"),
        rs.getString("org_id"),
        rs.getString("requested_by_user_id"),
        rs.getString("doc_type"),
        rs.getString("requested_provider"),
        rs.getString("requested_model"),
        Status.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"),
        instant(rs, "next_attempt_at"),
        instant(rs, "deadline_at"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        instant(rs, "provider_started_at"),
        rs.getObject("result_doc_id", UUID.class),
        (Integer) rs.getObject("result_doc_version"),
        rs.getString("error_code"),
        rs.getString("error_message"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String bounded(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }
}
