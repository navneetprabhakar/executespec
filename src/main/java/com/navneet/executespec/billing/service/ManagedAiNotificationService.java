package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.billing.entity.ManagedAiUsagePeriod;
import com.navneet.executespec.billing.repository.ManagedAiStateNotificationRepository;
import com.navneet.executespec.notification.InAppNotificationService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManagedAiNotificationService {

  private final ManagedAiStateNotificationRepository repository;
  private final InAppNotificationService inAppNotificationService;
  private final BillingNotificationService billingNotificationService;
  private final AppUserRepository appUserRepository;
  private final ManagedAiNotificationPolicy notificationPolicy;

  @Transactional
  public void notifyTransition(ManagedAiUsagePeriod period, String fromState, String toState) {
    if (!notificationPolicy.shouldNotify(period, fromState, toState)) return;
    notifyInApp(period, fromState, toState);
    if (notificationPolicy.shouldEmail(toState)) {
      notifyEmail(period, fromState, toState);
    }
  }

  private void notifyInApp(ManagedAiUsagePeriod period, String fromState, String toState) {
    if (repository.existsByPeriodIdAndToStateAndChannel(
        period.getId(), toState, ManagedAiNotificationPolicy.CHANNEL_IN_APP)) {
      return;
    }
    inAppNotificationService.create(
        period.getUserId(),
        "MANAGED_AI_ROUTING_STATE",
        "Managed AI routing update",
        notificationPolicy.copyFor(toState),
        "/billing");
    repository.save(
        notificationPolicy.record(
            period, fromState, toState, ManagedAiNotificationPolicy.CHANNEL_IN_APP));
  }

  private void notifyEmail(ManagedAiUsagePeriod period, String fromState, String toState) {
    if (repository.existsByPeriodIdAndToStateAndChannel(
        period.getId(), toState, ManagedAiNotificationPolicy.CHANNEL_EMAIL)) {
      return;
    }
    appUserRepository
        .findById(period.getUserId())
        .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
        .ifPresent(
            user -> {
              billingNotificationService.sendManagedAiRoutingStateChanged(
                  user.getEmail(), toState, notificationPolicy.copyFor(toState));
              repository.save(
                  notificationPolicy.record(
                      period, fromState, toState, ManagedAiNotificationPolicy.CHANNEL_EMAIL));
            });
  }
}
