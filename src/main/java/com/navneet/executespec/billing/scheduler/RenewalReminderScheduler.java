package com.navneet.executespec.billing.scheduler;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.notification.InAppNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RenewalReminderScheduler {

    private final BillingSubscriptionRepository subRepo;
    private final BillingPlanRepository planRepo;
    private final AppUserRepository userRepo;
    private final MailService mailService;
    private final InAppNotificationService notificationService;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy").withZone(ZoneId.of("Asia/Kolkata"));

    /**
     * Sends renewal reminders 3 days before subscription period end.
     * Runs daily at 9:15 AM (offset from dunning at 9:00 AM).
     */
    @Scheduled(cron = "0 15 9 * * *")
    public void sendRenewalReminders() {
        Instant now = Instant.now();
        Instant threshold = now.plus(3, ChronoUnit.DAYS);

        List<BillingSubscription> upcoming = subRepo.findRenewingBefore(now, threshold);
        if (upcoming.isEmpty()) return;

        log.info("Renewal reminder check: {} subscriptions renewing within 3 days", upcoming.size());

        for (BillingSubscription sub : upcoming) {
            try {
                AppUser user = userRepo.findById(sub.getUserId()).orElse(null);
                if (user == null) continue;

                String planName = planRepo.findById(sub.getBillingPlanId())
                        .map(p -> p.getName())
                        .orElse("your plan");

                String renewalDate = DATE_FMT.format(sub.getCurrentPeriodEnd());

                // Email reminder
                mailService.sendRenewalReminder(user.getEmail(), user.getFirstName(), planName, renewalDate);

                // In-app notification
                notificationService.create(
                        sub.getUserId(),
                        "RENEWAL_REMINDER",
                        "Subscription renewing soon",
                        "Your " + planName + " subscription will renew on " + renewalDate + ".",
                        "/billing"
                );

                log.info("Renewal reminder sent: user={}, plan={}, renewalDate={}",
                        user.getEmail(), planName, renewalDate);
            } catch (Exception e) {
                log.warn("Failed to send renewal reminder for sub {}: {}", sub.getId(), e.getMessage());
            }
        }
    }
}
