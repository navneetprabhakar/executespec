package com.navneet.executespec.project.rag;

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

import lombok.RequiredArgsConstructor;

/**
 * Durable control-plane state for source-code semantic indexing (V286).
 *
 * <p>The database lease is deliberately scoped to {@code (projectId, repoId)}. A repository may be
 * linked to several projects, and neither its chunks nor its readiness may bleed across those
 * tenant contexts. Every mutating chunk operation also checks the monotonically increasing fencing
 * token through {@link CodeChunkStore}; acquiring a newer lease therefore makes a stale JVM unable
 * to publish.
 */
@Component
@RequiredArgsConstructor
public class CodeIndexStateStore {

  public enum Status {
    UNINDEXED,
    INDEXING,
    READY,
    PARTIAL,
    STALE,
    FAILED
  }

  public record State(
      String projectId,
      UUID repoId,
      String sourceSha,
      Status status,
      String statusReason,
      int discoveredFiles,
      int eligibleFiles,
      int indexedFiles,
      int refreshedFiles,
      int unchangedFiles,
      int skippedFiles,
      int deletedFiles,
      int failedFiles,
      int indexedChunks,
      double coveragePercent,
      boolean inventoryTruncated,
      String leaseOwner,
      long leaseToken,
      Instant leaseExpiresAt,
      Instant startedAt,
      Instant completedAt,
      Instant updatedAt) {

    public boolean semanticallyReadyFor(String currentSourceSha) {
      return status == Status.READY && sourceSha.equals(currentSourceSha);
    }
  }

  public record Lease(
      String projectId,
      UUID repoId,
      String sourceSha,
      String owner,
      long fencingToken,
      Instant expiresAt) {}

  public record Completion(
      Status status,
      String reason,
      int discoveredFiles,
      int eligibleFiles,
      int indexedFiles,
      int refreshedFiles,
      int unchangedFiles,
      int skippedFiles,
      int deletedFiles,
      int failedFiles,
      int indexedChunks,
      double coveragePercent,
      boolean inventoryTruncated) {

    public Completion {
      if (status != Status.READY && status != Status.PARTIAL) {
        throw new IllegalArgumentException("completion status must be READY or PARTIAL");
      }
    }
  }

  private final JdbcTemplate jdbc;

  /**
   * Convert abandoned INDEXING rows and source-revision drift into durable STALE state. An active
   * unexpired owner is never pre-empted here; the new owner can claim only after expiry.
   */
  public void reconcile(String projectId, UUID repoId, String currentSourceSha) {
    jdbc.update(
        "UPDATE project_code_index_state SET status='STALE', status_reason='LEASE_EXPIRED',"
            + " lease_owner=NULL, lease_expires_at=NULL, updated_at=NOW()"
            + " WHERE project_id=? AND repo_id=? AND status='INDEXING'"
            + " AND lease_expires_at IS NOT NULL AND lease_expires_at < NOW()",
        projectId,
        repoId);
    jdbc.update(
        "UPDATE project_code_index_state SET status='STALE',"
            + " status_reason='SOURCE_REVISION_CHANGED', updated_at=NOW()"
            + " WHERE project_id=? AND repo_id=? AND source_sha<>? AND status<>'INDEXING'",
        projectId,
        repoId,
        currentSourceSha);
  }

  public Optional<State> get(String projectId, UUID repoId) {
    List<State> states =
        jdbc.query(
            "SELECT * FROM project_code_index_state WHERE project_id=? AND repo_id=?",
            CodeIndexStateStore::map,
            projectId,
            repoId);
    return states.stream().findFirst();
  }

  public List<State> listProject(String projectId) {
    return jdbc.query(
        "SELECT * FROM project_code_index_state WHERE project_id=? ORDER BY repo_id",
        CodeIndexStateStore::map,
        projectId);
  }

