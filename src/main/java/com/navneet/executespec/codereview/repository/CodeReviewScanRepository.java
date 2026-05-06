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
   * Latest scan regardless of status — used by the dashboard panel so a FAILED scan with its {@code
   * failure_reason} shows instead of silently reverting to "No scan yet". The chat tool still uses
   * the COMPLETE-only finder above so it doesn't hand the user an unfinished report.
   */
  Optional<CodeReviewScan> findFirstByProjectIdAndRepoFullNameOrderByStartedAtDesc(
      String projectId, String repoFullName);

  /**
   * Per-month count for a given user — used by the tier-quota gate to enforce per-tier monthly caps
   * (PRO: 4/month, TEAM/MAX/ENTERPRISE: unlimited).
   */
  @Query(
      "SELECT COUNT(s) FROM CodeReviewScan s "
          + "WHERE s.createdByUserId = :userId AND s.startedAt >= :sinceMonthStart")
  long countByUserSince(
      @Param("userId") String userId, @Param("sinceMonthStart") Instant sinceMonthStart);

  /**
   * Sum of {@code cost_usd} across every scan for a project. Used by the dashboard to fold code
   * review spend into the project's total cost. COALESCE handles the empty-table case (no scans
   * yet) and the per-row null case (in-flight scans where cost hasn't been tallied yet).
   */
  @Query(
      "SELECT COALESCE(SUM(s.costUsd), 0.0) FROM CodeReviewScan s WHERE s.projectId = :projectId")
  double sumCostUsdByProject(@Param("projectId") String projectId);

  /** Total tokens (input + output) across every scan for a project. */
  @Query(
      "SELECT COALESCE(SUM(s.totalInputTokens), 0L) + COALESCE(SUM(s.totalOutputTokens), 0L) "
          + "FROM CodeReviewScan s WHERE s.projectId = :projectId")
  long sumTokensByProject(@Param("projectId") String projectId);
}
