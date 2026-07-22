package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.dto.ChangePasswordRequest;
import com.navneet.executespec.auth.entity.AppUser;
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
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Password-change security behaviour.
 *
 * <ul>
 *   <li>RR-134 — a credential change must advance the per-user session epoch so tokens minted
 *       before the change are revoked.
 *   <li>RR-603 — the "no whitespace" guarantee must actually reject tabs (and every other Unicode
 *       whitespace codepoint), not just a literal space.
 * </ul>
 */
class AuthServicePasswordChangeTest {

  private AppUserRepository userRepo;
  private PasswordEncoder passwordEncoder;
  private AuthService service;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.encode(anyString())).thenReturn("hashed");
    service =
        new AuthService(
            userRepo,
            mock(EmailTokenRepository.class),
            mock(RefreshTokenBlocklistRepository.class),
            mock(OrgMemberRepository.class),
            passwordEncoder,
            mock(JwtUtil.class),
            mock(AuthenticationManager.class),
            mock(MailService.class),
            mock(ActivityAuditService.class),
            mock(BillingPlanRepository.class),
            mock(BillingSubscriptionRepository.class),
            mock(com.navneet.executespec.analytics.EventTrackingService.class),
            mock(ReferralService.class),
            mock(OrgService.class),
            mock(TierEnforcementService.class));
  }

  @Test
  void changePasswordRevokesExistingSessions() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setMustChangePassword(true); // skip current-password check
    when(userRepo.findById("u1")).thenReturn(Optional.of(user));

    service.changePassword("u1", new ChangePasswordRequest(null, "N3wPassw0rd!"));

    ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
    org.mockito.Mockito.verify(userRepo).save(saved.capture());
    // RR-134 — tokenInvalidatedAt is advanced, so JwtAuthenticationFilter rejects older tokens.
    assertThat(saved.getValue().getTokenInvalidatedAt()).isNotNull();
  }

  @Test
  void changePasswordRejectsTabWhitespace() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setMustChangePassword(true);
    when(userRepo.findById("u1")).thenReturn(Optional.of(user));

    // A tab is whitespace but not a literal space — the old contains(" ") check let it through.
    assertThatThrownBy(
            () -> service.changePassword("u1", new ChangePasswordRequest(null, "N3w\tPassw0rd!")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("whitespace");
    org.mockito.Mockito.verify(userRepo, org.mockito.Mockito.never()).save(any());
  }
}
