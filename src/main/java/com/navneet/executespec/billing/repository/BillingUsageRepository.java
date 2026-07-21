package com.navneet.executespec.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingUsage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingUsageRepository extends JpaRepository<BillingUsage, UUID> {

  @Query(
      "SELECT u FROM BillingUsage u WHERE u.userId = :userId AND u.metric = :metric AND u.periodEnd"
          + " > :now ORDER BY u.periodStart DESC LIMIT 1")
  Optional<BillingUsage> findCurrentByUserIdAndMetric(
      @Param("userId") String userId, @Param("metric") String metric, @Param("now") Instant now);

  @Query("SELECT u FROM BillingUsage u WHERE u.userId = :userId AND u.periodEnd > :now")
  List<BillingUsage> findAllCurrentByUserId(
      @Param("userId") String userId, @Param("now") Instant now);

  @Modifying
  @Query(
      "UPDATE BillingUsage u SET u.currentValue = u.currentValue + :delta, u.lastUpdated = :now"
          + " WHERE u.id = :id")
  void incrementValue(@Param("id") UUID id, @Param("delta") long delta, @Param("now") Instant now);

  /**
   * RR-186 (2026-07-20) — atomic reservation. The counter is incremented ONLY when the resulting
   * value still fits the caller-resolved allowance, and the affected-row count tells the caller
   * whether the reservation succeeded. This closes the check-then-act window in which N concurrent
   * submissions could each read {@code max - 1} and all be admitted before any increment committed.
   *
   * <p>The predicate lives in the {@code WHERE} clause so the database — not application memory —
   * is the arbiter, which also makes it correct across JVMs.
   */
  @Modifying
  @Query(
      "UPDATE BillingUsage u SET u.currentValue = u.currentValue + :delta, u.lastUpdated = :now"
          + " WHERE u.id = :id AND u.currentValue + :delta <= :allowance")
  int incrementIfWithinAllowance(
      @Param("id") UUID id,
      @Param("delta") long delta,
      @Param("allowance") long allowance,
      @Param("now") Instant now);
}
