package com.navneet.executespec.billing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.billing.entity.FounderPricingDiscount;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FounderPricingDiscountRepository
    extends JpaRepository<FounderPricingDiscount, UUID> {

  Optional<FounderPricingDiscount> findByName(String name);

  List<FounderPricingDiscount> findByActiveTrue();

  /**
   * PESSIMISTIC_WRITE lock on the row so atomic allocation works under contention. Use ONLY from
   * {@link com.navneet.executespec.billing.service.FounderPricingService#tryAllocate} —
   * unauthorised callers can starve the lock.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT d FROM FounderPricingDiscount d WHERE d.id = :id")
  Optional<FounderPricingDiscount> findByIdForUpdate(@Param("id") UUID id);
}
