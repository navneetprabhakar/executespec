package com.navneet.executespec.harness.repository;

import java.time.Instant;

import com.navneet.executespec.harness.entity.GateRecord;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface GateRepository extends JpaRepository<GateRecord, String> {

  /**
   * Atomic "claim" for the plan-approval gate. Only resolves the row if it is still PENDING — if
   * another caller already won the race, the UPDATE affects 0 rows and the caller should return 409
   * (see {@code RunController.approvePlan} + adversarial-test-report-2026-04-18 Finding 4).
   *
   * <p>{@code @Transactional} is mandatory for {@code @Modifying} queries — without it the call
   * throws {@code InvalidDataAccessApiUsageException: Executing an update/delete query}. {@code
   * REQUIRED} propagation lets PG serialise the two concurrent UPDATEs via row-level locks under
   * READ COMMITTED.
   *
   * @return number of rows updated (0 = already resolved, 1 = we won)
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE GateRecord g SET g.status = :newStatus, g.resolvedAt = :now "
          + "WHERE g.gateKey = :gateKey AND g.status = 'PENDING'")
  int tryResolveIfPending(
      @Param("gateKey") String gateKey,
      @Param("newStatus") String newStatus,
      @Param("now") Instant now);
}
