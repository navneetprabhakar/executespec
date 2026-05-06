package com.navneet.executespec.billing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-user record of a {@link FounderPricingDiscount} allocation. The unique constraint on {@code
 * (discount_id, user_id)} pins lifetime semantics — a user can hold at most one allocation per
 * offer, and revoke + re-grant flows toggle {@link #revokedAt} rather than inserting a new row.
 */
@Entity
@Table(name = "founder_pricing_allocation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FounderPricingAllocation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "discount_id", nullable = false)
  private UUID discountId;

  @Column(name = "user_id", nullable = false, length = 255)
  private String userId;

  @Column(name = "org_id", length = 255)
  private String orgId;

  @Column(name = "allocated_at", nullable = false)
  private Instant allocatedAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoke_reason", columnDefinition = "TEXT")
  private String revokeReason;

  @PrePersist
  void prePersist() {
    if (allocatedAt == null) allocatedAt = Instant.now();
  }

  /** Convenience — true when the allocation hasn't been revoked. */
  public boolean isActive() {
    return revokedAt == null;
  }
}
