package com.navneet.executespec.prreview.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.prreview.entity.PrReview;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrReviewRepository extends JpaRepository<PrReview, UUID> {

  /**
   * Look up the most recent review for a (repo, PR, sha) tuple. The unique constraint on those 3
   * columns guarantees this returns at most one row, but the method signature stays Optional for
   * callers that handle "never reviewed" cleanly.
   */
  Optional<PrReview> findByRepoFullNameAndPrNumberAndHeadSha(
      String repoFullName, Integer prNumber, String headSha);

  /** All reviews for a given PR, newest first — used to render historical reviews on the UI. */
  List<PrReview> findByRepoFullNameAndPrNumberOrderByStartedAtDesc(
      String repoFullName, Integer prNumber);

  /** All reviews for a project, newest first — used for the project-level reviews dashboard. */
  List<PrReview> findByProjectIdOrderByStartedAtDesc(String projectId);

  /**
   * Per-month count for a given user — used by the tier-quota gate to enforce per-tier monthly caps
   * (PRO: 10/month, TEAM/MAX/ENTERPRISE: unlimited).
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COUNT(r) FROM PrReview r WHERE r.createdByUserId = :userId "
          + "AND r.startedAt >= :sinceMonthStart")
  long countByUserSince(
      @org.springframework.data.repository.query.Param("userId") String userId,
      @org.springframework.data.repository.query.Param("sinceMonthStart") java.time.Instant since);

  /**
   * Sum of {@code cost_usd} across every PR review for a project. Used by the dashboard to fold PR
   * review spend into the project's total cost.
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(r.costUsd), 0.0) FROM PrReview r WHERE r.projectId = :projectId")
  double sumCostUsdByProject(
      @org.springframework.data.repository.query.Param("projectId") String projectId);

  /** Total tokens (input + output) across every PR review for a project. */
  @org.springframework.data.jpa.repository.Query(
      "SELECT COALESCE(SUM(r.totalInputTokens), 0L) + COALESCE(SUM(r.totalOutputTokens), 0L) "
          + "FROM PrReview r WHERE r.projectId = :projectId")
  long sumTokensByProject(
      @org.springframework.data.repository.query.Param("projectId") String projectId);
}
