package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {
    /**
     * Default list query. Kept as-is for callers that only need ExecutionRun
     * column fields (fast path). UI endpoints that render nested solution data
     * should use {@link #findListByProjectId} instead to avoid N+1.
     */
    Page<ExecutionRun> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(String projectId, Pageable pageable);

    /**
     * v2.5.2 — list-mode fetch with solutions pre-joined so the Launches list
     * (which renders per-solution summary) doesn't fire one query per row.
     *
     * <p>We intentionally only join {@code solutions} here — not
     * {@code solutions.repoMappings}. Hibernate cannot JOIN FETCH two List-typed
     * collections in one query (MultipleBagFetchException). The repoMappings
     * are loaded lazily per-solution during DTO mapping under OSIV. Trade-off:
     * (N solutions) queries for repo mappings vs. rewriting both associations
     * to Set — which would require entity-level changes and test coverage that
     * isn't worth the marginal savings on the Launches list (typically < 20
     * solutions per page). {@code dependencies} stays lazy.
     */
    @EntityGraph(attributePaths = {"solutions"})
    @Query("SELECT e FROM ExecutionRun e WHERE e.projectId = :projectId " +
            "AND e.deletedAt IS NULL ORDER BY e.createdAt DESC")
    Page<ExecutionRun> findListByProjectId(@Param("projectId") String projectId, Pageable pageable);

    /**
     * v2.5.2 — detail fetch for a single ExecutionRun with solutions joined.
     * Used by {@code GET /api/executions/{id}} so the page renders in one
     * round-trip. {@code repoMappings} loaded lazily during DTO mapping (see
     * note on {@link #findListByProjectId}).
     */
    @EntityGraph(attributePaths = {"solutions"})
    @Query("SELECT e FROM ExecutionRun e WHERE e.id = :id")
    Optional<ExecutionRun> findDetailById(@Param("id") String id);

    @Query("SELECT e FROM ExecutionRun e " +
            "WHERE e.status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'ABORTED') " +
            "AND e.updatedAt < :cutoff")
    List<ExecutionRun> findStaleNonTerminal(@Param("cutoff") Instant cutoff);

    /**
     * v2.5.7 — count non-terminal executions for a project. Used to gate
     * {@code POST /api/projects/{id}/executions} so a project can only have
     * one active Launch at a time (avoids concurrent multi-repo conflicts).
     */
    @Query("SELECT COUNT(e) FROM ExecutionRun e WHERE e.projectId = :projectId " +
            "AND e.deletedAt IS NULL " +
            "AND e.status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'ABORTED')")
    long countActiveByProjectId(@Param("projectId") String projectId);

    /**
     * v2.5.7 — returns the full active ExecutionRun rows so the controller can
     * craft a specific 409 message naming the blocking run and its actual status.
     */
    @Query("SELECT e FROM ExecutionRun e WHERE e.projectId = :projectId " +
            "AND e.deletedAt IS NULL " +
            "AND e.status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'ABORTED') " +
            "ORDER BY e.createdAt DESC")
    List<ExecutionRun> findActiveByProjectId(@Param("projectId") String projectId);

    /** v2.5.8 — per-user $ spent on Launches in a billing period. */
    @Query("SELECT COALESCE(SUM(e.totalCostUsd), 0.0) FROM ExecutionRun e " +
            "WHERE e.createdBy = :userId AND e.createdAt >= :since AND e.deletedAt IS NULL")
    Double sumTotalCostByUserAndSince(@Param("userId") String userId,
                                       @Param("since") Instant since);

    /** v2.5.8 — per-project $ spent on Launches (Project dashboard / FinOps). */
    @Query("SELECT COALESCE(SUM(e.totalCostUsd), 0.0) FROM ExecutionRun e " +
            "WHERE e.projectId = :projectId AND e.deletedAt IS NULL")
    Double sumTotalCostByProject(@Param("projectId") String projectId);

    /** v2.5.8 — backfill helper: executions whose totals are zero but have completed solutions. */
    @Query("SELECT e FROM ExecutionRun e WHERE (e.totalActualTokens = 0 OR e.totalActualTokens IS NULL) " +
            "AND e.status IN ('COMPLETE', 'PARTIAL') AND e.deletedAt IS NULL")
    List<ExecutionRun> findCompletedWithZeroTotals();
}
