package com.navneet.executespec.billing.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingPlanLimit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingPlanLimitRepository extends JpaRepository<BillingPlanLimit, UUID> {

  List<BillingPlanLimit> findByBillingPlanId(UUID billingPlanId);

  /**
   * RR-057 — batch limit lookup so {@code listPlans} resolves every plan's limits in one query
   * instead of one {@link #findByBillingPlanId} per plan.
   */
  List<BillingPlanLimit> findByBillingPlanIdIn(Collection<UUID> billingPlanIds);

  Optional<BillingPlanLimit> findByBillingPlanIdAndMetric(UUID billingPlanId, String metric);
}
