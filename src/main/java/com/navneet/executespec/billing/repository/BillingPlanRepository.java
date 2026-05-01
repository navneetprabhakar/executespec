package com.navneet.executespec.billing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingPlan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingPlanRepository extends JpaRepository<BillingPlan, UUID> {

  List<BillingPlan> findByActiveTrue();

  List<BillingPlan> findByActiveTrueAndKeyMode(String keyMode);

  Optional<BillingPlan> findFirstByTierAndKeyModeAndActiveTrue(String tier, String keyMode);

  List<BillingPlan> findByTierAndActiveTrue(String tier);

  long countByFounderPricingTrueAndActiveTrue();
}
