package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navneet.executespec.auth.dto.LoginRequest;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.EmailTokenRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.RefreshTokenBlocklistRepository;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.referral.ReferralService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * P1-5 (2026-08-01): an infrastructure failure while LOADING the account — e.g. an encrypted
 * credential column that no longer decrypts — must not masquerade as a wrong password. The live
 * repro: a user whose stored GitHub PAT envelope could not decrypt was told "Invalid credentials"
 * although their password hash was byte-identical to a working account's.
 */
class AuthServiceLoginInfrastructureFailureTest {

  private AuthService serviceWith(AuthenticationManager authManager) {
    return new AuthService(
        mock(AppUserRepository.class),
        mock(EmailTokenRepository.class),
        mock(RefreshTokenBlocklistRepository.class),
        mock(OrgMemberRepository.class),
        mock(PasswordEncoder.class),
        mock(JwtUtil.class),
        authManager,
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
  void accountLoadInfrastructureFailureIsNotReportedAsInvalidCredentials() {
    AuthenticationManager authManager = mock(AuthenticationManager.class);
    when(authManager.authenticate(any()))
        .thenThrow(
            new InternalAuthenticationServiceException(
                "Encrypted value cannot be decrypted", new IllegalStateException("enc:v1 fail")));

    assertThatThrownBy(
            () -> serviceWith(authManager).login(new LoginRequest("user@example.com", "pw")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.getStatusCode())
                  .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              org.assertj.core.api.Assertions.assertThat(e.getReason())
                  .doesNotContain("Invalid credentials")
                  .contains("NOT");
            });
  }

  @Test
  void realBadCredentialsRemainA401InvalidCredentials() {
    AuthenticationManager authManager = mock(AuthenticationManager.class);
    when(authManager.authenticate(any())).thenThrow(new BadCredentialsException("bad password"));

    assertThatThrownBy(
            () -> serviceWith(authManager).login(new LoginRequest("user@example.com", "pw")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              org.assertj.core.api.Assertions.assertThat(e.getStatusCode())
                  .isEqualTo(HttpStatus.UNAUTHORIZED);
              org.assertj.core.api.Assertions.assertThat(e.getReason())
                  .isEqualTo("Invalid credentials");
            });
  }
}
