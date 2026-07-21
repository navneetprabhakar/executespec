package com.navneet.executespec.billing.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RR-518 — durable, unique delivery claim for a billing reminder. One row per {@code (subscription,
 * billing-period, reminder-type, stage, channel)}; the unique index {@code
 * ux_billing_reminder_dispatch_once} (V335) makes the insert the atomic winner so each reminder
 * stage is delivered exactly once per billing period regardless of how often the daily schedule (or
 * how many replicas) revisit the subscription.
 */
@Entity
@Table(name = "billing_reminder_dispatch")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingReminderDispatch {

  @Id private String id;

  @Column(name = "subscription_id", nullable = false)
  private String subscriptionId;

  @Column(name = "user_id")
  private String userId;

  /**
   * The billing cycle the reminder is about — the subscription's current period end, not updatedAt.
   */
  @Column(name = "period_key", nullable = false, length = 64)
  private String periodKey;

  /** RENEWAL | DUNNING. */
  @Column(name = "reminder_type", nullable = false, length = 32)
  private String reminderType;

  /** RENEWAL for renewals; GENTLE | URGENT | FINAL for dunning. */
  @Column(nullable = false, length = 32)
  private String stage;

  /** EMAIL | IN_APP. */
  @Column(nullable = false, length = 16)
  private String channel;

  @Column(name = "dispatched_at", nullable = false)
  private Instant dispatchedAt;
}
