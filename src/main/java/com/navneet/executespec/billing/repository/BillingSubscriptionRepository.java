package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.BillingSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingSubscriptionRepository extends JpaRepository<BillingSubscription, UUID> {

    @Query("SELECT s FROM BillingSubscription s WHERE s.userId = :userId AND s.status IN ('FREE', 'ACTIVE', 'PAST_DUE')")
    Optional<BillingSubscription> findActiveByUserId(@Param("userId") String userId);

    Optional<BillingSubscription> findByGatewaySubscriptionId(String gatewaySubscriptionId);

    @Query("SELECT s.gatewayCustomerId FROM BillingSubscription s WHERE s.userId = :userId AND s.gatewayCustomerId IS NOT NULL ORDER BY s.createdAt DESC LIMIT 1")
    Optional<String> findLatestCustomerIdByUserId(@Param("userId") String userId);

    @Query("SELECT COUNT(s) FROM BillingSubscription s WHERE s.founderPricing = true AND s.status IN ('ACTIVE', 'PAST_DUE')")
    long countActiveFounderSubscriptions();

    List<BillingSubscription> findByStatus(String status);

    /** Find cancelled subscriptions whose grace period has ended (currentPeriodEnd is in the past). */
    @Query("SELECT s FROM BillingSubscription s WHERE s.status = 'CANCELLED' AND s.currentPeriodEnd < :now")
    List<BillingSubscription> findCancelledPastPeriodEnd(@Param("now") Instant now);
}
