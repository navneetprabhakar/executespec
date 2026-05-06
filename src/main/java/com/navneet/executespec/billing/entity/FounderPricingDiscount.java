package com.navneet.executespec.billing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Global founder-pricing offer (V177, GA-readiness P2.4). Represents a singleton "first N
 * subscribers get X% off" offer. The atomic allocation contract lives on {@link
 * com.navneet.executespec.billing.service.FounderPricingService} — call sites should never mutate
 * {@link #currentCount} directly.
 *
 * <p>Lifetime semantics: when {@link #isLifetime} is TRUE, an allocated discount survives churn +
 * re-subscribe by the same user. Tracking is on {@link FounderPricingAllocation}.
 */
@Entity
@Table(name = "founder_pricing_discount")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FounderPricingDiscount {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  /** Unique stable name (e.g. {@code FOUNDER50_SOLO_LIFETIME}). */
  @Column(nullable = false, unique = true, length = 64)
  private String name;

  /** Tier this offer applies to (e.g. {@code PRO} for the Solo offer). */
  @Column(nullable = false, length = 32)
  private String tier;

  /** Percent off. 1-100 inclusive (DB CHECK constraint). */
  @Column(name = "discount_pct", nullable = false)
  private int discountPct;

  /** Maximum number of subscribers eligible for this offer. */
  @Column(name = "max_subscribers", nullable = false)
  private int maxSubscribers;

  /** Number of allocations consumed so far. NEVER mutate directly — use the service. */
  @Column(name = "current_count", nullable = false)
  private int currentCount;

  /** TRUE = discount persists across churn + re-subscribe by the same user. */
  @Column(name = "is_lifetime", nullable = false)
  private boolean isLifetime;

  /** Optional sunset timestamp; NULL = no expiry. */
  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(nullable = false)
  private boolean active;

  /** Stripe coupon ID once ops creates the matching coupon on Stripe. */
  @Column(name = "stripe_coupon_id")
  private String stripeCouponId;

  /** Razorpay offer ID once ops creates the matching offer on Razorpay. */
  @Column(name = "razorpay_offer_id")
  private String razorpayOfferId;

  @Column(columnDefinition = "TEXT")
  private String notes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  /** Capacity check that does NOT mutate state. The service does the locked check + increment. */
  public boolean hasCapacity() {
    return active && currentCount < maxSubscribers;
  }
}
