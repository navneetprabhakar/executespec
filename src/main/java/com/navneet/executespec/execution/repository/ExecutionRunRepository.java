package com.navneet.executespec.execution.repository;

import com.navneet.executespec.execution.entity.ExecutionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ExecutionRunRepository extends JpaRepository<ExecutionRun, String> {
    Page<ExecutionRun> findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(String projectId, Pageable pageable);

    @Query("SELECT e FROM ExecutionRun e " +
            "WHERE e.status NOT IN ('COMPLETE', 'FAILED', 'CANCELLED', 'ABORTED') " +
            "AND e.updatedAt < :cutoff")
    List<ExecutionRun> findStaleNonTerminal(@Param("cutoff") Instant cutoff);
}
