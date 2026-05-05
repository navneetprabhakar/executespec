package com.navneet.executespec.billing.repository;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.FounderPricingAllocation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FounderPricingAllocationRepository
    extends JpaRepository<FounderPricingAllocation, UUID> {

  /** Lookup for "does this user already hold an allocation for this discount". */
  Optional<FounderPricingAllocation> findByDiscountIdAndUserId(UUID discountId, String userId);
}
