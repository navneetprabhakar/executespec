package com.navneet.executespec.report;

import com.navneet.executespec.report.entity.ValidationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ValidationReportRepository extends JpaRepository<ValidationReport, String> {

    Optional<ValidationReport> findByRunId(String runId);

    Optional<ValidationReport> findByShareToken(String shareToken);
}
