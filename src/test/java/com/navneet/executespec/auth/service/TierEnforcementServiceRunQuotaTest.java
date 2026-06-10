package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.billing.service.RunTopupService;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class TierEnforcementServiceRunQuotaTest {

  private AppUserRepository userRepo;
  private TierConfigRepository tierConfigRepo;
  private RunTopupService runTopupService;
  private BillingSubscriptionRepository subscriptionRepo;
  private TierEnforcementService service;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    tierConfigRepo = mock(TierConfigRepository.class);
    runTopupService = mock(RunTopupService.class);
    subscriptionRepo = mock(BillingSubscriptionRepository.class);
    service =
        new TierEnforcementService(
            userRepo,
            mock(OrgRepository.class),
            tierConfigRepo,
            runTopupService,
            subscriptionRepo,
            mock(ApplicationEventPublisher.class),
            mock(ProjectRepository.class),
            mock(ProjectRepoLinkRepository.class));
  }

  @Test
  void freeUserAtMonthlyLimitCanSpendAvailableTopupRun() {
    AppUser user = new AppUser();
    user.setId("user-1");
    user.setTier("FREE");
    user.setPersonalRunCount(15);
    user.setRunCountResetAt(Instant.now());
    TierConfig free = new TierConfig();
    free.setTier("FREE");
    free.setMonthlyRunLimit(15);

    when(userRepo.findByIdForUpdate("user-1")).thenReturn(Optional.of(user));
    when(tierConfigRepo.findById("FREE")).thenReturn(Optional.of(free));
    when(subscriptionRepo.findActiveByUserId("user-1")).thenReturn(Optional.empty());
    when(runTopupService.getAvailableBonus("user-1")).thenReturn(1L);
    when(runTopupService.consumeBonusRun("user-1")).thenReturn(true);

    service.assertAndIncrementRunCount("user-1");

    assertThat(user.getPersonalRunCount()).isEqualTo(16);
    verify(userRepo).save(user);
    verify(runTopupService).consumeBonusRun("user-1");
  }
}
