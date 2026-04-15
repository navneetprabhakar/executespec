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
}
