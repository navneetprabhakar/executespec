package com.navneet.executespec.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.report.entity.ValidationReport;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ValidationReportRepository extends JpaRepository<ValidationReport, String> {

  Optional<ValidationReport> findByRunId(String runId);

  Optional<ValidationReport> findByShareToken(String shareToken);

  /**
   * Pilot R6 — score trend for a project. Joins validation_report to pipeline_run on runId and
   * filters by project. Ordered oldest → newest so the UI chart plots time in the natural
   * direction.
   */
  @Query(
      value =
          """
          SELECT v.* FROM validation_report v
          JOIN pipeline_run r ON r.run_id = v.run_id
          WHERE r.project_id = :projectId
          ORDER BY v.created_at ASC
          """,
      nativeQuery = true)
  List<ValidationReport> findAllByProjectId(@Param("projectId") String projectId);

  // ─── Selling-point landing aggregates ─────────────────────────────────────
  // The next four queries power the public, anonymized /api/v3/landing/*
  // surfaces. Kept native so we can index-hit fast paths and so we don't
  // load whole entity rows when we only need counts.

  /** Count of completed runs (any pipeline_result) since {@code since}. */
  @Query(
      value =
          """
          SELECT COUNT(*) FROM validation_report
          WHERE created_at >= :since
          """,
      nativeQuery = true)
  long countCompletedSince(@Param("since") Instant since);

  /** Share with verifiedScore >= 80 since {@code since}, or null when no rows. */
  @Query(
      value =
          """
          SELECT CASE WHEN COUNT(*) = 0 THEN NULL
                      ELSE (100 * COUNT(*) FILTER (WHERE verified_score >= 80)) / COUNT(*)
                 END
          FROM validation_report
          WHERE created_at >= :since AND verified_score IS NOT NULL
          """,
      nativeQuery = true)
  Integer verifiedScorePercentSince(@Param("since") Instant since);

  /** Total completed runs all-time. */
  @Query(value = "SELECT COUNT(*) FROM validation_report", nativeQuery = true)
  long countAllTime();

  /**
   * Sample-runs gallery — the top {@code limit} most-recent rows with verifiedScore >= 80, joined
   * to pipeline_run for spec text + duration. Returns Object[] columns: [user_request,
   * verified_score, files_generated, started_at, completed_at, github_pr_url].
   */
  @Query(
      value =
          """
          SELECT r.user_request,
                 v.verified_score,
                 v.files_generated,
                 r.started_at,
                 r.completed_at,
                 r.github_pr_url
          FROM validation_report v
          JOIN pipeline_run r ON r.run_id = v.run_id
          WHERE v.verified_score >= 80
            AND r.user_request IS NOT NULL
            AND r.user_request <> ''
          ORDER BY v.created_at DESC
          LIMIT :limit
          """,
      nativeQuery = true)
  List<Object[]> findTopGalleryRuns(@Param("limit") int limit);

  /**
   * /v3/exec-demo — last-30-days aggregate. Returns Object[]: [total_runs, success_rate_int,
   * total_cost_usd, active_projects, needs_attention].
   */
  /**
   * Returns a single-row Object[] (totals + percent + spend + projects + needs). Wrapped as
   * List<Object[]> because Spring Data JPA otherwise misinterprets a multi-column native single-row
   * return as a 1-element Object[].
   */
  @Query(
      value =
          """
          SELECT COUNT(*),
                 CASE WHEN COUNT(*) FILTER (WHERE verified_score IS NOT NULL) = 0 THEN NULL
                      ELSE (100 * COUNT(*) FILTER (WHERE verified_score >= 80))
                           / COUNT(*) FILTER (WHERE verified_score IS NOT NULL)
                 END,
                 COALESCE(SUM(r.actual_cost_usd), 0),
                 COUNT(DISTINCT r.project_id) FILTER (WHERE r.project_id IS NOT NULL),
                 COUNT(*) FILTER (WHERE v.verified_score < 80 OR v.final_build_passed = FALSE)
          FROM validation_report v
          JOIN pipeline_run r ON r.run_id = v.run_id
          WHERE v.created_at >= :since
          """,
      nativeQuery = true)
  List<Object[]> execSnapshotTotals(@Param("since") Instant since);

  /**
   * Spend-by-project for /v3/exec-demo. Returns Object[]: [project_id, runs, spend_usd] for the top
   * 6 projects by run-count in the window.
   */
  @Query(
      value =
          """
          SELECT r.project_id,
                 COUNT(*),
                 COALESCE(SUM(r.actual_cost_usd), 0)
          FROM validation_report v
          JOIN pipeline_run r ON r.run_id = v.run_id
          WHERE v.created_at >= :since AND r.project_id IS NOT NULL
          GROUP BY r.project_id
          ORDER BY COUNT(*) DESC
          LIMIT 6
          """,
      nativeQuery = true)
  List<Object[]> spendByProjectSince(@Param("since") Instant since);

  /**
   * Top wins for /v3/exec-demo. Returns Object[]: [user_request, verified_score, total_cost_usd,
   * completed_at] of best 5 by verifiedScore in the window.
   */
  @Query(
      value =
          """
          SELECT r.user_request,
                 v.verified_score,
                 r.actual_cost_usd,
                 r.completed_at
          FROM validation_report v
          JOIN pipeline_run r ON r.run_id = v.run_id
          WHERE v.created_at >= :since
            AND v.verified_score >= 85
            AND r.user_request IS NOT NULL AND r.user_request <> ''
          ORDER BY v.verified_score DESC, v.created_at DESC
          LIMIT 5
          """,
      nativeQuery = true)
  List<Object[]> topWinsSince(@Param("since") Instant since);
}
