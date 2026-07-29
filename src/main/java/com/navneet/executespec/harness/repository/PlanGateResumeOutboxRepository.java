package com.navneet.executespec.harness.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.harness.entity.PlanGateResumeOutbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence access for the V346 one-continuation-per-run outbox. */
public interface PlanGateResumeOutboxRepository
    extends JpaRepository<PlanGateResumeOutbox, String> {

  Optional<PlanGateResumeOutbox> findByGateKey(String gateKey);

  /**
   * Claims are deliberately selected with {@code SKIP LOCKED}: every replica may sweep the durable
   * queue, while a row remains owned by at most one claim transaction.
   */
  @Query(
      value =
          "SELECT * FROM plan_gate_resume_outbox "
              + "WHERE ((status = 'READY' AND next_retry_at <= :now) "
              + "OR (status = 'PROCESSING' AND lease_expires_at <= :now)) "
              + "AND attempt_count < max_attempts "
              + "ORDER BY next_retry_at, created_at, run_id "
              + "FOR UPDATE SKIP LOCKED LIMIT 1",
      nativeQuery = true)
  Optional<PlanGateResumeOutbox> findNextClaimableForUpdate(@Param("now") Instant now);

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM PlanGateResumeOutbox o WHERE o.runId = :runId")
  Optional<PlanGateResumeOutbox> findByIdForUpdate(@Param("runId") String runId);

  @Query(
      value =
          "SELECT run_id FROM plan_gate_resume_outbox "
              + "WHERE ((status = 'READY' AND next_retry_at <= :now) "
              + "OR (status = 'PROCESSING' AND lease_expires_at <= :now)) "
              + "AND attempt_count >= max_attempts "
              + "ORDER BY next_retry_at, created_at, run_id LIMIT 16",
      nativeQuery = true)
  List<String> findExhaustedClaimableRunIds(@Param("now") Instant now);

  @Modifying
  @Query(
      "UPDATE PlanGateResumeOutbox o SET o.heartbeatAt = :now, o.leaseExpiresAt = :expires "
          + "WHERE o.runId = :runId AND o.status = 'PROCESSING' "
          + "AND o.leaseOwner = :owner AND o.leaseToken = :token "
          + "AND o.leaseExpiresAt > :now")
  int heartbeat(
      @Param("runId") String runId,
      @Param("owner") String owner,
      @Param("token") UUID token,
      @Param("now") Instant now,
      @Param("expires") Instant expires);
}
