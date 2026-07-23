package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.oauth.OAuthEmailConflictException;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.EmailTokenRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.RefreshTokenBlocklistRepository;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.referral.ReferralService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * RR-131 — OAuth email linking must require provider-verified-email evidence. A provider that
 * returns an unverified (or insufficiently assured) email must not link into an existing account.
 */
class AuthServiceOAuthLinkingTest {

  private AppUserRepository userRepo;
  private AuthService svc;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    svc =
        new AuthService(
            userRepo,
            mock(EmailTokenRepository.class),
            mock(RefreshTokenBlocklistRepository.class),
            mock(OrgMemberRepository.class),
            mock(PasswordEncoder.class),
            mock(JwtUtil.class),
            mock(AuthenticationManager.class),
            mock(MailService.class),
            mock(ActivityAuditService.class),
            mock(BillingPlanRepository.class),
            mock(BillingSubscriptionRepository.class),
            mock(EventTrackingService.class),
            mock(ReferralService.class),
            mock(OrgService.class),
            mock(TierEnforcementService.class));
  }

  private AppUser passwordAccount() {
    AppUser u = new AppUser();
    u.setId("existing-1");
    u.setEmail("owner@test.local");
    u.setStatus("ACTIVE");
    u.setPasswordHash("hash:secret");
    return u;
  }

  @Test
  void unverifiedProviderEmailCannotLinkIntoExistingAccount() {
    when(userRepo.findByOauthProviderAndOauthId("github", "gh-1")).thenReturn(Optional.empty());
    when(userRepo.findByEmail("owner@test.local")).thenReturn(Optional.of(passwordAccount()));

    assertThatThrownBy(
            () ->
                svc.findOrCreateFromOAuth(
                    "github", "gh-1", "owner@test.local", "Owner", null, false))
        .isInstanceOf(OAuthEmailConflictException.class);

    verify(userRepo, never()).save(any(AppUser.class));
  }

  @Test
  void verifiedProviderEmailLinksIntoExistingAccount() {
    AppUser existing = passwordAccount();
    when(userRepo.findByOauthProviderAndOauthId("google", "goog-1")).thenReturn(Optional.empty());
    when(userRepo.findByEmail("owner@test.local")).thenReturn(Optional.of(existing));
    when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

    AppUser linked =
        svc.findOrCreateFromOAuth("google", "goog-1", "owner@test.local", "Owner", null, true);

    assertThat(linked.getOauthProvider()).isEqualTo("google");
    assertThat(linked.getOauthId()).isEqualTo("goog-1");
    assertThat(linked.isEmailVerified()).isTrue();
    verify(userRepo).save(any(AppUser.class));
  }

  @Test
  void exactProviderMatchIsUnaffectedByVerificationFlag() {
    AppUser existing = passwordAccount();
    existing.setOauthProvider("github");
    existing.setOauthId("gh-1");
    when(userRepo.findByOauthProviderAndOauthId("github", "gh-1"))
        .thenReturn(Optional.of(existing));
    when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

    AppUser result =
        svc.findOrCreateFromOAuth("github", "gh-1", "owner@test.local", "Owner", null, false);

    assertThat(result.getId()).isEqualTo("existing-1");
    verify(userRepo, never()).findByEmail(anyString());
  }
}
