package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.EmailToken;
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
 * RR-127 / RR-1336 — the unauthenticated forgot-password endpoint must not be able to change
 * anyone's credentials.
 *
 * <p>Previously it generated a temporary password, overwrote the account hash, set
 * `mustChangePassword`, and emailed the plaintext — so anyone who knew an address could lock the
 * owner out at will, and (because it never minted the `PASSWORD_RESET` token the reset endpoint
 * looks up) the advertised reset link could never work.
 */
class AuthServicePasswordResetTest {

  private AppUserRepository userRepo;
  private EmailTokenRepository emailTokenRepo;
  private MailService mailService;
  private PasswordEncoder passwordEncoder;
  private AuthService svc;

  @BeforeEach
  void setUp() {
    userRepo = mock(AppUserRepository.class);
    emailTokenRepo = mock(EmailTokenRepository.class);
    mailService = mock(MailService.class);
    passwordEncoder = mock(PasswordEncoder.class);
    when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "hash:" + inv.getArgument(0));

    svc =
        new AuthService(
            userRepo,
            emailTokenRepo,
            mock(RefreshTokenBlocklistRepository.class),
            mock(OrgMemberRepository.class),
            passwordEncoder,
            mock(JwtUtil.class),
            mock(AuthenticationManager.class),
            mailService,
            mock(ActivityAuditService.class),
            mock(BillingPlanRepository.class),
            mock(BillingSubscriptionRepository.class),
            mock(EventTrackingService.class),
            mock(ReferralService.class),
            mock(OrgService.class),
            mock(TierEnforcementService.class));
  }

  private AppUser victim() {
    AppUser u = new AppUser();
    u.setId("victim-1");
    u.setEmail("victim@test.local");
    u.setFirstName("Victim");
    u.setPasswordHash("hash:OriginalPassw0rd!");
    when(userRepo.findByEmail("victim@test.local")).thenReturn(Optional.of(u));
    when(userRepo.findById("victim-1")).thenReturn(Optional.of(u));
    return u;
  }

  @Test
  void forgotPasswordDoesNotTouchTheAccountAndMailsALinkNotAPassword() {
    AppUser user = victim();

    svc.forgotPassword("victim@test.local");

    assertThat(user.getPasswordHash())
        .as("an unauthenticated request must never rotate a credential")
        .isEqualTo("hash:OriginalPassw0rd!");
    assertThat(user.isMustChangePassword()).isFalse();
    verify(userRepo, never()).save(any(AppUser.class));
    verify(mailService, never()).sendTemporaryPasswordEmail(anyString(), any(), anyString());
    verify(mailService).sendPasswordResetEmail(eq("victim@test.local"), eq("Victim"), anyString());
  }

  @Test
  void forgotPasswordPersistsOnlyAHashedSingleUseCapability() {
    victim();

    svc.forgotPassword("victim@test.local");

    ArgumentCaptor<EmailToken> saved = ArgumentCaptor.forClass(EmailToken.class);
    verify(emailTokenRepo).save(saved.capture());
    ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
    verify(mailService).sendPasswordResetEmail(anyString(), any(), mailed.capture());

    EmailToken token = saved.getValue();
    assertThat(token.getType()).isEqualTo(AuthService.PASSWORD_RESET_TOKEN_TYPE);
    assertThat(token.getUserId()).isEqualTo("victim-1");
    assertThat(token.getExpiresAt()).isAfter(Instant.now());
    assertThat(token.getToken())
        .as("only the digest reaches the database")
        .isNotEqualTo(mailed.getValue())
        .isEqualTo(AuthService.hashCapabilityToken(mailed.getValue()));

    // Reissuing supersedes any outstanding reset capability.
    verify(emailTokenRepo)
        .invalidateOutstanding(
            eq("victim-1"), eq(AuthService.PASSWORD_RESET_TOKEN_TYPE), any(Instant.class));
  }

  @Test
  void forgotPasswordForUnknownAddressIsASilentNoOp() {
    when(userRepo.findByEmail("nobody@test.local")).thenReturn(Optional.empty());

    svc.forgotPassword("nobody@test.local");

    verify(emailTokenRepo, never()).save(any(EmailToken.class));
    verify(mailService, never()).sendPasswordResetEmail(anyString(), any(), anyString());
  }

  @Test
  void resetConsumesTheCapabilityAtomicallyAndEndsExistingSessions() {
    AppUser user = victim();
    String plaintext = "capability-plaintext";
    EmailToken stored =
        EmailToken.builder()
            .id("tok-1")
            .userId("victim-1")
            .token(AuthService.hashCapabilityToken(plaintext))
            .type(AuthService.PASSWORD_RESET_TOKEN_TYPE)
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    when(emailTokenRepo.findByTokenAndType(
            AuthService.hashCapabilityToken(plaintext), AuthService.PASSWORD_RESET_TOKEN_TYPE))
        .thenReturn(Optional.of(stored));
    when(emailTokenRepo.claimIfUnused(eq("tok-1"), any(Instant.class))).thenReturn(1);

    svc.resetPassword(plaintext, "BrandNewPassw0rd!");

    assertThat(user.getPasswordHash()).isEqualTo("hash:BrandNewPassw0rd!");
    assertThat(user.getTokenInvalidatedAt())
        .as("a completed reset must invalidate sessions minted with the old credential")
        .isNotNull();
  }

  @Test
  void replayOfAnAlreadyClaimedCapabilityIsRefused() {
    AppUser user = victim();
    String plaintext = "capability-plaintext";
    EmailToken stored =
        EmailToken.builder()
            .id("tok-1")
            .userId("victim-1")
            .token(AuthService.hashCapabilityToken(plaintext))
            .type(AuthService.PASSWORD_RESET_TOKEN_TYPE)
            .expiresAt(Instant.now().plusSeconds(600))
            .build();
    when(emailTokenRepo.findByTokenAndType(
            AuthService.hashCapabilityToken(plaintext), AuthService.PASSWORD_RESET_TOKEN_TYPE))
        .thenReturn(Optional.of(stored));
    // The conditional claim already lost — another request consumed it, or it expired.
    when(emailTokenRepo.claimIfUnused(eq("tok-1"), any(Instant.class))).thenReturn(0);

    assertThatThrownBy(() -> svc.resetPassword(plaintext, "BrandNewPassw0rd!"))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(user.getPasswordHash()).isEqualTo("hash:OriginalPassw0rd!");
  }

  @Test
  void rawPlaintextTokenIsNotAValidDatabaseLookupKey() {
    victim();
    when(emailTokenRepo.findByTokenAndType(anyString(), anyString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> svc.resetPassword("not-a-real-token", "BrandNewPassw0rd!"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void resendVerificationPersistsOnlyAHashedTokenAndMailsThePlaintext() {
    // RR-132 — the EMAIL_VERIFICATION lane now stores a SHA-256 digest, like the reset lane, so a
    // read-only DB leak yields no usable activation capability.
    AppUser user = victim();
    user.setEmailVerified(false);

    svc.resendVerification("victim@test.local");

    ArgumentCaptor<EmailToken> saved = ArgumentCaptor.forClass(EmailToken.class);
    verify(emailTokenRepo).save(saved.capture());
    ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
    verify(mailService)
        .sendVerificationEmail(eq("victim@test.local"), eq("Victim"), mailed.capture());

    EmailToken token = saved.getValue();
    assertThat(token.getType()).isEqualTo("EMAIL_VERIFICATION");
    assertThat(token.getToken())
        .as("only the digest reaches the database")
        .isNotEqualTo(mailed.getValue())
        .isEqualTo(AuthService.hashCapabilityToken(mailed.getValue()));
  }

  @Test
  void verifyEmailLooksUpByHashNotRawToken() {
    // RR-132 — verification matches on the hash of the presented token.
    AppUser user = victim();
    user.setEmailVerified(false);
    user.setStatus("PENDING_VERIFICATION");
    String plaintext = "email-verify-plaintext";
    EmailToken stored =
        EmailToken.builder()
            .id("evk-1")
            .userId("victim-1")
            .token(AuthService.hashCapabilityToken(plaintext))
            .type("EMAIL_VERIFICATION")
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
    when(emailTokenRepo.findByTokenAndType(
            AuthService.hashCapabilityToken(plaintext), "EMAIL_VERIFICATION"))
        .thenReturn(Optional.of(stored));

    svc.verifyEmail(plaintext);

    assertThat(user.isEmailVerified()).isTrue();
    // The raw token is not a valid lookup key.
    verify(emailTokenRepo, never()).findByTokenAndType(eq(plaintext), anyString());
  }
}
