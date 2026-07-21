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
      "UPDATE RunTopup r SET r.gatewayPaymentId = :paymentId, r.remaining = 0, "
          + "r.status = 'CREDITED', r.expiresAt = null, r.updatedAt = :now "
          + "WHERE r.gatewayOrderId = :orderId AND r.status = 'PENDING'")
  int markPendingPurchaseCredited(
      @Param("orderId") String orderId,
      @Param("paymentId") String paymentId,
      @Param("now") Instant now);

  /**
   * RR-110 — conditional {@code PENDING -> CREDITED} claim keyed by top-up id, for the Stripe
   * checkout path.
   *
   * <p>The Stripe path used to do a read / status-check / ordinary {@code save()}, with no
   * optimistic version on {@link RunTopup} and no conditional activation query. Two concurrent
   * deliveries of the same valid Stripe event could therefore both observe {@code PENDING} and each
   * call {@code grantPurchasedCredits}, which is an additive update rather than an idempotent one —
   * granting the purchased AI credits twice for a single payment. Exactly one caller can now win
   * this UPDATE; a loser sees {@code 0} rows and must not grant.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE RunTopup r SET r.gatewayOrderId = :sessionId, r.gatewayPaymentId = :paymentId, "
          + "r.remaining = 0, r.status = 'CREDITED', r.expiresAt = null, r.updatedAt = :now "
          + "WHERE r.id = :id AND r.status = 'PENDING'")
  int claimPendingStripeTopup(
      @Param("id") UUID id,
      @Param("sessionId") String sessionId,
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
