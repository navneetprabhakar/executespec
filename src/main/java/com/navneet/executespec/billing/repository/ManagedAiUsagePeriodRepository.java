package com.navneet.executespec.billing.repository;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ManagedAiUsagePeriodRepository
    extends JpaRepository<ManagedAiUsagePeriod, String> {

  @Query(
      """
      select p from ManagedAiUsagePeriod p
      where p.userId = :userId and p.periodStart <= :now and p.periodEnd > :now
      """)
  Optional<ManagedAiUsagePeriod> findCurrent(
      @Param("userId") String userId, @Param("now") Instant now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select p from ManagedAiUsagePeriod p
      where p.userId = :userId and p.periodStart <= :now and p.periodEnd > :now
      """)
  Optional<ManagedAiUsagePeriod> findCurrentForUpdate(
      @Param("userId") String userId, @Param("now") Instant now);
}
