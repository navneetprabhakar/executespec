package com.navneet.executespec.cost;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository over {@link RunCostHistory}. Audit 2026-05-13 Stage 2 — k-NN cost
 * estimation data layer.
 */
@Repository
public interface RunCostHistoryRepository extends JpaRepository<RunCostHistory, String> {

  /**
   * Return the actual cost (USD) of every past run that matches a shape similar to the given
   * filters. Used by {@link CostHistoryService#estimateFromHistory} to compute P25/P75 of actual
   * cost as the historical estimate.
   *
   * <p>Similarity is an exact match on plan strategy, physical route, repository count, task count,
   * and canonical task-complexity distribution. The distribution fence prevents a LOW-heavy plan
   * from setting the approval cap for an equally sized HIGH-heavy plan. A future revision can layer
   * spec-similarity (embedding distance) on top.
   *
   * <p>RR-1551 — the band this query returns sets {@code creditsCharged} and {@code hardCapUsd} on
   * a customer's run, so it MUST be tenant-scoped. Without the {@code orgId} predicate it was the
   * P25/P75 of every OTHER organisation's observed dollar spend: a BYOK org running a flagship
   * model inflated a PRO user's charge, a cheap cohort depressed another org's ceiling, and the
   * returned band was a materially reconstructable aggregate of foreign tenants' costs. {@code
   * orgId} is required and never nullable — a caller that cannot name a tenant falls back to the
   * model-aware Stage-1 formula, never to a global pool.
   *
   * <p>Returns descending by completed_at so the freshest runs dominate the percentile when pricing
   * or pipeline behaviour shifts; caller can {@code limit} to last N.
   */
  @Query(
      "SELECT h.actualCostUsd FROM RunCostHistory h "
          + "WHERE h.orgId = :orgId "
          + "AND h.planStrategy = :strategy "
          + "AND h.provider = :provider "
          + "AND h.routeFingerprint = :routeFingerprint "
          + "AND h.repoCount = :repoCount "
          + "AND h.taskCount = :taskCount "
          + "AND h.taskComplexityFingerprint = :taskComplexityFingerprint "
          + "AND h.schemaVersion = 2 "
          + "AND h.status = 'COMPLETE' "
          + "AND h.actualCostUsd IS NOT NULL "
          + "ORDER BY h.completedAt DESC")
  List<BigDecimal> findSimilarCosts(
      @Param("orgId") String orgId,
      @Param("strategy") String strategy,
      @Param("provider") String provider,
      @Param("routeFingerprint") String routeFingerprint,
      @Param("repoCount") int repoCount,
      @Param("taskCount") int taskCount,
      @Param("taskComplexityFingerprint") String taskComplexityFingerprint,
      Pageable pageable);
}
