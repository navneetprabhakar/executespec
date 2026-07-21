package com.navneet.executespec.billing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingGatewayObligation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** V310 — durable gateway obligations (RR-048 / RR-049 / RR-352 / RR-353). */
@Repository
public interface BillingGatewayObligationRepository
    extends JpaRepository<BillingGatewayObligation, UUID> {

  Optional<BillingGatewayObligation> findByIdempotencyKey(String idempotencyKey);

  /** Unresolved obligations, oldest first — the operator/retry view. */
  @Query(
      "SELECT o FROM BillingGatewayObligation o WHERE o.status IN ('PENDING', 'FAILED',"
          + " 'UNSUPPORTED') ORDER BY o.createdAt ASC")
  List<BillingGatewayObligation> findUnresolved();

  @Query(
      "SELECT o FROM BillingGatewayObligation o WHERE o.kind = :kind AND o.status IN ('PENDING',"
          + " 'FAILED', 'UNSUPPORTED') ORDER BY o.createdAt ASC")
  List<BillingGatewayObligation> findUnresolvedByKind(@Param("kind") String kind);

  List<BillingGatewayObligation> findBySubscriptionId(UUID subscriptionId);
}
