package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.BillingPlanLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BillingPlanLimitRepository extends JpaRepository<BillingPlanLimit, UUID> {

    List<BillingPlanLimit> findByBillingPlanId(UUID billingPlanId);

    Optional<BillingPlanLimit> findByBillingPlanIdAndMetric(UUID billingPlanId, String metric);
}