  /**
   * Acquire or steal an expired lease. {@code RETURNING lease_token} makes acquisition one atomic
   * database statement; a loser receives {@link Optional#empty()} and performs no embedding work.
   */
  public Optional<Lease> tryAcquire(
      String projectId, UUID repoId, String sourceSha, String owner, Duration duration) {
    Instant expiresAt = Instant.now().plus(duration);
    List<Long> tokens =
        jdbc.query(
            "INSERT INTO project_code_index_state(project_id, repo_id, source_sha, status,"
                + " status_reason, lease_owner, lease_token, lease_expires_at, started_at,"
                + " completed_at, updated_at) VALUES (?, ?, ?, 'INDEXING', NULL, ?, 1, ?, NOW(),"
                + " NULL, NOW()) ON CONFLICT(project_id, repo_id) DO UPDATE SET"
                + " source_sha=EXCLUDED.source_sha, status='INDEXING', status_reason=NULL,"
                + " discovered_files=0, eligible_files=0, indexed_files=0, refreshed_files=0,"
                + " unchanged_files=0, skipped_files=0, deleted_files=0, failed_files=0,"
                + " indexed_chunks=0, coverage_percent=0, inventory_truncated=FALSE,"
                + " lease_owner=EXCLUDED.lease_owner,"
                + " lease_token=project_code_index_state.lease_token+1,"
                + " lease_expires_at=EXCLUDED.lease_expires_at, started_at=NOW(),"
                + " completed_at=NULL, updated_at=NOW() WHERE"
                + " project_code_index_state.lease_expires_at IS NULL OR"
                + " project_code_index_state.lease_expires_at < NOW() RETURNING lease_token",
            (rs, rowNum) -> rs.getLong(1),
            projectId,
            repoId,
            sourceSha,
            owner,
            Timestamp.from(expiresAt));
    return tokens.stream()
        .findFirst()
        .map(token -> new Lease(projectId, repoId, sourceSha, owner, token, expiresAt));
  }

  /** Renew only the exact current owner/token. False means the caller must stop publishing. */
  public boolean renew(Lease lease, Duration duration) {
    Instant expiresAt = Instant.now().plus(duration);
    return jdbc.update(
            "UPDATE project_code_index_state SET lease_expires_at=?, updated_at=NOW()"
                + " WHERE project_id=? AND repo_id=? AND status='INDEXING' AND lease_owner=?"
                + " AND lease_token=? AND lease_expires_at>NOW()",
            Timestamp.from(expiresAt),
            lease.projectId(),
            lease.repoId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean complete(Lease lease, Completion result) {
    return jdbc.update(
            "UPDATE project_code_index_state SET status=?, status_reason=?, discovered_files=?,"
                + " eligible_files=?, indexed_files=?, refreshed_files=?, unchanged_files=?,"
                + " skipped_files=?, deleted_files=?, failed_files=?, indexed_chunks=?,"
                + " coverage_percent=?, inventory_truncated=?, lease_owner=NULL,"
                + " lease_expires_at=NULL, completed_at=NOW(), updated_at=NOW() WHERE project_id=?"
                + " AND repo_id=? AND status='INDEXING' AND lease_owner=? AND lease_token=? AND"
                + " lease_expires_at>NOW()",
            result.status().name(),
            boundedReason(result.reason()),
            result.discoveredFiles(),
            result.eligibleFiles(),
            result.indexedFiles(),
            result.refreshedFiles(),
            result.unchangedFiles(),
            result.skippedFiles(),
            result.deletedFiles(),
            result.failedFiles(),
            result.indexedChunks(),
            Math.max(0, Math.min(100, result.coveragePercent())),
            result.inventoryTruncated(),
            lease.projectId(),
            lease.repoId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  public boolean fail(Lease lease, String reason) {
    return jdbc.update(
            "UPDATE project_code_index_state SET status='FAILED', status_reason=?,"
                + " lease_owner=NULL, lease_expires_at=NULL, completed_at=NOW(), updated_at=NOW()"
                + " WHERE project_id=? AND repo_id=? AND status='INDEXING' AND lease_owner=? AND"
                + " lease_token=?",
            boundedReason(reason),
            lease.projectId(),
            lease.repoId(),
            lease.owner(),
            lease.fencingToken())
        == 1;
  }

  private static State map(ResultSet rs, int ignored) throws SQLException {
    return new State(
        rs.getString("project_id"),
        rs.getObject("repo_id", UUID.class),
        rs.getString("source_sha"),
        Status.valueOf(rs.getString("status")),
        rs.getString("status_reason"),
        rs.getInt("discovered_files"),
        rs.getInt("eligible_files"),
        rs.getInt("indexed_files"),
        rs.getInt("refreshed_files"),
        rs.getInt("unchanged_files"),
        rs.getInt("skipped_files"),
        rs.getInt("deleted_files"),
        rs.getInt("failed_files"),
        rs.getInt("indexed_chunks"),
        rs.getDouble("coverage_percent"),
        rs.getBoolean("inventory_truncated"),
        rs.getString("lease_owner"),
        rs.getLong("lease_token"),
        instant(rs, "lease_expires_at"),
        instant(rs, "started_at"),
        instant(rs, "completed_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static String boundedReason(String reason) {
    if (reason == null || reason.isBlank()) return null;
    String normalized = reason.replaceAll("[\\r\\n\\t]", " ").trim();
    return normalized.length() <= 255 ? normalized : normalized.substring(0, 255);
  }
}
