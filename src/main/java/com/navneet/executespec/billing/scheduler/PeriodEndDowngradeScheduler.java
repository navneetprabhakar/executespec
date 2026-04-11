package com.navneet.executespec.billing.scheduler;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Downgrades cancelled subscriptions to FREE after the billing period ends.
 *
 * <p>Runs every 30 minutes. Finds all CANCELLED subscriptions whose
 * {@code currentPeriodEnd} has passed, then downgrades the associated
 * user/org to FREE tier.
 *
 * <p>This ensures users retain access until their paid period expires
 * (grace period) before being downgraded.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeriodEndDowngradeScheduler {

    private final BillingSubscriptionRepository subRepo;
    private final AppUserRepository userRepo;
    private final OrgRepository orgRepo;

    @Scheduled(cron = "0 */30 * * * *") // Every 30 minutes
    public void handlePeriodEndDowngrades() {
        List<BillingSubscription> expired = subRepo.findCancelledPastPeriodEnd(Instant.now());
        if (expired.isEmpty()) return;

        log.info("[PeriodEndDowngrade] Found {} cancelled subscriptions past period end", expired.size());

        for (BillingSubscription sub : expired) {
            try {
                // Downgrade user
                userRepo.findById(sub.getUserId()).ifPresent(user -> {
                    if (!"FREE".equals(user.getTier())) {
                        user.setTier("FREE");
                        userRepo.save(user);
                        log.info("[PeriodEndDowngrade] Downgraded user {} to FREE", user.getEmail());
                    }
                });

                // Downgrade org if subscription is org-level
                if (sub.getOrgId() != null) {
                    orgRepo.findById(sub.getOrgId()).ifPresent(org -> {
                        if (!"FREE".equals(org.getTier())) {
                            org.setTier("FREE");
                            orgRepo.save(org);
                            log.info("[PeriodEndDowngrade] Downgraded org {} to FREE", org.getName());
                        }
                    });
                }

                // Mark subscription as DOWNGRADED (terminal state)
                sub.setStatus("DOWNGRADED");
                sub.setUpdatedAt(Instant.now());
                subRepo.save(sub);

            } catch (Exception e) {
                log.error("[PeriodEndDowngrade] Failed to downgrade sub {}: {}",
                        sub.getId(), e.getMessage());
            }
        }
    }
}
