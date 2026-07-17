package com.navneet.executespec.export;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** V289 database state machine for durable project-export work. */
@Component
@RequiredArgsConstructor
public class ProjectExportJobStore {

  public enum Status {
    QUEUED,
    RUNNING,
    CANCEL_REQUESTED,
    CANCELLED,
    SUCCEEDED,
    FAILED,
    EXPIRING,
    EXPIRED;

    public boolean terminal() {
      return this == CANCELLED || this == SUCCEEDED || this == FAILED || this == EXPIRED;
    }
  }

  public record Job(
      UUID id,
      String projectId,
      String orgId,
      String requestedByUserId,
      String idempotencyKey,
      Status status,
      String fileName,
      String contentType,
      String objectKey,
      Long sizeBytes,
      String sha256,
      int attemptCount,
      Instant nextAttemptAt,
      String leaseOwner,
      long leaseToken,
      Instant leaseExpiresAt,
      String errorCode,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant expiresAt,
      Instant cancelRequestedAt,
      Instant lastDownloadedAt,
      long downloadCount) {}

  public record Lease(Job job, String owner, long fencingToken, Instant expiresAt) {}

  public record AuditEvent(
      UUID id,
      UUID jobId,
      String projectId,
      String orgId,
      String actorUserId,
      String eventType,
      String outcome,
      String details,
      Instant occurredAt) {}

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  /**
   * Insert exactly once for a caller-supplied idempotency key. A different request racing on the
   * per-project active-job index receives the already-active job instead of creating parallel
   * archive work.
   */
  @Transactional
  public Job createOrGet(
      UUID id,
      String projectId,
      String orgId,
      String userId,
      String idempotencyKey,
      String fileName) {
    int inserted =
        jdbc.update(
            """
            INSERT INTO project_export_job
              (id, project_id, org_id, requested_by_user_id, idempotency_key, file_name)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """,
            id,
            projectId,
            orgId,
            userId,
            idempotencyKey,
            fileName);
    if (inserted == 1) {
      Job job = get(id).orElseThrow();
      audit(job, userId, "REQUESTED", "QUEUED", Map.of("idempotentReplay", false));
      return job;
    }
    return findByIdempotency(projectId, userId, idempotencyKey)
        .or(() -> findActive(projectId))
        .orElseThrow(() -> new IllegalStateException("project export insert conflict was lost"));
  }

  public Optional<Job> get(UUID id) {
    return query("SELECT * FROM project_export_job WHERE id=?", id).stream().findFirst();
  }

  public Optional<Job> getScoped(UUID id, String projectId, String orgId) {
    return query(
            "SELECT * FROM project_export_job WHERE id=? AND project_id=? AND org_id=?",
            id,
            projectId,
            orgId)
        .stream()
        .findFirst();
  }

  public Optional<Job> findByIdempotency(String projectId, String userId, String idempotencyKey) {
    return query(
            """
            SELECT * FROM project_export_job
            WHERE project_id=? AND requested_by_user_id=? AND idempotency_key=?
            """,
            projectId,
            userId,
            idempotencyKey)
        .stream()
        .findFirst();
  }

  public Optional<Job> findActive(String projectId) {
    return query(
            """
            SELECT * FROM project_export_job
            WHERE project_id=? AND status IN ('QUEUED','RUNNING','CANCEL_REQUESTED')
            ORDER BY created_at DESC LIMIT 1
            """,
            projectId)
        .stream()
        .findFirst();
  }

  public List<Job> listProject(String projectId, String orgId, int limit) {
    return query(
        """
        SELECT * FROM project_export_job
        WHERE project_id=? AND org_id=? ORDER BY created_at DESC LIMIT ?
        """,
        projectId,
        orgId,
        limit);
  }

