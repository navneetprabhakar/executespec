package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.EmailTokenRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.RefreshTokenBlocklistRepository;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.referral.ReferralService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceLogoutRevocationTest {

  private AppUser user;
  private JwtUtil jwtUtil;
  private AuthService service;

  @BeforeEach
  void setUp() {
    AppUserRepository users = mock(AppUserRepository.class);
    RefreshTokenBlocklistRepository blocklist = mock(RefreshTokenBlocklistRepository.class);
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.getString("force_logout_epoch", null)).thenReturn(null);

    jwtUtil =
        new JwtUtil(
            "logout-revocation-test-secret-that-is-at-least-thirty-two-bytes",
            60,
            7,
            com.navneet.executespec.support.TestObjectProviders.of(flags));
    user =
        AppUser.builder()
            .id("user-1")
            .email("user@example.com")
            .firstName("Test")
            .globalRole("USER")
            .tier("FREE")
            .status("ACTIVE")
            .build();
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(blocklist.existsByJti(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

    service =
        new AuthService(
            users,
            mock(EmailTokenRepository.class),
            blocklist,
            mock(OrgMemberRepository.class),
            mock(PasswordEncoder.class),
            jwtUtil,
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
  void logoutRejectsPreviouslyIssuedAccessToken() {
    String accessToken = jwtUtil.generateAccessToken(user, null);
    String refreshToken = jwtUtil.generateRefreshToken(user);

    service.logout(refreshToken);

    assertThat(user.getTokenInvalidatedAt()).isNotNull();
    assertThat(jwtUtil.isAccessTokenValid(accessToken, user.getTokenInvalidatedAt())).isFalse();
  }

  @Test
  void logoutRejectsPreviouslyIssuedRefreshTokenEvenWithoutBlocklistLookupHit() {
    String refreshToken = jwtUtil.generateRefreshToken(user);

    service.logout(refreshToken);

    assertThatThrownBy(() -> service.refresh(refreshToken))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Refresh token revoked");
  }

  @Test
  void tokenMintedImmediatelyAfterLogoutIsAcceptedWhileOldTokensRemainRevoked() {
    for (int attempt = 0; attempt < 5; attempt++) {
      String oldAccess = jwtUtil.generateAccessToken(user, null);
      String oldRefresh = jwtUtil.generateRefreshToken(user);

      service.logout(oldRefresh);
      String newAccess = jwtUtil.generateAccessToken(user, null);
      String newRefresh = jwtUtil.generateRefreshToken(user);

      // Exercise the exact NumericDate collision: both standard iat claims name the same second.
      if (!jwtUtil
          .parseToken(oldAccess)
          .getIssuedAt()
          .equals(jwtUtil.parseToken(newAccess).getIssuedAt())) {
        continue;
      }
      assertThat(jwtUtil.isAccessTokenValid(oldAccess, user.getTokenInvalidatedAt())).isFalse();
      assertThat(jwtUtil.isRefreshTokenValid(oldRefresh, user.getTokenInvalidatedAt())).isFalse();
      assertThat(jwtUtil.isAccessTokenValid(newAccess, user.getTokenInvalidatedAt())).isTrue();
      assertThat(jwtUtil.isRefreshTokenValid(newRefresh, user.getTokenInvalidatedAt())).isTrue();
      return;
    }
    throw new AssertionError("Could not exercise same-second JWT issuance after five attempts");
  }
}
