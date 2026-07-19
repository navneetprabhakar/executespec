package com.navneet.executespec.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.billing.entity.RunQuotaRefund;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RunQuotaRefundRepository extends JpaRepository<RunQuotaRefund, String> {

  long countByStatusAndCreatedAtBefore(String status, Instant createdBefore);

  @Modifying
  @Query(
      value =
          """
INSERT INTO run_quota_refund
    (run_id, user_id, credits, status, attempt_count, next_attempt_at, created_at, updated_at)
VALUES (:runId, :userId, :credits, 'PENDING', 0, NOW(), NOW(), NOW())
ON CONFLICT (run_id) DO NOTHING
""",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("runId") String runId, @Param("userId") String userId, @Param("credits") int credits);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from RunQuotaRefund r where r.runId = :runId")
  Optional<RunQuotaRefund> findForUpdate(@Param("runId") String runId);

  @Query(
      "select r.runId from RunQuotaRefund r where r.status = 'PENDING' "
          + "and r.nextAttemptAt <= :now order by r.nextAttemptAt asc, r.createdAt asc")
  List<String> findPendingRunIds(@Param("now") Instant now, Pageable pageable);

  @Modifying
  @Query(
      "update RunQuotaRefund r set r.attemptCount = r.attemptCount + 1, "
          + "r.lastError = :error, r.updatedAt = :now, r.nextAttemptAt = :nextAttemptAt "
          + "where r.runId = :runId and r.status = 'PENDING'")
  int recordFailure(
      @Param("runId") String runId,
      @Param("error") String error,
      @Param("now") Instant now,
      @Param("nextAttemptAt") Instant nextAttemptAt);
}