  public long countCreatedSince(String projectId, Instant since) {
    Long count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM project_export_job WHERE project_id=? AND created_at>=?",
            Long.class,
            projectId,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  public List<UUID> claimCandidates(int limit, int maxAttempts) {
    return jdbc.query(
        """
        SELECT id FROM project_export_job
        WHERE attempt_count < ? AND (
          (status='QUEUED' AND next_attempt_at<=NOW())
          OR (status='RUNNING' AND lease_expires_at<NOW())
        )
        ORDER BY next_attempt_at, created_at LIMIT ?
        """,
        (rs, ignored) -> rs.getObject(1, UUID.class),
        maxAttempts,
        limit);
  }

  /** One atomic claim/expired-lease takeover. The returned token fences every later transition. */
  @Transactional
  public Optional<Lease> tryAcquire(
      UUID id, String owner, Duration leaseDuration, int maxAttempts) {
    Instant expiresAt = Instant.now().plus(leaseDuration);
    List<Job> claimed =
        jdbc.query(
            """
            UPDATE project_export_job SET
              status='RUNNING', lease_owner=?, lease_token=lease_token+1,
              lease_expires_at=?, attempt_count=attempt_count+1,
              started_at=COALESCE(started_at, NOW()), error_code=NULL
            WHERE id=? AND attempt_count<? AND (
              (status='QUEUED' AND next_attempt_at<=NOW())
              OR (status='RUNNING' AND lease_expires_at<NOW())
            )
            RETURNING *
            """,
            ProjectExportJobStore::mapJob,
            owner,
            Timestamp.from(expiresAt),
            id,
            maxAttempts);
    if (claimed.isEmpty()) return Optional.empty();
    Job job = claimed.getFirst();
    audit(
        job,
        null,
        job.attemptCount() == 1 ? "STARTED" : "RECOVERED",
        "RUNNING",
        Map.of("attempt", job.attemptCount(), "leaseToken", job.leaseToken()));
    return Optional.of(new Lease(job, owner, job.leaseToken(), expiresAt));
  }

  public boolean renew(Lease lease, Duration leaseDuration) {
    return jdbc.update(
            """
            UPDATE project_export_job SET lease_expires_at=?
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            Timestamp.from(Instant.now().plus(leaseDuration)),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean cancellationRequested(Lease lease) {
    List<Boolean> requested =
        jdbc.query(
            """
            SELECT status='CANCEL_REQUESTED' FROM project_export_job
            WHERE id=? AND lease_owner=? AND lease_token=?
            """,
            (rs, ignored) -> rs.getBoolean(1),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    return !requested.isEmpty() && Boolean.TRUE.equals(requested.getFirst());
  }

  @Transactional
  public Optional<Job> requestCancellation(
      UUID id, String projectId, String orgId, String actorUserId) {
    List<Job> changed =
        jdbc.query(
            """
            UPDATE project_export_job SET
              status=CASE WHEN status='QUEUED' THEN 'CANCELLED' ELSE 'CANCEL_REQUESTED' END,
              cancel_requested_at=COALESCE(cancel_requested_at, NOW()),
              completed_at=CASE WHEN status='QUEUED' THEN NOW() ELSE completed_at END,
              lease_owner=CASE WHEN status='QUEUED' THEN NULL ELSE lease_owner END,
              lease_expires_at=CASE WHEN status='QUEUED' THEN NULL ELSE lease_expires_at END
            WHERE id=? AND project_id=? AND org_id=?
              AND status IN ('QUEUED','RUNNING')
            RETURNING *
            """,
            ProjectExportJobStore::mapJob,
            id,
            projectId,
            orgId);
    Job job =
        changed.stream().findFirst().orElseGet(() -> getScoped(id, projectId, orgId).orElse(null));
    if (!changed.isEmpty()) {
      audit(job, actorUserId, "CANCEL_REQUESTED", job.status().name(), Map.of());
    }
    return Optional.ofNullable(job);
  }

  @Transactional
  public boolean complete(
      Lease lease, String objectKey, long sizeBytes, String sha256, Instant expiresAt) {
    int updated =
        jdbc.update(
            """
            UPDATE project_export_job SET status='SUCCEEDED', object_key=?, size_bytes=?,
              sha256=?, completed_at=NOW(), expires_at=?, lease_owner=NULL,
              lease_expires_at=NULL, error_code=NULL
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
              AND lease_expires_at>NOW()
            """,
            objectKey,
            sizeBytes,
            sha256,
            Timestamp.from(expiresAt),
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    if (updated == 1) {
      Job job = get(lease.job().id()).orElseThrow();
      audit(job, null, "COMPLETED", "SUCCEEDED", Map.of("sizeBytes", sizeBytes, "sha256", sha256));
      return true;
    }
    return false;
  }

  @Transactional
  public Status failOrRequeue(Lease lease, String errorCode, int maxAttempts, Duration retryDelay) {
    Job current = get(lease.job().id()).orElseThrow();
    boolean retry = current.attemptCount() < maxAttempts;
    String nextStatus = retry ? "QUEUED" : "FAILED";
    int updated =
        jdbc.update(
            """
            UPDATE project_export_job SET status=?, error_code=?, next_attempt_at=?,
              completed_at=CASE WHEN ?='FAILED' THEN NOW() ELSE NULL END,
              lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='RUNNING' AND lease_owner=? AND lease_token=?
            """,
            nextStatus,
            boundedCode(errorCode),
            Timestamp.from(Instant.now().plus(retryDelay)),
            nextStatus,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    if (updated != 1) return current.status();
    Job job = get(lease.job().id()).orElseThrow();
    audit(
        job,
        null,
        retry ? "RETRY_SCHEDULED" : "FAILED",
        nextStatus,
        Map.of("errorCode", boundedCode(errorCode), "attempt", current.attemptCount()));
    return Status.valueOf(nextStatus);
  }

  @Transactional
  public boolean finishCancellation(Lease lease) {
    int updated =
        jdbc.update(
            """
            UPDATE project_export_job SET status='CANCELLED', completed_at=NOW(),
              lease_owner=NULL, lease_expires_at=NULL
            WHERE id=? AND status='CANCEL_REQUESTED' AND lease_owner=? AND lease_token=?
            """,
            lease.job().id(),
            lease.owner(),
            lease.fencingToken());
    if (updated == 1) {
      Job job = get(lease.job().id()).orElseThrow();
      audit(job, null, "CANCELLED", "CANCELLED", Map.of());
    }
    return updated == 1;
  }

  /**
   * Finish cooperative cancellations whose worker disappeared. Live workers retain their lease
   * until they observe the cancellation; only an absent/expired owner is recovered here.
   */
  @Transactional
  public int finishAbandonedCancellations() {
    List<Job> jobs =
        jdbc.query(
            """
            UPDATE project_export_job SET status='CANCELLED', completed_at=NOW(),
              lease_owner=NULL, lease_expires_at=NULL
            WHERE status='CANCEL_REQUESTED'
              AND (lease_expires_at IS NULL OR lease_expires_at<NOW())
            RETURNING *
            """,
            ProjectExportJobStore::mapJob);
    jobs.forEach(
        job ->
            audit(job, null, "CANCELLED", "CANCELLED", Map.of("recoveredAfterWorkerLoss", true)));
    return jobs.size();
  }

  /** Permanently fail stale rows that exhausted attempts before another JVM could claim them. */
  @Transactional
  public int failExhaustedStale(int maxAttempts) {
    List<Job> jobs =
        jdbc.query(
            """
            UPDATE project_export_job SET status='FAILED', error_code='MAX_ATTEMPTS_EXHAUSTED',
              completed_at=NOW(), lease_owner=NULL, lease_expires_at=NULL
            WHERE status='RUNNING' AND lease_expires_at<NOW() AND attempt_count>=?
            RETURNING *
            """,
            ProjectExportJobStore::mapJob,
            maxAttempts);
    jobs.forEach(
        job ->
            audit(
                job,
                null,
                "FAILED",
                "MAX_ATTEMPTS_EXHAUSTED",
                Map.of("attempt", job.attemptCount())));
    return jobs.size();
  }

  public List<Job> expiryCandidates(int limit) {
    return query(
        """
        SELECT * FROM project_export_job
        WHERE (status='SUCCEEDED' AND expires_at<=NOW()) OR status='EXPIRING'
        ORDER BY expires_at NULLS FIRST LIMIT ?
        """,
        limit);
  }

  @Transactional
  public Optional<Job> beginExpiry(UUID id) {
    List<Job> changed =
        jdbc.query(
            """
            UPDATE project_export_job SET status='EXPIRING'
            WHERE id=? AND status='SUCCEEDED' AND expires_at<=NOW() RETURNING *
            """,
            ProjectExportJobStore::mapJob,
            id);
    if (!changed.isEmpty()) {
      Job job = changed.getFirst();
      audit(job, null, "EXPIRY_STARTED", "EXPIRING", Map.of());
      return Optional.of(job);
    }
    return get(id).filter(job -> job.status() == Status.EXPIRING);
  }

  @Transactional
  public boolean finishExpiry(UUID id, String objectKey) {
    int updated =
        jdbc.update(
            """
            UPDATE project_export_job SET status='EXPIRED', object_key=NULL
            WHERE id=? AND status='EXPIRING' AND object_key=?
            """,
            id,
            objectKey);
    if (updated == 1) {
      Job job = get(id).orElseThrow();
      audit(job, null, "EXPIRED", "EXPIRED", Map.of());
    }
    return updated == 1;
  }

  @Transactional
  public boolean recordDownload(UUID id, String projectId, String orgId, String actorUserId) {
    int updated =
        jdbc.update(
            """
            UPDATE project_export_job SET download_count=download_count+1, last_downloaded_at=NOW()
            WHERE id=? AND project_id=? AND org_id=? AND status='SUCCEEDED'
              AND expires_at>NOW()
            """,
            id,
            projectId,
            orgId);
    if (updated == 1) {
      Job job = get(id).orElseThrow();
      audit(
          job,
          actorUserId,
          "DOWNLOAD_ISSUED",
          "ALLOWED",
          Map.of("downloadCount", job.downloadCount()));
    }
    return updated == 1;
  }

  public List<AuditEvent> listAudit(UUID jobId, String projectId, String orgId) {
    return jdbc.query(
        """
        SELECT * FROM project_export_audit
        WHERE job_id=? AND project_id=? AND org_id=? ORDER BY occurred_at DESC LIMIT 100
        """,
        ProjectExportJobStore::mapAudit,
        jobId,
        projectId,
        orgId);
  }

  private void audit(
      Job job, String actorUserId, String eventType, String outcome, Map<String, Object> details) {
    jdbc.update(
        """
        INSERT INTO project_export_audit
          (id, job_id, project_id, org_id, actor_user_id, event_type, outcome, details)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """,
        UUID.randomUUID(),
        job.id(),
        job.projectId(),
        job.orgId(),
        blankToNull(actorUserId),
        eventType,
        outcome,
        json(details));
  }

  private List<Job> query(String sql, Object... args) {
    return jdbc.query(sql, ProjectExportJobStore::mapJob, args);
  }

  private static Job mapJob(ResultSet rs, int ignored) throws SQLException {
    Long size = rs.getObject("size_bytes", Long.class);
    return new Job(
        rs.getObject("id", UUID.class),
        rs.getString("project_id"),
        rs.getString("org_id"),
        rs.getString("requested_by_user_id"),
        rs.getString("idempotency_key"),
        Status.valueOf(rs.getString("status")),
        rs.getString("file_name"),
        rs.getString("content_type"),
        rs.getString("object_key"),
        size,
        rs.getString("sha256"),
        rs.getInt("attempt_count"),
        instant(rs, "next_attempt_at"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        rs.getString("error_code"),
        instant(rs, "created_at"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"),
        instant(rs, "expires_at"),
        instant(rs, "cancel_requested_at"),
        instant(rs, "last_downloaded_at"),
        rs.getLong("download_count"));
  }

  private static AuditEvent mapAudit(ResultSet rs, int ignored) throws SQLException {
    return new AuditEvent(
        rs.getObject("id", UUID.class),
        rs.getObject("job_id", UUID.class),
        rs.getString("project_id"),
        rs.getString("org_id"),
        rs.getString("actor_user_id"),
        rs.getString("event_type"),
        rs.getString("outcome"),
        rs.getString("details"),
        instant(rs, "occurred_at"));
  }

  private String json(Map<String, Object> details) {
    try {
      return mapper.writeValueAsString(details == null ? Map.of() : details);
    } catch (JsonProcessingException impossible) {
      return "{}";
    }
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String boundedCode(String code) {
    if (code == null || code.isBlank()) return "EXPORT_FAILED";
    String normalized = code.replaceAll("[^A-Z0-9_]", "_").toUpperCase();
    return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
