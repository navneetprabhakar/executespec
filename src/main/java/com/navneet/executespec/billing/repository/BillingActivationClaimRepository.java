package com.navneet.executespec.billing.repository;

import java.time.Instant;

import com.navneet.executespec.billing.entity.BillingActivationClaim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BillingActivationClaimRepository
    extends JpaRepository<BillingActivationClaim, String> {

  /**
   * RR-056 — atomically claim {@code (gateway, claimKey)}. Returns {@code 1} for the caller whose
   * row was inserted, {@code 0} when the identity was already claimed. {@code ON CONFLICT DO
   * NOTHING} means a concurrent duplicate does NOT raise a constraint violation (which would poison
   * the enclosing activation transaction) — it simply reports zero rows, so the loser can skip the
   * non-idempotent effects while the winner proceeds. Running in the caller's transaction, a
   * rollback of the activation releases the claim so a genuine retry can proceed.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO billing_activation_claim (id, gateway, claim_key, claimed_at) "
              + "VALUES (:id, :gateway, :claimKey, :claimedAt) "
              + "ON CONFLICT (gateway, claim_key) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") String id,
      @Param("gateway") String gateway,
      @Param("claimKey") String claimKey,
      @Param("claimedAt") Instant claimedAt);
}
