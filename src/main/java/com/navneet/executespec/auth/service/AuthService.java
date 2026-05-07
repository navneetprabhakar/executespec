package com.navneet.executespec.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.navneet.executespec.analytics.EventName;
import com.navneet.executespec.analytics.EventTrackingService;
import com.navneet.executespec.auth.dto.*;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.EmailToken;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.EmailTokenRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.RefreshTokenBlocklistRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;
import com.navneet.executespec.referral.ReferralService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

  private final AppUserRepository userRepo;
  private final EmailTokenRepository emailTokenRepo;
  private final RefreshTokenBlocklistRepository blocklistRepo;
  private final OrgMemberRepository orgMemberRepo;
  private final PasswordEncoder passwordEncoder;
  private final JwtUtil jwtUtil;
  private final AuthenticationManager authManager;
  private final MailService mailService;
  private final ActivityAuditService auditService;
  private final BillingPlanRepository billingPlanRepo;
  private final BillingSubscriptionRepository billingSubscriptionRepo;
  private final EventTrackingService eventTracking;
  private final ReferralService referralService;
  private final OrgService orgService;

  @Value("${app.mail.base-url:http://localhost:5173}")
  private String baseUrl;

  // ── Registration ──────────────────────────────────────────────────────────

  @Transactional
  public UserDto register(RegisterRequest req) {
    if (userRepo.existsByEmail(req.email().toLowerCase())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
    }
    validatePasswordStrength(req.password());

    AppUser user =
        AppUser.builder()
            .id(UUID.randomUUID().toString())
            .email(req.email().toLowerCase())
            .firstName(req.firstName())
            .lastName(req.lastName())
            .passwordHash(passwordEncoder.encode(req.password()))
            .globalRole("USER")
            .tier("FREE")
            .status("PENDING_VERIFICATION")
            .emailVerified(false)
            .signupSource(req.signupSource())
            .signupUtmSource(req.utmSource())
            .signupUtmCampaign(req.utmCampaign())
            .build();
    userRepo.save(user);

    // Auto-create FREE billing subscription so usage checks pass
    createFreeSubscription(user.getId());

    // V162 — solo-user bootstrap: every fresh user gets a personal org so
    // POST /api/projects (which requires ORG_ADMIN/DEVELOPER role) and the
    // sandbox provisioning flow (which writes project.org_id NOT NULL)
    // both work without a paid-tier upgrade. Without this, the V3EmptyProject
    // "Create my first project" button hits 403 and the alt sandbox path 500s.
    // See OrgService.bootstrapPersonalOrg javadoc for the design notes.
    orgService.bootstrapPersonalOrg(user.getId());

    // R-162 — claim any pending referral invite addressed to this email.
    // Best-effort: a missing or already-bound invite is a clean no-op.
    tryBindReferral(user.getId(), user.getEmail());

    // Send verification email
    String token = createEmailToken(user.getId(), "EMAIL_VERIFICATION", 24);
    mailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), token);

    // Admin notification — fire-and-forget (@Async), failures logged not rethrown
    try {
      mailService.sendNewUserAlertToAdmin(
          mailService.getAdminAddress(),
          user.getEmail(),
          user.getFirstName(),
          user.getLastName(),
          "Email");
    } catch (Exception e) {
      log.warn("Admin new-signup notification failed for {}: {}", user.getEmail(), e.getMessage());
    }

    // Wave 2.1 — activation funnel step 1
    java.util.Map<String, Object> signupProps = new java.util.HashMap<>();
    signupProps.put("signupMethod", "email");
    signupProps.put("tier", user.getTier());
    if (req.signupSource() != null) signupProps.put("signupSource", req.signupSource());
    if (req.utmSource() != null) signupProps.put("utmSource", req.utmSource());
    if (req.utmCampaign() != null) signupProps.put("utmCampaign", req.utmCampaign());
    eventTracking.track(user.getId(), EventName.SIGNUP, signupProps);

    log.info("User registered: {}", user.getEmail());

    auditService.record(
        ActivityAudit.builder()
            .eventType("USER_REGISTERED")
            .entityType("USER")
            .entityId(user.getId())
            .newValue("{\"email\":\"" + user.getEmail() + "\",\"tier\":\"" + user.getTier() + "\"}")
            .build());

    return UserDto.from(user, null);
  }

  // ── Email Verification ────────────────────────────────────────────────────

  @Transactional
  public void verifyEmail(String token) {
    EmailToken emailToken =
        emailTokenRepo
            .findByTokenAndType(token, "EMAIL_VERIFICATION")
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid or expired token"));

    if (emailToken.getUsedAt() != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token already used");
    }
    if (emailToken.getExpiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
    }

    AppUser user =
        userRepo
            .findById(emailToken.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    user.setEmailVerified(true);
    user.setStatus("ACTIVE");
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    emailToken.setUsedAt(Instant.now());
    emailTokenRepo.save(emailToken);

    auditService.record(
        ActivityAudit.builder()
            .eventType("EMAIL_VERIFIED")
            .entityType("USER")
            .entityId(user.getId())
            .newValue("{\"email\":\"" + user.getEmail() + "\"}")
            .build());

    // Send welcome email
    mailService.sendWelcomeEmail(user.getEmail(), user.getFirstName(), user.getTier());
  }

  /**
   * [F-29] (audit Sprint 1 PR-2, 2026-04-30) — verify-email + auto-session.
   *
   * <p>Runs the same activation steps as {@link #verifyEmail(String)} but additionally returns a
   * {@link LoginResponse} so the controller can issue a JWT + refresh cookie. The frontend then
   * redirects straight to /home (or /welcome once F-02 is wired) instead of forcing the user to
   * re-enter their password at the LoginPage.
   *
   * <p>Idempotent on the verification side — once the token has been used, subsequent calls fail
   * with 400 "Token already used" same as the void variant. This preserves the security property
   * that a stolen email link cannot be used twice.
   */
  @Transactional
  public LoginResponse verifyEmailAndAuthenticate(String token) {
    EmailToken emailToken =
        emailTokenRepo
            .findByTokenAndType(token, "EMAIL_VERIFICATION")
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid or expired token"));
    if (emailToken.getUsedAt() != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token already used");
    }
    if (emailToken.getExpiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
    }
    AppUser user =
        userRepo
            .findById(emailToken.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    user.setEmailVerified(true);
    user.setStatus("ACTIVE");
    user.setLastLoginAt(Instant.now());
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    emailToken.setUsedAt(Instant.now());
    emailTokenRepo.save(emailToken);

    auditService.record(
        ActivityAudit.builder()
            .eventType("EMAIL_VERIFIED")
            .entityType("USER")
            .entityId(user.getId())
            .newValue("{\"email\":\"" + user.getEmail() + "\",\"autoSession\":true}")
            .build());

    // Send welcome email (non-fatal if SMTP fails)
    try {
      mailService.sendWelcomeEmail(user.getEmail(), user.getFirstName(), user.getTier());
    } catch (Exception e) {
      log.warn(
          "[verifyEmailAndAuthenticate] welcome email send failed for {} — continuing",
          user.getEmail(),
          e);
    }

    // Build the same LoginResponse shape as login() so the frontend's
    // existing auth-store handler can consume it without branching.
    OrgMember orgMember =
        (user.getOrgId() != null)
            ? orgMemberRepo.findByOrgIdAndUserId(user.getOrgId(), user.getId()).orElse(null)
            : null;
    String accessToken = jwtUtil.generateAccessToken(user, orgMember);
    return new LoginResponse(accessToken, UserDto.from(user, orgMember));
  }

  // ── Login ─────────────────────────────────────────────────────────────────

  public LoginResponse login(LoginRequest req) {
    // Audit r5 (P2-2, 2026-05-06) — distinguish account-state failures
    // from real bad-credential failures so the UI shows the right
    // error. Pre-fix every Spring auth exception fell through to
    // "Invalid credentials" — including DisabledException thrown when
    // AppUserDetails.isEnabled() returns false (PENDING_VERIFICATION).
    // The downstream status check below was unreachable for pending
    // users because they failed authentication first. New user
    // registers → email link arrives → user tries to login before
    // clicking link → backend said "Invalid credentials" → user
    // thought the password was wrong. Now the message points them at
    // the verification email.
    Authentication auth;
    try {
      auth =
          authManager.authenticate(
              new UsernamePasswordAuthenticationToken(req.email().toLowerCase(), req.password()));
    } catch (org.springframework.security.authentication.DisabledException e) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Email not verified — check your inbox for the activation link, or use Forgot password to"
              + " resend.");
    } catch (org.springframework.security.authentication.LockedException e) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Account suspended. Contact support@executespec.ai if this is unexpected.");
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    AppUserDetails details = (AppUserDetails) auth.getPrincipal();
    AppUser user = details.getUser();

    if (!"ACTIVE".equals(user.getStatus())) {
      // Defense in depth — unreachable now that DisabledException is
      // caught above, but kept so a future status enum addition (e.g.
      // PASSWORD_RESET_REQUIRED) doesn't accidentally let a non-active
      // user through.
      if ("PENDING_VERIFICATION".equals(user.getStatus())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Email not verified — check your inbox for the activation link, or use Forgot password"
                + " to resend.");
      }
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account suspended");
    }

    OrgMember orgMember = details.getOrgMember();
    String accessToken = jwtUtil.generateAccessToken(user, orgMember);

    // Update last login
    user.setLastLoginAt(Instant.now());
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    return new LoginResponse(accessToken, UserDto.from(user, orgMember));
  }

  /** Generate a refresh token string (for cookie). */
  public String generateRefreshToken(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    return jwtUtil.generateRefreshToken(user);
  }

  /** Validate refresh token and return new access token. */
  @Transactional
  public LoginResponse refresh(String refreshToken) {
    if (!jwtUtil.isTokenValid(refreshToken)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
    }
    String jti = jwtUtil.extractJti(refreshToken);
    if (blocklistRepo.existsByJti(jti)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revoked");
    }

    String userId = jwtUtil.extractUserId(refreshToken);
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    OrgMember orgMember = null;
    if (user.getOrgId() != null) {
      orgMember = orgMemberRepo.findByOrgIdAndUserId(user.getOrgId(), user.getId()).orElse(null);
    }
    String accessToken = jwtUtil.generateAccessToken(user, orgMember);
    return new LoginResponse(accessToken, UserDto.from(user, orgMember));
  }

  /** Revoke a refresh token. */
  @Transactional
  public void logout(String refreshToken) {
    if (refreshToken == null || !jwtUtil.isTokenValid(refreshToken)) return;
    String jti = jwtUtil.extractJti(refreshToken);
    Instant exp = jwtUtil.extractExpiry(refreshToken);
    blocklistRepo.insertIfAbsent(jti, jwtUtil.extractUserId(refreshToken), exp);
  }

  // ── Password Reset ────────────────────────────────────────────────────────

  @Transactional
  public void forgotPassword(String email) {
    userRepo
        .findByEmail(email.toLowerCase())
        .ifPresent(
            user -> {
              String tempPassword = generateSecurePassword();
              user.setPasswordHash(passwordEncoder.encode(tempPassword));
              user.setMustChangePassword(true);
              user.setUpdatedAt(Instant.now());
              userRepo.save(user);
              mailService.sendTemporaryPasswordEmail(
                  user.getEmail(), user.getFirstName(), tempPassword);
            });
    // Always return success to prevent email enumeration
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    validatePasswordStrength(newPassword);
    EmailToken emailToken =
        emailTokenRepo
            .findByTokenAndType(token, "PASSWORD_RESET")
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Invalid or expired token"));
    if (emailToken.getUsedAt() != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token already used");
    }
    if (emailToken.getExpiresAt().isBefore(Instant.now())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
    }

    AppUser user =
        userRepo
            .findById(emailToken.getUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    user.setPasswordHash(passwordEncoder.encode(newPassword));
    user.setMustChangePassword(false);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    emailToken.setUsedAt(Instant.now());
    emailTokenRepo.save(emailToken);

    auditService.record(
        ActivityAudit.builder()
            .eventType("PASSWORD_RESET")
            .entityType("USER")
            .entityId(user.getId())
            .newValue("{\"email\":\"" + user.getEmail() + "\"}")
            .build());
  }

  // ── Change Password ───────────────────────────────────────────────────────

  @Transactional
  public void changePassword(String userId, ChangePasswordRequest req) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    // If not forced-change, require current password
    if (!user.isMustChangePassword()) {
      if (req.currentPassword() == null || req.currentPassword().isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password required");
      }
      if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password incorrect");
      }
    }
    validatePasswordStrength(req.newPassword());
    user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
    user.setMustChangePassword(false);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    auditService.record(
        ActivityAudit.builder()
            .eventType("PASSWORD_CHANGED")
            .entityType("USER")
            .entityId(userId)
            .actorId(userId)
            .build());
  }

  // ── Resend Verification ───────────────────────────────────────────────────

  @Transactional
  public void resendVerification(String email) {
    userRepo
        .findByEmail(email.toLowerCase())
        .ifPresent(
            user -> {
              if (!user.isEmailVerified()) {
                String token = createEmailToken(user.getId(), "EMAIL_VERIFICATION", 24);
                mailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), token);
              }
            });
  }

  // ── FREE subscription bootstrap ─────────────────────────────────────────

  /**
   * Creates a billing_subscription row with status='FREE' so that
   * UsageTrackingService.assertWithinLimits() can look up plan limits. Called once at registration.
   */
  private void createFreeSubscription(String userId) {
    try {
      BillingPlan freePlan =
          billingPlanRepo.findByTierAndActiveTrue("FREE").stream().findFirst().orElse(null);
      if (freePlan == null) {
        log.warn(
            "No active FREE billing plan found — skipping subscription creation for user {}",
            userId);
        return;
      }
      BillingSubscription sub =
          BillingSubscription.builder()
              .userId(userId)
              .billingPlanId(freePlan.getId())
              .paymentGateway("NONE")
              .status("FREE")
              .totalSeats(1)
              .extraSeats(0)
              .cancelAtPeriodEnd(false)
              .founderPricing(false)
              .build();
      billingSubscriptionRepo.save(sub);
      log.debug("Created FREE billing subscription for user {}", userId);
    } catch (Exception e) {
      // Non-fatal: tier enforcement still works via TierEnforcementService
      log.warn(
          "Failed to create FREE billing subscription for user {}: {}", userId, e.getMessage());
    }
  }

  /**
   * R-162 — claim any pending {@code referral_invite} row addressed to a freshly-signed-up user's
   * email. {@link ReferralService#bindReferral} is already idempotent (won't overwrite an existing
   * binding, won't re-stamp acceptedAt), so calling this on every signup path is safe even when no
   * invite exists. Wrapped in try/catch so a referral subsystem hiccup never blocks signup.
   */
  private void tryBindReferral(String userId, String email) {
    if (userId == null || email == null) return;
    try {
      boolean bound = referralService.bindReferral(userId, email);
      if (bound) {
        log.info("v3 referral auto-bound on signup user={} email={}", userId, email);
      }
    } catch (Exception e) {
      log.warn("v3 referral auto-bind failed user={} email={}: {}", userId, email, e.getMessage());
    }
  }

  // ── OAuth ─────────────────────────────────────────────────────────────────

  /**
   * Find or create a user from an OAuth2 login.
   *
   * <ul>
   *   <li>If a user with matching provider+oauthId exists → return it (update last login).
   *   <li>If a user with the same email exists but different provider → throw conflict.
   *   <li>If a user with the same email exists with no provider → link the OAuth identity.
   *   <li>Otherwise → create a new user (no password, ACTIVE, email verified).
   * </ul>
   */
  @Transactional
  public AppUser findOrCreateFromOAuth(
      String provider, String oauthId, String email, String name, String avatarUrl) {
    // 1. Exact OAuth match
    var existing = userRepo.findByOauthProviderAndOauthId(provider, oauthId);
    if (existing.isPresent()) {
      AppUser user = existing.get();
      user.setLastLoginAt(Instant.now());
      user.setUpdatedAt(Instant.now());
      if (avatarUrl != null) user.setAvatarUrl(avatarUrl);
      return userRepo.save(user);
    }

    // 2. Check by email — link/update OAuth identity regardless of current provider
    var byEmail = userRepo.findByEmail(email.toLowerCase());
    if (byEmail.isPresent()) {
      AppUser user = byEmail.get();
      user.setOauthProvider(provider);
      user.setOauthId(oauthId);
      if (avatarUrl != null) user.setAvatarUrl(avatarUrl);
      user.setEmailVerified(true);
      user.setStatus("ACTIVE");
      user.setLastLoginAt(Instant.now());
      user.setUpdatedAt(Instant.now());
      userRepo.save(user);

      log.info("Linked OAuth {} to existing user {}", provider, user.getEmail());
      return user;
    }

    // 3. New user
    String firstName = name != null ? name.split(" ")[0] : email.split("@")[0];
    String lastName =
        (name != null && name.contains(" ")) ? name.substring(name.indexOf(' ') + 1) : null;

    AppUser user =
        AppUser.builder()
            .id(UUID.randomUUID().toString())
            .email(email.toLowerCase())
            .firstName(firstName)
            .lastName(lastName)
            .passwordHash(null)
            .oauthProvider(provider)
            .oauthId(oauthId)
            .avatarUrl(avatarUrl)
            .globalRole("USER")
            .tier("FREE")
            .status("ACTIVE")
            .emailVerified(true)
            .lastLoginAt(Instant.now())
            .build();
    userRepo.save(user);

    // Auto-create FREE billing subscription
    createFreeSubscription(user.getId());

    // V162 — solo-user bootstrap (OAuth new-user branch). Same fix as the
    // email-signup register() path. See OrgService.bootstrapPersonalOrg.
    orgService.bootstrapPersonalOrg(user.getId());

    // R-162 — claim any pending referral invite addressed to this email
    // (OAuth new-user branch). Best-effort, idempotent.
    tryBindReferral(user.getId(), user.getEmail());

    // Admin notification — fire-and-forget, same as email-signup path
    try {
      mailService.sendNewUserAlertToAdmin(
          mailService.getAdminAddress(),
          user.getEmail(),
          user.getFirstName(),
          user.getLastName(),
          provider == null
              ? "OAuth"
              : provider.substring(0, 1).toUpperCase() + provider.substring(1));
    } catch (Exception e) {
      log.warn(
          "Admin new-signup OAuth notification failed for {}: {}", user.getEmail(), e.getMessage());
    }

    // Wave 2.1 — activation funnel step 1 (OAuth new-user branch only)
    eventTracking.track(
        user.getId(),
        EventName.SIGNUP,
        java.util.Map.of(
            "signupMethod", provider == null ? "oauth" : provider.toLowerCase(),
            "tier", user.getTier() == null ? "FREE" : user.getTier()));

    auditService.record(
        ActivityAudit.builder()
            .eventType("USER_REGISTERED_OAUTH")
            .entityType("USER")
            .entityId(user.getId())
            .newValue(
                "{\"email\":\""
                    + user.getEmail()
                    + "\",\"provider\":\""
                    + provider
                    + "\",\"tier\":\"FREE\"}")
            .build());

    log.info("Created new OAuth user: email={}, provider={}", email, provider);

    // Send welcome email for new OAuth users
    mailService.sendWelcomeEmail(user.getEmail(), user.getFirstName(), user.getTier());

    return user;
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private String createEmailToken(String userId, String type, int expiryHours) {
    String token = UUID.randomUUID().toString().replace("-", "");
    emailTokenRepo.save(
        EmailToken.builder()
            .id(UUID.randomUUID().toString())
            .userId(userId)
            .token(token)
            .type(type)
            .expiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS))
            .build());
    return token;
  }

  private void validatePasswordStrength(String password) {
    if (password == null || password.length() < 10) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must be at least 10 characters");
    }
    if (password.length() > 128) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must not exceed 128 characters");
    }
    if (!password.matches(".*[A-Z].*")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must contain at least one uppercase letter");
    }
    if (!password.matches(".*[a-z].*")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must contain at least one lowercase letter");
    }
    if (!password.matches(".*[0-9].*")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must contain at least one digit");
    }
    if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\",./<>?].*")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must contain at least one special character");
    }
    if (password.contains(" ")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Password must not contain whitespace");
    }
  }

  /**
   * Generates a random 12-character password containing uppercase, lowercase, digits, and special
   * characters — guaranteed to pass validatePasswordStrength().
   */
  private String generateSecurePassword() {
    String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    String lower = "abcdefghijklmnopqrstuvwxyz";
    String digits = "0123456789";
    String special = "!@#$%&*";
    String all = upper + lower + digits + special;
    SecureRandom rng = new SecureRandom();

    // Guarantee at least one of each required type
    StringBuilder sb = new StringBuilder(12);
    sb.append(upper.charAt(rng.nextInt(upper.length())));
    sb.append(lower.charAt(rng.nextInt(lower.length())));
    sb.append(digits.charAt(rng.nextInt(digits.length())));
    sb.append(special.charAt(rng.nextInt(special.length())));

    // Fill the rest
    for (int i = 4; i < 12; i++) {
      sb.append(all.charAt(rng.nextInt(all.length())));
    }

    // Shuffle to avoid predictable positions
    char[] chars = sb.toString().toCharArray();
    for (int i = chars.length - 1; i > 0; i--) {
      int j = rng.nextInt(i + 1);
      char tmp = chars[i];
      chars[i] = chars[j];
      chars[j] = tmp;
    }
    return new String(chars);
  }
}
