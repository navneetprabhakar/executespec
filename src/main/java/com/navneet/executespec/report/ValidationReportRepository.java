package com.navneet.executespec.report;

import com.navneet.executespec.report.entity.ValidationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ValidationReportRepository extends JpaRepository<ValidationReport, String> {

    Optional<ValidationReport> findByRunId(String runId);

    Optional<ValidationReport> findByShareToken(String shareToken);

    /**
     * Pilot R6 — score trend for a project. Joins validation_report to
     * pipeline_run on runId and filters by project. Ordered oldest → newest so
     * the UI chart plots time in the natural direction.
     */
    @Query(value = """
        SELECT v.* FROM validation_report v
        JOIN pipeline_run r ON r.run_id = v.run_id
        WHERE r.project_id = :projectId
        ORDER BY v.created_at ASC
        """, nativeQuery = true)
    List<ValidationReport> findAllByProjectId(@Param("projectId") String projectId);
}
