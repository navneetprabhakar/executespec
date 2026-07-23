package com.navneet.executespec.billing.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.navneet.executespec.billing.entity.BillingGatewayObligation;
import com.navneet.executespec.billing.gateway.PaymentGateway;
import com.navneet.executespec.billing.gateway.PaymentGatewayRouter;
import com.navneet.executespec.billing.gateway.RazorpayGateway;
import com.navneet.executespec.billing.service.BillingGatewayObligationService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains {@code billing_gateway_obligation} (V310) — the durable record of an irreversible gateway
 * action (refund / cancellation) that failed or was interrupted.
 *
 * <p><b>RR-049 / RR-352 / RR-353 / RR-1681.</b> Those findings made a failed cancellation or refund
 * a durable, retryable work item instead of a swallowed warning — but nothing ever retried them and
 * nothing surfaced them, so obligations accumulated silently. This scheduler is the missing
 * recovery half: it retries unresolved obligations with bounded exponential backoff and
 * dead-letters after {@link BillingGatewayObligationService#MAX_ATTEMPTS} attempts, while the admin
 * obligations endpoint exposes the unresolved counts so a stuck obligation is visible rather than
 * silent.
 *
 * <p><b>Idempotency — no double refund, no double cancel.</b> Every retry proves gateway state
 * before it re-acts, keyed on the obligation's own identity:
 *
 * <ul>
 *   <li><b>Refund:</b> {@link RazorpayGateway#findExistingRefundId} is consulted first; a payment
 *       that already carries a matching refund is recorded as settled and never refunded again.
 *       When a refund IS re-issued, the obligation id is passed as Razorpay's idempotency key so
 *       even a racing double-issue collapses to the original refund at the gateway.
 *   <li><b>Cancellation:</b> the subscription's current gateway status is read first; a
 *       subscription already in a terminal cancelled state is recorded as settled without a second
 *       cancel call.
 * </ul>
 *
 * <p>Runs by default (kill switch: {@code billing.obligation-drainer.enabled=false}); the cadence
 * is {@code billing.obligation-drainer.cron} (default every 15 minutes). It is deliberately NOT
 * gated behind a live feature flag — an unattended safety drainer should not depend on Redis flag
 * state.
 *
 * <p><b>Known limitation.</b> When the drainer issues a genuinely new prorated refund (the happy
 * path never completed), it settles the obligation and records the gateway refund id, but does NOT
 * regenerate the credit note / customer email that {@code BillingService} produces on a
 * first-attempt refund — the obligation lacks the plan/usage context those need. That case is
 * logged at WARN for operator follow-up rather than silently producing partial accounting.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BillingObligationDrainer {

  /**
   * Grace before a freshly-recorded (attempt 0) obligation is eligible — lets the happy path win.
   */
  private static final Duration BASE_BACKOFF = Duration.ofMinutes(10);

  /** Upper bound on the exponential backoff between retries. */
  private static final Duration MAX_BACKOFF = Duration.ofHours(6);

  private final BillingGatewayObligationService obligations;
  private final PaymentGatewayRouter gatewayRouter;
  private final RazorpayGateway razorpayGateway;

  @Value("${billing.obligation-drainer.enabled:true}")
  private boolean enabled;

  @Scheduled(cron = "${billing.obligation-drainer.cron:0 */15 * * * *}")
  public void drain() {
    if (!enabled) {
      return;
    }
    List<BillingGatewayObligation> candidates = obligations.retryable();
    if (candidates.isEmpty()) {
      return;
    }
    Instant now = Instant.now();
    int attempted = 0;
    for (BillingGatewayObligation o : candidates) {
      if (!isEligible(o, now)) {
        continue;
      }
      attempted++;
      try {
        settle(o);
      } catch (Exception e) {
        // A settle() branch should have already recorded markFailed; this is the last-resort guard
        // so one bad row cannot abort the whole sweep.
        log.error(
            "[ObligationDrainer] Unhandled error settling obligation {} (kind={}): {}",
            o.getId(),
            o.getKind(),
            e.getMessage(),
            e);
      }
    }
    if (attempted > 0) {
      log.info(
          "[ObligationDrainer] Examined {} candidate(s), attempted {} this pass",
          candidates.size(),
          attempted);
    }
  }

  /** Per-row exponential backoff: an obligation is skipped until {@code updatedAt + backoff}. */
  private boolean isEligible(BillingGatewayObligation o, Instant now) {
    Instant base = o.getUpdatedAt() != null ? o.getUpdatedAt() : o.getCreatedAt();
    if (base == null) {
      return true;
    }
    return !now.isBefore(base.plus(backoff(o.getAttempts())));
  }

  private Duration backoff(int attempts) {
    int shift = Math.min(Math.max(attempts, 0), 5); // cap the shift so 1L << shift cannot overflow
    long minutes = BASE_BACKOFF.toMinutes() << shift;
    return Duration.ofMinutes(Math.min(minutes, MAX_BACKOFF.toMinutes()));
  }

  private void settle(BillingGatewayObligation o) {
    switch (o.getKind()) {
      case BillingGatewayObligation.KIND_CANCEL_OLD_SUBSCRIPTION -> settleCancellation(o, true);
      case BillingGatewayObligation.KIND_TIER_SUNSET_CANCEL -> settleCancellation(o, false);
      case BillingGatewayObligation.KIND_PRORATED_REFUND -> settleRefund(o);
      default -> {
        log.error(
            "[ObligationDrainer] Unknown obligation kind '{}' for {} — marking UNSUPPORTED for"
                + " operator review",
            o.getKind(),
            o.getId());
        obligations.markUnsupported(o.getId(), "Unknown obligation kind: " + o.getKind());
      }
    }
  }

  /**
   * Retry a subscription cancellation. {@code immediate} distinguishes the paid-upgrade supersede
   * (immediate cancel) from the tier-sunset (cancel at period end). Checks the gateway's current
   * subscription status first so an already-cancelled subscription is recorded as settled rather
   * than throwing on a second cancel call.
   */
  private void settleCancellation(BillingGatewayObligation o, boolean immediate) {
    String gatewayName = o.getGateway();
    String ref = o.getGatewayRef();
    if (ref == null || ref.isBlank()) {
      log.warn(
          "[ObligationDrainer] Cancellation obligation {} has no gateway ref — marking UNSUPPORTED",
          o.getId());
      obligations.markUnsupported(o.getId(), "No gateway subscription id on obligation");
      return;
    }

    PaymentGateway gateway = gatewayRouter.resolveByName(gatewayName);
    if (!gatewayMatches(gateway, gatewayName)) {
      // The obligation's gateway is not currently enabled; router.resolveByName fell back to a
      // different gateway. Acting would target the wrong provider with this ref. Skip WITHOUT
      // marking failed so the row is retried when the gateway is re-enabled (config, not a defect).
      log.warn(
          "[ObligationDrainer] Gateway '{}' unavailable for obligation {} — deferring (resolved to"
              + " {})",
          gatewayName,
          o.getId(),
          gateway.gatewayType());
      return;
    }

    if (isAlreadyCancelled(gateway, ref)) {
      log.info(
          "[ObligationDrainer] Subscription {} already cancelled at {} — recording obligation {} as"
              + " settled",
          ref,
          gatewayName,
          o.getId());
      obligations.markSucceeded(o.getId(), ref);
      return;
    }

    try {
      if (immediate) {
        gateway.cancelSubscription(ref);
      } else {
        gateway.cancelAtPeriodEnd(ref);
      }
      obligations.markSucceeded(o.getId(), ref);
      log.info(
          "[ObligationDrainer] Retried cancellation of {} at {} succeeded (obligation {})",
          ref,
          gatewayName,
          o.getId());
    } catch (Exception e) {
      recordFailure(o, e.getMessage());
    }
  }

  /**
   * Retry a prorated refund. Razorpay is the only gateway with an automated refund path; anything
   * else is recorded UNSUPPORTED (an operator must settle it). Before re-issuing, an existing
   * refund on the payment is treated as proof the refund already landed — so the customer's money
   * is never moved twice.
   */
  private void settleRefund(BillingGatewayObligation o) {
    String gatewayName = o.getGateway();
    if (!"RAZORPAY".equalsIgnoreCase(gatewayName)) {
      log.warn(
          "[ObligationDrainer] Refund obligation {} on gateway {} has no automated path — marking"
              + " UNSUPPORTED",
          o.getId(),
          gatewayName);
      obligations.markUnsupported(
          o.getId(), "No automated refund path for gateway " + gatewayName + "; settle manually");
      return;
    }
    if (!gatewayRouter.isRazorpayEnabled()) {
      log.warn("[ObligationDrainer] Razorpay disabled — deferring refund obligation {}", o.getId());
      return;
    }

    String paymentId = o.getGatewayRef();
    Long amount = o.getAmountMinor();
    if (paymentId == null || paymentId.isBlank() || amount == null || amount <= 0) {
      log.warn(
          "[ObligationDrainer] Refund obligation {} missing paymentId/amount — marking UNSUPPORTED",
          o.getId());
      obligations.markUnsupported(o.getId(), "Missing gateway payment id or refund amount");
      return;
    }

    // Idempotency: a refund already on this payment means the (possibly-crashed) first attempt did
    // move the money. Record it and stop — never refund twice.
    String existing;
    try {
      existing = razorpayGateway.findExistingRefundId(paymentId, amount);
    } catch (Exception e) {
      // Could not prove gateway state — do NOT re-issue blind, that risks a double refund. Fail and
      // retry later when the lookup works.
      recordFailure(o, "refund state lookup failed: " + e.getMessage());
      return;
    }
    if (existing != null) {
      log.info(
          "[ObligationDrainer] Refund already present on payment {} (refundId={}) — recording"
              + " obligation {} as settled",
          paymentId,
          existing,
          o.getId());
      obligations.markSucceeded(o.getId(), existing);
      return;
    }

    String reason = o.getDetail() != null ? o.getDetail() : "Prorated refund (drained obligation)";
    try {
      // Pass the obligation id as Razorpay's idempotency key so even a concurrent re-issue
      // collapses
      // to the original refund at the gateway.
      String refundId =
          razorpayGateway.refundPartial(paymentId, amount, reason, o.getId().toString());
      if (refundId == null || refundId.isBlank()) {
        recordFailure(o, "Razorpay refund returned no refund id");
        return;
      }
      obligations.markSucceeded(o.getId(), refundId);
      log.warn(
          "[ObligationDrainer] Issued prorated refund {} for obligation {} (payment {}, {} paise)."
              + " Credit note + customer email are NOT auto-generated by the drainer — operator"
              + " follow-up required for accounting.",
          refundId,
          o.getId(),
          paymentId,
          amount);
    } catch (Exception e) {
      recordFailure(o, e.getMessage());
    }
  }

  private boolean isAlreadyCancelled(PaymentGateway gateway, String ref) {
    String status;
    try {
      status = gateway.subscriptionStatus(ref);
    } catch (Exception e) {
      // Unknown status is not proof of cancellation — fall through and attempt the cancel.
      log.warn(
          "[ObligationDrainer] Could not read status for subscription {}: {}", ref, e.getMessage());
      return false;
    }
    if (status == null) {
      return false;
    }
    return switch (status) {
      case "cancelled", "canceled", "completed", "expired" -> true;
      default -> false;
    };
  }

  private boolean gatewayMatches(PaymentGateway gateway, String gatewayName) {
    return gatewayName != null
        && gateway.gatewayType() != null
        && gateway.gatewayType().equalsIgnoreCase(gatewayName.trim());
  }

  private void recordFailure(BillingGatewayObligation o, String error) {
    obligations.markFailed(o.getId(), error);
    int nextAttempts = o.getAttempts() + 1;
    if (nextAttempts >= BillingGatewayObligationService.MAX_ATTEMPTS) {
      log.error(
          "[ObligationDrainer] DEAD-LETTER: obligation {} (kind={}, gateway={}, ref={}) failed"
              + " {} times and will no longer be retried automatically — operator must settle it."
              + " Last error: {}",
          o.getId(),
          o.getKind(),
          o.getGateway(),
          o.getGatewayRef(),
          nextAttempts,
          error);
    } else {
      log.warn(
          "[ObligationDrainer] Obligation {} (kind={}) retry failed ({}/{}): {}",
          o.getId(),
          o.getKind(),
          nextAttempts,
          BillingGatewayObligationService.MAX_ATTEMPTS,
          error);
    }
  }
}
