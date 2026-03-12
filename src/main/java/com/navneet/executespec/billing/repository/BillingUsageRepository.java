package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.BillingUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingUsageRepository extends JpaRepository<BillingUsage, UUID> {

    @Query("SELECT u FROM BillingUsage u WHERE u.userId = :userId AND u.metric = :metric AND u.periodEnd > :now ORDER BY u.periodStart DESC LIMIT 1")
    Optional<BillingUsage> findCurrentByUserIdAndMetric(
            @Param("userId") String userId,
            @Param("metric") String metric,
            @Param("now") Instant now);

    @Query("SELECT u FROM BillingUsage u WHERE u.userId = :userId AND u.periodEnd > :now")
    List<BillingUsage> findAllCurrentByUserId(@Param("userId") String userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE BillingUsage u SET u.currentValue = u.currentValue + :delta, u.lastUpdated = :now WHERE u.id = :id")
    void incrementValue(@Param("id") UUID id, @Param("delta") long delta, @Param("now") Instant now);
}
