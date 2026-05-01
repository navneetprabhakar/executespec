package com.navneet.executespec.billing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "billing_plan")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingPlan {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 100)
  private String name;

  /** FREE, STARTER, PRO, ENTERPRISE */
  @Column(nullable = false, length = 20)
  private String tier;

  /** BYOK, PLATFORM, NONE */
  @Column(name = "key_mode", nullable = false, length = 20)
  private String keyMode;

  @Column(name = "product_access", nullable = false, columnDefinition = "TEXT[]")
  @JdbcTypeCode(SqlTypes.ARRAY)
  private String[] productAccess;

  /** MONTHLY, ANNUAL, NONE */
  @Column(nullable = false, length = 20)
  private String period;

  @Column(name = "base_amount_inr", nullable = false)
  private long baseAmountInr;

  @Column(name = "base_amount_usd", nullable = false)
  private long baseAmountUsd;

  @Column(name = "per_seat_inr", nullable = false)
  private long perSeatInr;

  @Column(name = "per_seat_usd", nullable = false)
  private long perSeatUsd;

  @Column(name = "included_seats", nullable = false)
  private int includedSeats;

  @Column(name = "run_limit", nullable = false)
  private int runLimit;

  @Column(name = "annual_discount_pct", nullable = false)
  private int annualDiscountPct;

  @Column(name = "is_founder_pricing", nullable = false)
  private boolean founderPricing;

  @Column(name = "razorpay_plan_id", length = 50)
  private String razorpayPlanId;

  @Column(name = "stripe_price_id", length = 50)
  private String stripePriceId;

  @Column(name = "stripe_product_id", length = 50)
  private String stripeProductId;

  @Column(nullable = false)
  private boolean active;

  /**
   * v2.6.0 — lifecycle state ({@link com.navneet.executespec.billing.lifecycle.LifecycleState}).
   * LISTED | HIDDEN | SUNSETTING. See tier_config.lifecycle_state for the tier-level counterpart;
   * resolution rule is "both must be LISTED to appear on public surfaces."
   */
  @Column(name = "lifecycle_state", nullable = false, length = 16)
  @Builder.Default
  private String lifecycleState = "LISTED";

  @Column(name = "lifecycle_changed_at")
  private Instant lifecycleChangedAt;

  @Column(name = "lifecycle_changed_by", length = 64)
  private String lifecycleChangedBy;

  @Column(name = "lifecycle_reason", columnDefinition = "TEXT")
  private String lifecycleReason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @PrePersist
  void prePersist() {
    var now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (!active) active = true;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
