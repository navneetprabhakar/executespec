package com.navneet.executespec.billing.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.junit.jupiter.api.Test;

/**
 * RR-517 — the period-end job used to set the user (and optional org) to FREE for every expired
 * CANCELLED row, without ever checking for a newer paid subscription. A cancel → resubscribe before
 * the old period ended therefore erased the customer's currently-paid tier.
 */
class PeriodEndDowngradeSchedulerTest {

  private final BillingSubscriptionRepository subRepo = mock(BillingSubscriptionRepository.class);
  private final BillingPlanRepository planRepo = mock(BillingPlanRepository.class);
  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final PeriodEndDowngradeScheduler scheduler =
      new PeriodEndDowngradeScheduler(subRepo, planRepo, userRepo, orgRepo);

  private final UUID paidPlanId = UUID.randomUUID();
  private final UUID freePlanId = UUID.randomUUID();

  @Test
  void doesNotDowngradeWhenANewerPaidSubscriptionExists() {
    BillingSubscription expired = expiredCancelled("user-1", "org-1");
    BillingSubscription newerPaid =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .orgId("org-1")
            .billingPlanId(paidPlanId)
            .status("ACTIVE")
            .currentPeriodEnd(Instant.now().plus(20, ChronoUnit.DAYS))
            .build();
    AppUser user = AppUser.builder().id("user-1").email("u@example.com").tier("TEAM").build();
    Org org = Org.builder().id("org-1").tier("TEAM").build();

    when(subRepo.findOtherLiveByUserId("user-1", expired.getId())).thenReturn(List.of(newerPaid));
    when(subRepo.findOtherLiveByOrgId("org-1", expired.getId())).thenReturn(List.of(newerPaid));
    when(planRepo.findById(paidPlanId))
        .thenReturn(Optional.of(BillingPlan.builder().id(paidPlanId).tier("TEAM").build()));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));

    scheduler.downgradeIfNoRemainingPaidSource(expired);

    assertThat(user.getTier()).isEqualTo("TEAM");
    assertThat(org.getTier()).isEqualTo("TEAM");
    verify(userRepo, never()).save(any());
    verify(orgRepo, never()).save(any());
    // The stale row is still retired, so the job stays idempotent.
    assertThat(expired.getStatus()).isEqualTo("DOWNGRADED");
    verify(subRepo).save(expired);
  }

  @Test
  void downgradesWhenNoOtherSubscriptionRemains() {
    BillingSubscription expired = expiredCancelled("user-1", "org-1");
    AppUser user = AppUser.builder().id("user-1").email("u@example.com").tier("TEAM").build();
    Org org = Org.builder().id("org-1").tier("TEAM").build();

    when(subRepo.findOtherLiveByUserId("user-1", expired.getId())).thenReturn(List.of());
    when(subRepo.findOtherLiveByOrgId("org-1", expired.getId())).thenReturn(List.of());
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));
    when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));

    scheduler.downgradeIfNoRemainingPaidSource(expired);

    assertThat(user.getTier()).isEqualTo("FREE");
    assertThat(org.getTier()).isEqualTo("FREE");
    assertThat(expired.getStatus()).isEqualTo("DOWNGRADED");
  }

  @Test
  void anEquallyStaleOrFreeRowDoesNotBlockTheDowngrade() {
    BillingSubscription expired = expiredCancelled("user-1", null);
    BillingSubscription alsoExpired =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .billingPlanId(paidPlanId)
            .status("ACTIVE")
            .currentPeriodEnd(Instant.now().minus(2, ChronoUnit.DAYS))
            .build();
    BillingSubscription freeRow =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("user-1")
            .billingPlanId(freePlanId)
            .status("ACTIVE")
            .currentPeriodEnd(Instant.now().plus(10, ChronoUnit.DAYS))
            .build();
    AppUser user = AppUser.builder().id("user-1").email("u@example.com").tier("PRO").build();

    when(subRepo.findOtherLiveByUserId("user-1", expired.getId()))
        .thenReturn(List.of(alsoExpired, freeRow));
    when(planRepo.findById(freePlanId))
        .thenReturn(Optional.of(BillingPlan.builder().id(freePlanId).tier("FREE").build()));
    when(userRepo.findById("user-1")).thenReturn(Optional.of(user));

    scheduler.downgradeIfNoRemainingPaidSource(expired);

    assertThat(user.getTier()).isEqualTo("FREE");
  }

  private BillingSubscription expiredCancelled(String userId, String orgId) {
    return BillingSubscription.builder()
        .id(UUID.randomUUID())
        .userId(userId)
        .orgId(orgId)
        .billingPlanId(paidPlanId)
        .status("CANCELLED")
        .currentPeriodEnd(Instant.now().minus(1, ChronoUnit.DAYS))
        .build();
  }
}
