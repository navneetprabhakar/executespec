package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.notification.InAppNotificationService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManagedAiNotificationService {

  private final ManagedAiNotificationDedupe dedupe;
  private final InAppNotificationService inAppNotificationService;
  private final BillingNotificationService billingNotificationService;
  private final AppUserRepository appUserRepository;
  private final ManagedAiNotificationPolicy notificationPolicy;

  /**
   * RR-520 — deliveries are intentionally NOT wrapped in a single transaction. The dedupe row is
   * the atomic claim (committed in its own {@code REQUIRES_NEW} transaction before the side
   * effect), so delivery is a non-transactional external effect that runs only for the claim
   * winner.
   */
  public void notifyTransition(ManagedAiUsagePeriod period, String fromState, String toState) {
    if (!notificationPolicy.shouldNotify(period, fromState, toState)) return;
    notifyInApp(period, fromState, toState);
    if (notificationPolicy.shouldEmail(toState)) {
      notifyEmail(period, fromState, toState);
    }
  }

  private void notifyInApp(ManagedAiUsagePeriod period, String fromState, String toState) {
    // Claim first: only the caller that inserts the unique dedupe row proceeds to deliver.
    if (!dedupe.claim(
        notificationPolicy.record(
            period, fromState, toState, ManagedAiNotificationPolicy.CHANNEL_IN_APP))) {
      return;
    }
    inAppNotificationService.create(
        period.getUserId(),
        "MANAGED_AI_ROUTING_STATE",
        "Managed AI routing update",
        notificationPolicy.copyFor(toState),
        "/billing");
  }

  private void notifyEmail(ManagedAiUsagePeriod period, String fromState, String toState) {
    appUserRepository
        .findById(period.getUserId())
        .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
        .ifPresent(
            user -> {
              // Presence-check the email BEFORE claiming so a user without an address never leaves
              // a
              // spurious EMAIL dedupe row; then claim-before-send exactly as the in-app channel.
              if (!dedupe.claim(
                  notificationPolicy.record(
                      period, fromState, toState, ManagedAiNotificationPolicy.CHANNEL_EMAIL))) {
                return;
              }
              billingNotificationService.sendManagedAiRoutingStateChanged(
                  user.getEmail(), toState, notificationPolicy.copyFor(toState));
            });
  }
}
