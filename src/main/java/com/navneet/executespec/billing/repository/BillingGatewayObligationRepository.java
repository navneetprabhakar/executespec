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

  /**
   * Drainer candidate pool: retryable obligations that have not yet exhausted their attempt budget.
   * {@code UNSUPPORTED} is excluded on purpose — it means no automated gateway path exists, so it
   * is an operator task, not a retry candidate. Per-row backoff (skip rows touched too recently)
   * and dead-lettering are applied by the drainer; this only bounds the row set it examines.
   */
  @Query(
      "SELECT o FROM BillingGatewayObligation o WHERE o.status IN ('PENDING', 'FAILED') AND"
          + " o.attempts < :maxAttempts ORDER BY o.updatedAt ASC")
  List<BillingGatewayObligation> findRetryable(@Param("maxAttempts") int maxAttempts);

  /** Count unresolved obligations grouped by kind + status — backs the admin visibility surface. */
  @Query(
      "SELECT o.kind, o.status, COUNT(o) FROM BillingGatewayObligation o WHERE o.status IN"
          + " ('PENDING', 'FAILED', 'UNSUPPORTED') GROUP BY o.kind, o.status")
  List<Object[]> countUnresolvedByKindAndStatus();
}
