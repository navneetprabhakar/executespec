package com.navneet.executespec.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingSubscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingSubscriptionRepository extends JpaRepository<BillingSubscription, UUID> {

  @Query(
      "SELECT s FROM BillingSubscription s WHERE s.userId = :userId AND s.status IN ('FREE',"
          + " 'ACTIVE', 'PAST_DUE')")
  Optional<BillingSubscription> findActiveByUserId(@Param("userId") String userId);

  /**
   * B-08 (audit, 2026-05-09) — bulk variant for the monthly-counter cron. Per-user {@code
   * findActiveByUserId} fired one SELECT per app_user row at midnight IST, which on a 50k-user DB
   * took the cron above the lock window. The cron now loads every active subscription in one SELECT
   * and matches in memory.
   */
  @Query("SELECT s FROM BillingSubscription s WHERE s.status IN ('FREE', 'ACTIVE', 'PAST_DUE')")
  List<BillingSubscription> findAllActiveOrPastDue();

  Optional<BillingSubscription> findByGatewaySubscriptionId(String gatewaySubscriptionId);

  @Query(
      "SELECT s.gatewayCustomerId FROM BillingSubscription s WHERE s.userId = :userId AND"
          + " s.gatewayCustomerId IS NOT NULL ORDER BY s.createdAt DESC LIMIT 1")
  Optional<String> findLatestCustomerIdByUserId(@Param("userId") String userId);

  @Query(
      "SELECT COUNT(s) FROM BillingSubscription s WHERE s.founderPricing = true AND s.status IN"
          + " ('ACTIVE', 'PAST_DUE')")
  long countActiveFounderSubscriptions();

  List<BillingSubscription> findByStatus(String status);

  /** Find ACTIVE subscriptions renewing within a given window (for renewal reminders). */
  @Query(
      "SELECT s FROM BillingSubscription s WHERE s.status = 'ACTIVE' AND s.currentPeriodEnd > :now"
          + " AND s.currentPeriodEnd < :threshold AND s.cancelAtPeriodEnd = false")
  List<BillingSubscription> findRenewingBefore(
      @Param("now") Instant now, @Param("threshold") Instant threshold);

  /**
   * Find cancelled subscriptions whose grace period has ended (currentPeriodEnd is in the past).
   */
  @Query(
      "SELECT s FROM BillingSubscription s WHERE s.status = 'CANCELLED' AND s.currentPeriodEnd <"
          + " :now")
  List<BillingSubscription> findCancelledPastPeriodEnd(@Param("now") Instant now);

  /**
   * v2.6.0 — find active subscriptions on a given tier. Used by {@code
   * TierLifecycleService.executeSunsettingSideEffects} to enumerate subscribers who need their
   * renewal queued for cancel-at-period-end when a tier transitions to SUNSETTING.
   */
  @Query(
      "SELECT s FROM BillingSubscription s WHERE s.status IN ('ACTIVE', 'PAST_DUE') "
          + "AND s.billingPlanId IN (SELECT p.id FROM BillingPlan p WHERE p.tier = :tier)")
  List<BillingSubscription> findActiveByTier(@Param("tier") String tier);
}
