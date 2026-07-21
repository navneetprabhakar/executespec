package com.navneet.executespec.billing.scheduler;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.billing.entity.BillingReminderDispatch;
import com.navneet.executespec.billing.repository.BillingReminderDispatchRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * RR-518 — atomic, durable delivery claim for billing reminders (renewal + dunning).
 *
 * <p>A scheduler calls {@link #claim} before sending. The insert into {@code
 * billing_reminder_dispatch} is guarded by the unique index on {@code (subscription_id, period_key,
 * reminder_type, stage, channel)}, so the FIRST daily run (on the FIRST replica) that reaches a
 * given reminder identity wins the insert and delivers; every later run — the next day, another
 * replica, a webhook-triggered re-selection — meets the committed row, the insert fails, and {@code
 * claim} returns {@code false}. The reminder is therefore sent exactly once per billing period per
 * channel.
 *
 * <p>The claim runs in its own {@link Propagation#REQUIRES_NEW} transaction so it is durable
 * independently of the scheduler loop and a caught constraint violation cannot poison an enclosing
 * transaction.
 */
@Component
@RequiredArgsConstructor
public class BillingReminderDispatchGuard {

  static final String TYPE_RENEWAL = "RENEWAL";
  static final String TYPE_DUNNING = "DUNNING";
  static final String CHANNEL_EMAIL = "EMAIL";
  static final String CHANNEL_IN_APP = "IN_APP";

  private final BillingReminderDispatchRepository repository;

  /**
   * Claim one reminder delivery identity. Returns {@code true} only for the single caller whose
   * insert committed; every repeat or concurrent caller for the same identity returns {@code false}
   * and must not deliver.
   *
   * @param periodEnd the subscription's current period end — the stable billing-cycle key (never
   *     the mutable {@code updatedAt}); a null value collapses to a fixed sentinel so the row is
   *     still uniquely claimable.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(
      String subscriptionId,
      String userId,
      Instant periodEnd,
      String reminderType,
      String stage,
      String channel) {
    try {
      repository.saveAndFlush(
          BillingReminderDispatch.builder()
              .id(UUID.randomUUID().toString())
              .subscriptionId(subscriptionId)
              .userId(userId)
              .periodKey(periodEnd == null ? "no-period" : periodEnd.toString())
              .reminderType(reminderType)
              .stage(stage)
              .channel(channel)
              .dispatchedAt(Instant.now())
              .build());
      return true;
    } catch (DataIntegrityViolationException alreadyDispatched) {
      return false;
    }
  }
}
