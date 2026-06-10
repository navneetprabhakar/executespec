package com.navneet.executespec.codereview.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codereview.entity.CodeReviewIssue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeReviewIssueRepository extends JpaRepository<CodeReviewIssue, UUID> {

  /** All issues for a scan, severity-then-file ordered for stable rendering. */
  @Query(
      """
      select i
      from CodeReviewIssue i
      where i.scanId = :scanId
      order by case i.severity
        when 'CRITICAL' then 0
        when 'MAJOR' then 1
        when 'MINOR' then 2
        when 'INFO' then 3
        else 4
      end, i.filePath asc, i.lineStart asc
      """)
  List<CodeReviewIssue> findByScanIdOrderBySeverityAscFilePathAscLineStartAsc(
      @Param("scanId") UUID scanId);

  /** Open issues only — used for the Code Quality dashboard rollup. */
  @Query(
      """
      select i
      from CodeReviewIssue i
      where i.scanId = :scanId and i.status = :status
      order by case i.severity
        when 'CRITICAL' then 0
        when 'MAJOR' then 1
        when 'MINOR' then 2
        when 'INFO' then 3
        else 4
      end, i.filePath asc, i.lineStart asc
      """)
  List<CodeReviewIssue> findByScanIdAndStatusOrderBySeverityAscFilePathAscLineStartAsc(
      @Param("scanId") UUID scanId, @Param("status") String status);

  long countByScanIdAndStatus(UUID scanId, String status);

  long countByScanIdAndSeverityAndStatus(UUID scanId, String severity, String status);

  /** Look up across scans by fingerprint — used to carry status (resolved/dismissed) forward. */
  List<CodeReviewIssue> findByFingerprint(String fingerprint);
}
