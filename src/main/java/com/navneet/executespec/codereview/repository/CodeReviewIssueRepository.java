package com.navneet.executespec.codereview.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.codereview.entity.CodeReviewIssue;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CodeReviewIssueRepository extends JpaRepository<CodeReviewIssue, UUID> {

  /** All issues for a scan, severity-then-file ordered for stable rendering. */
  List<CodeReviewIssue> findByScanIdOrderBySeverityAscFilePathAscLineStartAsc(UUID scanId);

  /** Open issues only — used for the Code Quality dashboard rollup. */
  List<CodeReviewIssue> findByScanIdAndStatusOrderBySeverityAscFilePathAscLineStartAsc(
      UUID scanId, String status);

  long countByScanIdAndStatus(UUID scanId, String status);

  long countByScanIdAndSeverityAndStatus(UUID scanId, String severity, String status);

  /** Look up across scans by fingerprint — used to carry status (resolved/dismissed) forward. */
  List<CodeReviewIssue> findByFingerprint(String fingerprint);
}
