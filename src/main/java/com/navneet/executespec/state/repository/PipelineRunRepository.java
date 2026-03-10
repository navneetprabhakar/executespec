package com.navneet.executespec.state.repository;

import com.navneet.executespec.state.entity.PipelineRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, String>, JpaSpecificationExecutor<PipelineRun> {

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
}
