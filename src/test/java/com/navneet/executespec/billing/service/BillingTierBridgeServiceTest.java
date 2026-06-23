package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

class BillingTierBridgeServiceTest {

  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final OrgRepository orgRepo = mock(OrgRepository.class);
  private final BillingPlanRepository planRepo = mock(BillingPlanRepository.class);
  private final BillingSubscriptionRepository subRepo = mock(BillingSubscriptionRepository.class);
  private final BillingNotificationService notificationService =
      mock(BillingNotificationService.class);
  private final BillingTierBridgeService service =
      new BillingTierBridgeService(userRepo, orgRepo, planRepo, subRepo, notificationService);

  @Test
  void upgradeTierSyncsUserOrgTierAndKeySourceMode() {
    AppUser user = AppUser.builder().id("u1").tier("FREE").build();
    Org org = Org.builder().id("org1").tier("FREE").keySourceMode("SYSTEM").build();
    BillingPlan plan = BillingPlan.builder().tier("TEAM").keyMode("BYOK").build();

    when(userRepo.findById("u1")).thenReturn(Optional.of(user));
    when(orgRepo.findById("org1")).thenReturn(Optional.of(org));

    service.upgradeTier("u1", "org1", plan);

    assertThat(user.getTier()).isEqualTo("TEAM");
    assertThat(org.getTier()).isEqualTo("TEAM");
    assertThat(org.getKeySourceMode()).isEqualTo("OWN_KEYS");
    verify(userRepo).save(user);
    verify(orgRepo).save(org);
  }

  @Test
  void upgradeTierMapsLegacyMaxToTeamAndSkipsMissingOrg() {
    AppUser user = AppUser.builder().id("u1").tier("FREE").build();
    BillingPlan plan = BillingPlan.builder().tier("MAX").keyMode("MANAGED").build();

    when(userRepo.findById("u1")).thenReturn(Optional.of(user));

    service.upgradeTier("u1", null, plan);

    assertThat(user.getTier()).isEqualTo("TEAM");
    verify(userRepo).save(user);
  }

  @Test
  void downgradeToFreeSyncsSubscriptionUserOrgAndSendsNotification() {
    UUID planId = UUID.randomUUID();
    BillingSubscription sub =
        BillingSubscription.builder()
            .id(UUID.randomUUID())
            .userId("u1")
            .orgId("org1")
            .billingPlanId(planId)
            .status("ACTIVE")
            .build();
    AppUser user = AppUser.builder().id("u1").email("u1@example.com").tier("TEAM").build();
    Org org = Org.builder().id("org1").tier("TEAM").keySourceMode("OWN_KEYS").build();
    BillingPlan plan = BillingPlan.builder().id(planId).name("Team").build();

    when(userRepo.findById("u1")).thenReturn(Optional.of(user));
    when(orgRepo.findById("org1")).thenReturn(Optional.of(org));
    when(planRepo.findById(planId)).thenReturn(Optional.of(plan));

    service.downgradeToFree(sub);

    assertThat(sub.getStatus()).isEqualTo("DOWNGRADED");
    assertThat(sub.getDowngradedAt()).isNotNull();
    assertThat(user.getTier()).isEqualTo("FREE");
    assertThat(org.getTier()).isEqualTo("FREE");
    assertThat(org.getKeySourceMode()).isEqualTo("SYSTEM");
    verify(subRepo).save(sub);
    verify(userRepo).save(user);
    verify(orgRepo).save(org);
    verify(notificationService).sendDowngradedToFree("u1@example.com", "Team");
  }
}
