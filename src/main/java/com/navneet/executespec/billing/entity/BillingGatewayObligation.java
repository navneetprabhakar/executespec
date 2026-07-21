package com.navneet.executespec.billing.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A durable record of an irreversible payment-gateway action that ExecuteSpec owes a customer
 * (V310).
 *
 * <p>Every one of these was previously a fire-and-forget call whose failure was caught, logged and
 * then contradicted by the records ExecuteSpec kept:
 *
 * <ul>
 *   <li><b>RR-048</b> — a prorated upgrade refund. The old code called the refund API only for
 *       Razorpay, then unconditionally generated a credit note, emailed the customer that money was
 *       refunded, and wrote a {@code PRORATED_REFUND} audit row. For Stripe, none of that was true.
 *   <li><b>RR-049</b> — cancelling the OLD gateway subscription during a paid upgrade. Failure was
 *       swallowed while the local row was marked {@code UPGRADED}, so the old subscription could
 *       keep renewing with ExecuteSpec showing it as retired: the customer is billed twice.
 *   <li><b>RR-352 / RR-353</b> — the per-subscriber cancel-at-period-end cascade for a SUNSETTING
 *       tier, which committed the tier state first and then ran gateway calls inside the same
 *       rollbackable transaction (or skipped them entirely when a feature flag was off).
 * </ul>
 *
 * <p>The contract is: <b>commit the obligation, then call the gateway, then record the outcome in a
 * separate short transaction.</b> A crash, rollback or partial cascade therefore leaves a visible,
 * retryable work item instead of silent divergence between gateway truth and ExecuteSpec truth.
 *
 * <p>{@link #status} distinguishes {@code UNSUPPORTED} from {@code SUCCEEDED} on purpose: an action
 * ExecuteSpec cannot perform automatically on a given gateway is an outstanding liability requiring
 * an operator, never something a customer may be told is done.
 */
@Entity
@Table(name = "billing_gateway_obligation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingGatewayObligation {

  /** A prorated refund owed to the customer for the unused remainder of an upgraded plan. */
  public static final String KIND_PRORATED_REFUND = "PRORATED_REFUND";

  /** Cancellation of the superseded gateway subscription after a paid upgrade. */
  public static final String KIND_CANCEL_OLD_SUBSCRIPTION = "CANCEL_OLD_SUBSCRIPTION";

  /** Cancel-at-period-end queued against a subscriber of a SUNSETTING tier. */
  public static final String KIND_TIER_SUNSET_CANCEL = "TIER_SUNSET_CANCEL";

  public static final String STATUS_PENDING = "PENDING";
  public static final String STATUS_SUCCEEDED = "SUCCEEDED";
  public static final String STATUS_FAILED = "FAILED";

  /**
   * The gateway cannot perform this action automatically — an operator must settle it. Explicitly
   * NOT a success state.
   */
  public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, length = 40)
  private String kind;

  @Column(nullable = false, length = 20)
  @Builder.Default
  private String status = STATUS_PENDING;

  @Column(name = "subscription_id")
  private UUID subscriptionId;

  @Column(name = "user_id", length = 64)
  private String userId;

  @Column(name = "org_id", length = 64)
  private String orgId;

  @Column(length = 20)
  private String gateway;

  /** Gateway subscription id or payment id the action targets. */
  @Column(name = "gateway_ref")
  private String gatewayRef;

  @Column(name = "amount_minor")
  private Long amountMinor;

  @Column(length = 3)
  private String currency;

  /** Deterministic dedupe key — a retry must reuse the obligation, not create a second one. */
  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  @Column(nullable = false)
  @Builder.Default
  private int attempts = 0;

  @Column(name = "last_error", columnDefinition = "text")
  private String lastError;

  /** Gateway-side identifier of the completed action (refund id, cancellation id). */
  @Column(name = "external_ref")
  private String externalRef;

  @Column(columnDefinition = "text")
  private String detail;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  /** True once the gateway confirmed the action actually happened. */
  public boolean isConfirmed() {
    return STATUS_SUCCEEDED.equals(status);
  }
}
