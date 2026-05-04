package com.navneet.executespec.codereview.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.codereview.entity.CodeReviewScan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeReviewScanRepository extends JpaRepository<CodeReviewScan, UUID> {

  List<CodeReviewScan> findByProjectIdOrderByStartedAtDesc(String projectId);

  /** Latest scan for a (repo) — used when the user opens the Code Quality tab cold. */
  Optional<CodeReviewScan> findFirstByRepoFullNameOrderByStartedAtDesc(String repoFullName);

  /**
   * Latest COMPLETE scan for a (project, repo) — preferred over the absolute-latest because QUEUED
   * / RUNNING / FAILED rows shouldn't surface the previous report's markdown to the chat.
   */
  Optional<CodeReviewScan> findFirstByProjectIdAndRepoFullNameAndStatusOrderByStartedAtDesc(
      String projectId, String repoFullName, String status);

  /**
   * Per-month count for a given user — used by the tier-quota gate to enforce per-tier monthly caps
   * (PRO: 4/month, TEAM/MAX/ENTERPRISE: unlimited).
   */
  @Query(
      "SELECT COUNT(s) FROM CodeReviewScan s "
          + "WHERE s.createdByUserId = :userId AND s.startedAt >= :sinceMonthStart")
  long countByUserSince(
      @Param("userId") String userId, @Param("sinceMonthStart") Instant sinceMonthStart);
}
