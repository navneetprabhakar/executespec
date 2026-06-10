package com.navneet.executespec.billing.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.RunTopup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RunTopupRepository extends JpaRepository<RunTopup, UUID> {

  @Query(
      "SELECT COALESCE(SUM(r.remaining), 0) FROM RunTopup r "
          + "WHERE r.userId = :userId AND r.status = 'ACTIVE' "
          + "AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
  long sumRemainingByUserId(@Param("userId") String userId, @Param("now") Instant now);

  @Query(
      "SELECT COALESCE(SUM(r.remaining), 0) FROM RunTopup r "
          + "WHERE r.orgId = :orgId AND r.status = 'ACTIVE' "
          + "AND (r.expiresAt IS NULL OR r.expiresAt > :now)")
  long sumRemainingByOrgId(@Param("orgId") String orgId, @Param("now") Instant now);

  @Query(
      "SELECT r FROM RunTopup r "
          + "WHERE r.userId = :userId AND r.status = 'ACTIVE' AND r.remaining > 0 "
          + "AND (r.expiresAt IS NULL OR r.expiresAt > :now) "
          + "ORDER BY r.createdAt ASC LIMIT 1")
  Optional<RunTopup> findOldestActiveByUserId(
      @Param("userId") String userId, @Param("now") Instant now);

  @Query(
      "SELECT r FROM RunTopup r "
          + "WHERE r.orgId = :orgId AND r.status = 'ACTIVE' AND r.remaining > 0 "
          + "AND (r.expiresAt IS NULL OR r.expiresAt > :now) "
          + "ORDER BY r.createdAt ASC LIMIT 1")
  Optional<RunTopup> findOldestActiveByOrgId(
      @Param("orgId") String orgId, @Param("now") Instant now);

  Optional<RunTopup> findByGatewayOrderId(String gatewayOrderId);

  @Modifying
  @Query(
      "UPDATE RunTopup r SET r.gatewayPaymentId = :paymentId, r.remaining = r.quantity, "
          + "r.status = 'ACTIVE', r.expiresAt = null, r.updatedAt = :now "
          + "WHERE r.gatewayOrderId = :orderId AND r.status = 'PENDING'")
  int activatePendingPurchase(
      @Param("orderId") String orderId,
      @Param("paymentId") String paymentId,
      @Param("now") Instant now);

  @Modifying
  @Query(
      "UPDATE RunTopup r SET r.remaining = r.remaining - 1, r.updatedAt = :now "
          + "WHERE r.id = :id AND r.remaining > 0")
  int decrementRemaining(@Param("id") UUID id, @Param("now") Instant now);

  @Modifying
  @Query(
      "UPDATE RunTopup r SET r.status = 'EXPIRED', r.updatedAt = :now "
          + "WHERE r.status = 'ACTIVE' AND r.expiresAt IS NOT NULL AND r.expiresAt <= :now")
  int expireOverdue(@Param("now") Instant now);

  List<RunTopup> findByUserIdOrderByCreatedAtDesc(String userId);

  @Query(
      "SELECT r FROM RunTopup r WHERE r.userId = :userId AND r.status = 'ACTIVE' "
          + "AND (r.expiresAt IS NULL OR r.expiresAt > :now) ORDER BY r.createdAt ASC")
  List<RunTopup> findActiveByUserId(@Param("userId") String userId, @Param("now") Instant now);
}
