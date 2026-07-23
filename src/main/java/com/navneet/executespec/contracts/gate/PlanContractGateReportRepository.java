package com.navneet.executespec.contracts.gate;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PlanContractGateReportRepository
    extends JpaRepository<PlanContractGateReport, String> {

  /**
   * Record the acknowledgement atomically, and only against the exact digest the approver was
   * shown. Returns 0 when the row is gone, the digest has moved, or the gate was already
   * acknowledged — in every one of those cases the caller must refuse the approval rather than
   * assume consent.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update PlanContractGateReport r
         set r.acknowledgedAt = :at,
             r.acknowledgedByUserId = :userId,
             r.acknowledgementText = :text
       where r.runId = :runId
         and r.reportDigest = :digest
         and r.acknowledgedAt is null
      """)
  int claimAcknowledgement(
      @Param("runId") String runId,
      @Param("digest") String digest,
      @Param("userId") String userId,
      @Param("text") String text,
      @Param("at") Instant at);
}
