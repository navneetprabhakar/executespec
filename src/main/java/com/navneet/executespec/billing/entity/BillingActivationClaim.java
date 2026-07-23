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
 * RR-056 — durable, unique idempotency claim for a billing subscription/top-up activation.
 *
 * <p>One row per {@code (gateway, claim_key)}, where {@code claim_key} is an IMMUTABLE gateway
 * payment / invoice id for a single activation fact. The unique index {@code
 * ux_billing_activation_claim_once} (V336) makes the insert the atomic winner-selection: the first
 * caller — a post-checkout callback OR a webhook — that reaches a given payment id owns the
 * activation and performs its non-idempotent effects (retire superseded subscription, generate
 * invoice, notify); every duplicate callback/webhook for the same payment id loses the insert and
 * must skip those effects.
 *
 * <p>The claim is inserted in the SAME transaction as the activation effects (via {@code INSERT ...
 * ON CONFLICT DO NOTHING}), so a rollback of the activation releases the claim and a later retry
 * can proceed, while a committed activation permanently blocks the duplicate.
 */
@Entity
@Table(name = "billing_activation_claim")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingActivationClaim {

  @Id private String id;

  /** RAZORPAY | STRIPE. */
  @Column(nullable = false, length = 20)
  private String gateway;

  /** The immutable gateway payment / invoice id that identifies this single activation. */
  @Column(name = "claim_key", nullable = false, length = 200)
  private String claimKey;

  @Column(name = "claimed_at", nullable = false)
  private Instant claimedAt;
}
