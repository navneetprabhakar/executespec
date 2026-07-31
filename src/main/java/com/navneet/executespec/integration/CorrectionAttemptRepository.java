package com.navneet.executespec.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CorrectionAttemptRepository extends JpaRepository<CorrectionAttempt, UUID> {

  List<CorrectionAttempt> findByPhaseInOrderByCreatedAtAsc(List<String> phases);

  Optional<CorrectionAttempt> findFirstByRunIdAndPhaseInOrderByCreatedAtDesc(
      String runId, List<String> phases);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE CorrectionAttempt c SET c.phase = :next, c.updatedAt = CURRENT_TIMESTAMP "
          + "WHERE c.id = :id AND c.leaseToken = :token AND c.phase = :expected")
  int transition(
      @Param("id") UUID id,
      @Param("token") UUID token,
      @Param("expected") String expected,
      @Param("next") String next);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE CorrectionAttempt c SET c.leaseToken = :token, c.updatedAt = CURRENT_TIMESTAMP "
          + "WHERE c.id = :id AND c.phase IN ('PREPARED','MUTATING','RECOVERY_FAILED')")
  int claimRecovery(@Param("id") UUID id, @Param("token") UUID token);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE CorrectionAttempt c SET c.phase = 'ACCEPTED', c.afterGeneration = :after, "
          + "c.resolvedAt = CURRENT_TIMESTAMP, c.updatedAt = CURRENT_TIMESTAMP "
          + "WHERE c.id = :id AND c.leaseToken = :token AND c.phase = 'MUTATING'")
  int accept(
      @Param("id") UUID id, @Param("token") UUID token, @Param("after") String afterGeneration);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE CorrectionAttempt c SET c.phase = 'ACCEPTED', c.afterGeneration = :after, "
          + "c.resolvedAt = CURRENT_TIMESTAMP, c.updatedAt = CURRENT_TIMESTAMP "
          + "WHERE c.id = :id AND c.leaseToken = :token "
          + "AND c.phase IN ('MUTATING','RECOVERY_FAILED')")
  int acceptRecovered(
      @Param("id") UUID id, @Param("token") UUID token, @Param("after") String afterGeneration);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE CorrectionAttempt c SET c.phase = 'ROLLED_BACK', "
          + "c.afterGeneration = c.beforeGeneration, c.resolvedAt = CURRENT_TIMESTAMP, "
          + "c.updatedAt = CURRENT_TIMESTAMP "
          + "WHERE c.id = :id AND c.leaseToken = :token "
          + "AND c.phase IN ('PREPARED','MUTATING','RECOVERY_FAILED')")
  int rolledBack(@Param("id") UUID id, @Param("token") UUID token);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @org.springframework.transaction.annotation.Transactional
  @Query(
      "UPDATE CorrectionAttempt c SET c.phase = 'RECOVERY_FAILED', c.failureReason = :reason, "
          + "c.updatedAt = CURRENT_TIMESTAMP WHERE c.id = :id AND c.leaseToken = :token "
          + "AND c.phase IN ('PREPARED','MUTATING','RECOVERY_FAILED')")
  int recoveryFailed(
      @Param("id") UUID id, @Param("token") UUID token, @Param("reason") String reason);
}
