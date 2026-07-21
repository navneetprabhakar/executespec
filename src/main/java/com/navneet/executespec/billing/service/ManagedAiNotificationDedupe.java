package com.navneet.executespec.billing.service;

import com.navneet.executespec.billing.entity.ManagedAiStateNotification;
import com.navneet.executespec.billing.repository.ManagedAiStateNotificationRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * RR-520 — atomic claim boundary for managed-AI routing-state notifications.
 *
 * <p>The old flow was {@code exists()} → deliver in-app/email → {@code save()} the dedupe row.
 * Between the read and the write two concurrent transitions could both see "not sent", both
 * deliver, and then one lose the unique-index insert or roll back — the database recorded one
 * notification while the customer received two, and a retry re-delivered.
 *
 * <p>This component inverts the boundary: it inserts (claims) the unique {@code (period_id,
 * to_state, channel)} row FIRST, in its own committed transaction, and only the winner is told it
 * may deliver. The unique index {@code ux_managed_ai_state_notification_once} (V245) makes the
 * insert the atomic winner-selection: concurrent inserts of the same key are serialized by the
 * database, the loser gets a {@link DataIntegrityViolationException}, and any later retry meets the
 * committed row and is refused. Because the claim commits before the side effect runs, a
 * rolled-back or losing caller can never also deliver.
 *
 * <p>The claim is deliberately {@link Propagation#REQUIRES_NEW}: it must be durable independently
 * of whatever transaction (if any) the caller is running in, and it must be its own unit so a
 * caught constraint violation does not mark an enclosing transaction rollback-only.
 *
 * <p>Trade-off (documented, not a regression): delivery is now at-most-once per committed claim. A
 * delivery that fails after the claim commits is not retried — a durable retry needs the outbox
 * worker the finding calls for. That is strictly better than the previous at-least-once
 * double-send: the P1 here is duplicate customer notifications, and the claim eliminates them.
 */
@Component
@RequiredArgsConstructor
class ManagedAiNotificationDedupe {

  private final ManagedAiStateNotificationRepository repository;

  /**
   * Attempt to claim the notification identity. Returns {@code true} only for the single caller
   * whose insert committed; every concurrent or repeat caller for the same identity returns {@code
   * false} and must not deliver.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(ManagedAiStateNotification record) {
    try {
      repository.saveAndFlush(record);
      return true;
    } catch (DataIntegrityViolationException alreadyClaimed) {
      // Another transition already owns this (period, to_state, channel) row. The loser must not
      // deliver — the winner did (or will).
      return false;
    }
  }
}
