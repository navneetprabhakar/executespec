package com.navneet.executespec.billing.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DunningScheduler {

  private final BillingSubscriptionRepository subRepo;
  private final BillingPlanRepository planRepo;
  private final AppUserRepository userRepo;
  private final MailService mailService;

  @Scheduled(cron = "0 0 9 * * *") // Daily at 9 AM
  public void sendDunningReminders() {
    List<BillingSubscription> pastDue = subRepo.findByStatus("PAST_DUE");
    if (pastDue.isEmpty()) return;

    log.info("Dunning check: {} past-due subscriptions", pastDue.size());
    Instant now = Instant.now();

    for (BillingSubscription sub : pastDue) {
      if (sub.getUpdatedAt() == null) continue;

      long daysPastDue = ChronoUnit.DAYS.between(sub.getUpdatedAt(), now);
      String urgency;

      if (daysPastDue >= 14) {
        urgency = "FINAL";
      } else if (daysPastDue >= 7) {
        urgency = "URGENT";
      } else if (daysPastDue >= 3) {
        urgency = "GENTLE";
      } else {
        continue; // Too soon for a reminder
      }

      var user = userRepo.findById(sub.getUserId()).orElse(null);
      if (user == null) continue;

      String planName =
          planRepo.findById(sub.getBillingPlanId()).map(p -> p.getName()).orElse("your plan");

      try {
        mailService.sendDunningReminder(user.getEmail(), user.getFirstName(), planName, urgency);
        log.info(
            "Dunning {} reminder sent: user={}, daysPastDue={}",
            urgency,
            user.getEmail(),
            daysPastDue);
      } catch (Exception e) {
        log.warn("Failed to send dunning reminder to {}: {}", user.getEmail(), e.getMessage());
      }
    }
  }
}
