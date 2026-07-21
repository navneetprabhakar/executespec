package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingActivationClaimRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Webhook temporal correctness for the billing path (RR-054 + RR-056).
 *
 * <p>Two orthogonal defects in the Stripe/Razorpay webhook handlers share this seam:
 *
 * <ul>
 *   <li><b>RR-056 — activation is not idempotent.</b> A post-checkout callback and a webhook (and
 *       replays of either) both retire superseded subscriptions, generate invoices and notify, with
 *       no gate. {@link #claimActivation} claims the activation by its IMMUTABLE gateway
 *       payment/invoice id: exactly one caller per payment id wins and performs the effects; every
 *       duplicate is told to skip them.
 *   <li><b>RR-054 — transitions apply in arrival order.</b> Handlers overwrite status/period on
 *       every event using {@code Instant.now()}, ignoring the provider's own event time. A delayed
 *       {@code subscription.pending} arriving after {@code subscription.charged} could regress
 *       ACTIVE back to PAST_DUE. {@link #isStaleTransition} compares the provider event time
 *       against a persisted high-water mark so the last writer is decided by provider time, not by
 *       which event happened to be delivered last.
 * </ul>
 *
 * <p>Both concerns are threaded through the handlers rather than baked into {@code BillingService}
 * so they stay independently testable and do not grow an already-large class.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BillingWebhookSequencingService {

  private final BillingActivationClaimRepository claimRepository;

  /**
   * RR-056 — claim the activation identified by {@code claimKey} (an immutable gateway
   * payment/invoice id) for exactly one caller.
   *
   * <p>Returns {@code true} for the single caller whose claim row was inserted — that caller owns
   * the activation and MUST perform its non-idempotent effects. Returns {@code false} for every
   * duplicate callback/webhook for the same payment id — that caller MUST skip retire / invoice /
   * notify because the winner did (or will) run them.
   *
   * <p>Runs in the caller's transaction (default {@link Transactional} propagation) so the claim
   * shares the activation's commit/rollback fate: a rolled-back activation releases the claim and a
   * genuine retry can proceed, while a committed activation permanently de-duplicates.
   *
   * <p>A blank {@code claimKey} means the gateway gave us no stable identity to de-duplicate on;
   * the caller is allowed to proceed (returns {@code true}) rather than being silently dropped —
   * fail-open is correct here because the alternative is losing a real activation.
   */
  @Transactional
  public boolean claimActivation(String gateway, String claimKey) {
    if (claimKey == null || claimKey.isBlank()) {
      log.debug("Activation claim skipped — no gateway payment id available for {}", gateway);
      return true;
    }
    int inserted =
        claimRepository.insertIfAbsent(
            UUID.randomUUID().toString(), gateway, claimKey, Instant.now());
    if (inserted == 0) {
      log.info(
          "Activation already claimed for gateway={}, key={} — skipping duplicate effects",
          gateway,
          claimKey);
      return false;
    }
    return true;
  }

  /**
   * RR-054 — is {@code eventTime} strictly older than the last provider event already applied to
   * {@code sub}? A stale event must not overwrite status/period set by a newer one.
   *
   * <p>Conservative: a null {@code eventTime} (the gateway did not give us a usable event time) or
   * a subscription with no recorded high-water mark yet is never treated as stale, so this can only
   * REJECT provably out-of-order events and never blocks a legitimate first transition.
   */
  public boolean isStaleTransition(BillingSubscription sub, Instant eventTime) {
    if (eventTime == null || sub == null || sub.getLastProviderEventAt() == null) {
      return false;
    }
    return eventTime.isBefore(sub.getLastProviderEventAt());
  }

  /**
   * RR-054 — advance the subscription's provider-event high-water mark after a transition is
   * accepted. Only moves forward, so an accepted-but-equal or older time never rewinds the mark.
   * The caller persists {@code sub} as part of the same transition it already saves.
   */
  public void markTransitionApplied(BillingSubscription sub, Instant eventTime) {
    if (sub == null || eventTime == null) {
      return;
    }
    Instant current = sub.getLastProviderEventAt();
    if (current == null || eventTime.isAfter(current)) {
      sub.setLastProviderEventAt(eventTime);
    }
  }
}
