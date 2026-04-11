package com.navneet.executespec.state.repository;

import com.navneet.executespec.state.entity.PipelineRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, String>, JpaSpecificationExecutor<PipelineRun> {

    // ── Atomic token counter updates (avoids read-modify-write race) ─────────

    @Modifying
    @Query("UPDATE PipelineRun r SET r.totalInputTokens = r.totalInputTokens + :inp, r.totalOutputTokens = r.totalOutputTokens + :out WHERE r.runId = :runId")
    void addTokens(@Param("runId") String runId, @Param("inp") int inp, @Param("out") int out);

    /** Atomic status update that only touches status + currentGate — avoids overwriting concurrent field changes. */
    @Modifying
    @Query("UPDATE PipelineRun r SET r.status = :status, r.currentGate = NULL WHERE r.runId = :runId")
    void updateStatusAndClearGate(@Param("runId") String runId, @Param("status") String status);

    /** Atomic execution mode update — avoids read-modify-write race with clearGateSync/markApproved. */
    @Modifying
    @Query("UPDATE PipelineRun r SET r.approvedExecutionMode = :mode WHERE r.runId = :runId")
    void updateApprovedExecutionMode(@Param("runId") String runId, @Param("mode") String mode);

    /** Atomic currentGate clear — avoids full entity save that could overwrite concurrent field changes. */
    @Modifying
    @Query("UPDATE PipelineRun r SET r.currentGate = NULL WHERE r.runId = :runId")
    void clearCurrentGate(@Param("runId") String runId);

    List<PipelineRun> findByStatusIn(List<String> statuses);

    Optional<PipelineRun> findTopByStatusInOrderByStartedAtDesc(List<String> statuses);

    // ── Tier 3 additions ─────────────────────────────────────────────────────
    Page<PipelineRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Page<PipelineRun> findByStatusOrderByStartedAtDesc(String status, Pageable pageable);

    long countByStatus(String status);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens), 0) FROM PipelineRun r")
    Long sumInputTokens();

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens), 0) FROM PipelineRun r")
    Long sumOutputTokens();

    // ── Org-filtered metrics ────────────────────────────────────────────────────

    long countByOrgId(String orgId);

    long countByOrgIdAndStatus(String orgId, String status);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens), 0) FROM PipelineRun r WHERE r.orgId = :orgId")
    Long sumInputTokensByOrgId(@Param("orgId") String orgId);

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens), 0) FROM PipelineRun r WHERE r.orgId = :orgId")
    Long sumOutputTokensByOrgId(@Param("orgId") String orgId);

    // ── Date-filtered global metrics ──────────────────────────────────────────

    long countByStartedAtGreaterThanEqual(Instant since);

    long countByStatusAndStartedAtGreaterThanEqual(String status, Instant since);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens), 0) FROM PipelineRun r WHERE r.startedAt >= :since")
    Long sumInputTokensByStartedAtAfter(@Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens), 0) FROM PipelineRun r WHERE r.startedAt >= :since")
    Long sumOutputTokensByStartedAtAfter(@Param("since") Instant since);

    // ── Date-filtered org-scoped metrics ────────────────────────────────────────

    long countByOrgIdAndStartedAtGreaterThanEqual(String orgId, Instant since);

    long countByOrgIdAndStatusAndStartedAtGreaterThanEqual(String orgId, String status, Instant since);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens), 0) FROM PipelineRun r WHERE r.orgId = :orgId AND r.startedAt >= :since")
    Long sumInputTokensByOrgIdAndStartedAtAfter(@Param("orgId") String orgId, @Param("since") Instant since);

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens), 0) FROM PipelineRun r WHERE r.orgId = :orgId AND r.startedAt >= :since")
    Long sumOutputTokensByOrgIdAndStartedAtAfter(@Param("orgId") String orgId, @Param("since") Instant since);

    // ── Usage report queries (date-range + per-user/org) ───────────────────────

    long countByCreatedByUserIdAndStartedAtBetween(String userId, Instant start, Instant end);

    long countByCreatedByUserIdAndStatusAndStartedAtBetween(String userId, String status, Instant start, Instant end);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens),0) FROM PipelineRun r WHERE r.createdByUserId = :uid AND r.startedAt BETWEEN :s AND :e")
    Long sumInputTokensByUserAndDateRange(@Param("uid") String userId, @Param("s") Instant start, @Param("e") Instant end);

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens),0) FROM PipelineRun r WHERE r.createdByUserId = :uid AND r.startedAt BETWEEN :s AND :e")
    Long sumOutputTokensByUserAndDateRange(@Param("uid") String userId, @Param("s") Instant start, @Param("e") Instant end);

    long countByOrgIdAndStartedAtBetween(String orgId, Instant start, Instant end);

    long countByOrgIdAndStatusAndStartedAtBetween(String orgId, String status, Instant start, Instant end);

    @Query("SELECT COALESCE(SUM(r.totalInputTokens),0) FROM PipelineRun r WHERE r.orgId = :oid AND r.startedAt BETWEEN :s AND :e")
    Long sumInputTokensByOrgAndDateRange(@Param("oid") String orgId, @Param("s") Instant start, @Param("e") Instant end);

    @Query("SELECT COALESCE(SUM(r.totalOutputTokens),0) FROM PipelineRun r WHERE r.orgId = :oid AND r.startedAt BETWEEN :s AND :e")
    Long sumOutputTokensByOrgAndDateRange(@Param("oid") String orgId, @Param("s") Instant start, @Param("e") Instant end);

    @Query("SELECT r.createdByUserId, COUNT(r), COALESCE(SUM(r.totalInputTokens + r.totalOutputTokens),0) FROM PipelineRun r WHERE r.orgId = :oid AND r.startedAt BETWEEN :s AND :e GROUP BY r.createdByUserId ORDER BY COUNT(r) DESC")
    List<Object[]> topUsersByRunCount(@Param("oid") String orgId, @Param("s") Instant start, @Param("e") Instant end, Pageable pageable);

    @Query("SELECT r.projectId, COUNT(r), COALESCE(SUM(r.totalInputTokens + r.totalOutputTokens),0) FROM PipelineRun r WHERE r.orgId = :oid AND r.startedAt BETWEEN :s AND :e AND r.projectId IS NOT NULL GROUP BY r.projectId ORDER BY COUNT(r) DESC")
    List<Object[]> projectBreakdownByOrg(@Param("oid") String orgId, @Param("s") Instant start, @Param("e") Instant end);

    @Query("SELECT r.projectId, COUNT(r), COALESCE(SUM(r.totalInputTokens + r.totalOutputTokens),0) FROM PipelineRun r WHERE r.createdByUserId = :uid AND r.startedAt BETWEEN :s AND :e AND r.projectId IS NOT NULL GROUP BY r.projectId ORDER BY COUNT(r) DESC")
    List<Object[]> projectBreakdownByUser(@Param("uid") String userId, @Param("s") Instant start, @Param("e") Instant end);

    // ── Run title backfill ────────────────────────────────────────────────────

    /** Finds runs that have a plan but no run_title yet (for one-time backfill on startup). */
    @Query("SELECT r FROM PipelineRun r WHERE r.runTitle IS NULL AND r.planJson IS NOT NULL AND r.planJson <> ''")
    List<PipelineRun> findRunsWithoutTitle();

    // ── GCS workspace archiving ───────────────────────────────────────────────

    /**
     * Finds completed/failed runs whose workspace has not yet been archived to GCS,
     * where the run finished more than {@code cutoff} ago. Processed in batches of 50.
     */
    @Query("""
        SELECT r FROM PipelineRun r
        WHERE r.status IN ('COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED')
          AND r.completedAt < :cutoff
          AND r.gcsArchivedAt IS NULL
          AND r.gcsWorkspaceKey IS NULL
        ORDER BY r.completedAt ASC
        """)
    List<PipelineRun> findRunsToArchive(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Finds terminal runs whose local workspace should be cleaned up.
     * Includes runs already uploaded to GCS (gcsWorkspaceKey set) but not yet
     * locally deleted (gcsArchivedAt still null).
     */
    @Query("""
        SELECT r FROM PipelineRun r
        WHERE r.status IN ('COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED')
          AND r.completedAt < :cutoff
          AND r.gcsArchivedAt IS NULL
        ORDER BY r.completedAt ASC
        """)
    List<PipelineRun> findRunsToCleanup(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Finds terminal runs that have NOT been uploaded to GCS yet.
     * Used by the 5-minute fallback sweep in WorkspaceGcsUploadListener.
     */
    @Query("""
        SELECT r FROM PipelineRun r
        WHERE r.status IN ('COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED')
          AND r.completedAt < :cutoff
          AND r.gcsWorkspaceKey IS NULL
        ORDER BY r.completedAt ASC
        """)
    List<PipelineRun> findTerminalRunsWithoutGcsKey(@Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Finds archived runs older than the given cutoff (for 90-day hard-delete cleanup).
     */
    @Query("SELECT r FROM PipelineRun r WHERE r.archivedAt IS NOT NULL AND r.archivedAt < :cutoff")
    List<PipelineRun> findArchivedRunsOlderThan(@Param("cutoff") Instant cutoff);

    /** Returns runs completed before cutoff, for a given tier, that have not yet been soft-deleted. */
    @Query("SELECT r FROM PipelineRun r WHERE r.userTier = :tier " +
           "AND r.completedAt IS NOT NULL AND r.completedAt < :cutoff " +
           "AND r.deletedAt IS NULL")
    List<PipelineRun> findExpiredRunsForTier(@Param("tier") String tier, @Param("cutoff") Instant cutoff);

    /** Returns soft-deleted runs that are ready for hard deletion. */
    @Query("SELECT r FROM PipelineRun r WHERE r.deletedAt IS NOT NULL AND r.deletedAt < :cutoff")
    List<PipelineRun> findSoftDeletedOlderThan(@Param("cutoff") Instant cutoff);

    /** Returns QUEUED runs that were queued due to a provider outage (for automatic retry). */
    @Query("SELECT r FROM PipelineRun r WHERE r.status = 'QUEUED' " +
           "AND r.failureReason LIKE '%providers unavailable%'")
    List<PipelineRun> findOutageQueuedRuns();

    /** Soft-deletes all non-active runs belonging to a user (GDPR right-to-erasure). */
    @Modifying
    @Query("UPDATE PipelineRun r SET r.deletedAt = :now " +
           "WHERE r.createdByUserId = :userId AND r.deletedAt IS NULL " +
           "AND r.status NOT IN ('PENDING', 'PLANNING', 'IN_PROGRESS', 'IN_PROGRESS_BATCH')")
    int softDeleteByUserId(@Param("userId") String userId, @Param("now") Instant now);
}
