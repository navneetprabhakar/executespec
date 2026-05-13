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
   * <p>Similarity is "exact match on planStrategy + provider + repoCount + taskCount bucket", which
   * is coarse but matches what the indexes support. A future revision can layer spec-similarity
   * (embedding distance) on top.
   *
   * <p>Returns descending by completed_at so the freshest runs dominate the percentile when pricing
   * or pipeline behaviour shifts; caller can {@code limit} to last N.
   */
  @Query(
      "SELECT h.actualCostUsd FROM RunCostHistory h "
          + "WHERE h.planStrategy = :strategy "
          + "AND h.provider = :provider "
          + "AND h.repoCount = :repoCount "
          + "AND h.taskCount = :taskCount "
          + "AND h.actualCostUsd IS NOT NULL "
          + "ORDER BY h.completedAt DESC")
  List<BigDecimal> findSimilarCosts(
      @Param("strategy") String strategy,
      @Param("provider") String provider,
      @Param("repoCount") int repoCount,
      @Param("taskCount") int taskCount,
      Pageable pageable);

  /**
   * Broader fallback — same provider + strategy, any repoCount/taskCount. Used when the strict
   * similarity query returns too few rows to be statistically meaningful (< 10).
   */
  @Query(
      "SELECT h.actualCostUsd FROM RunCostHistory h "
          + "WHERE h.planStrategy = :strategy "
          + "AND h.provider = :provider "
          + "AND h.actualCostUsd IS NOT NULL "
          + "ORDER BY h.completedAt DESC")
  List<BigDecimal> findSimilarCostsLoose(
      @Param("strategy") String strategy, @Param("provider") String provider, Pageable pageable);
}
