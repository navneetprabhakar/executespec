package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.dto.*;
import com.navneet.executespec.auth.entity.*;
import com.navneet.executespec.auth.repository.*;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

    @Value("${app.mail.base-url:http://localhost:5173}")
    private String baseUrl;

    // ── Registration ──────────────────────────────────────────────────────────

    @Transactional
    public UserDto register(RegisterRequest req) {
        if (userRepo.existsByEmail(req.email().toLowerCase())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        validatePasswordStrength(req.password());

        AppUser user = AppUser.builder()
                .id(UUID.randomUUID().toString())
                .email(req.email().toLowerCase())
                .firstName(req.firstName())
                .lastName(req.lastName())
                .passwordHash(passwordEncoder.encode(req.password()))
                .globalRole("USER")
                .tier("FREE")
                .status("PENDING_VERIFICATION")
                .emailVerified(false)
                .build();
        userRepo.save(user);

        // Send verification email
        String token = createEmailToken(user.getId(), "EMAIL_VERIFICATION", 24);
        mailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), token);
        log.info("User registered: {}", user.getEmail());

        return UserDto.from(user, null);
    }

    // ── Email Verification ────────────────────────────────────────────────────

    @Transactional
    public void verifyEmail(String token) {
        EmailToken emailToken = emailTokenRepo.findByTokenAndType(token, "EMAIL_VERIFICATION")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token"));

        if (emailToken.getUsedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token already used");
        }
        if (emailToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
        }

        AppUser user = userRepo.findById(emailToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        user.setEmailVerified(true);
        user.setStatus("ACTIVE");
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);

        emailToken.setUsedAt(Instant.now());
        emailTokenRepo.save(emailToken);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    public LoginResponse login(LoginRequest req) {
        Authentication auth;
        try {
            auth = authManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.email().toLowerCase(), req.password()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        AppUserDetails details = (AppUserDetails) auth.getPrincipal();
        AppUser user = details.getUser();

        if (!"ACTIVE".equals(user.getStatus())) {
            if ("PENDING_VERIFICATION".equals(user.getStatus())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Email not verified");
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
        AppUser user = userRepo.findById(userId)
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
        AppUser user = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
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
        if (!blocklistRepo.existsByJti(jti)) {
            blocklistRepo.save(RefreshTokenBlocklist.builder()
                    .jti(jti)
                    .userId(jwtUtil.extractUserId(refreshToken))
                    .expiresAt(exp)
                    .build());
        }
    }

    // ── Password Reset ────────────────────────────────────────────────────────

    @Transactional
    public void forgotPassword(String email) {
        userRepo.findByEmail(email.toLowerCase()).ifPresent(user -> {
            String token = createEmailToken(user.getId(), "PASSWORD_RESET", 1); // 1 hour
            mailService.sendPasswordResetEmail(user.getEmail(), user.getFirstName(), token);
        });
        // Always return success to prevent email enumeration
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        validatePasswordStrength(newPassword);
        EmailToken emailToken = emailTokenRepo.findByTokenAndType(token, "PASSWORD_RESET")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired token"));
        if (emailToken.getUsedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token already used");
        }
        if (emailToken.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
        }

        AppUser user = userRepo.findById(emailToken.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setUpdatedAt(Instant.now());
        userRepo.save(user);

        emailToken.setUsedAt(Instant.now());
        emailTokenRepo.save(emailToken);
    }

    // ── Change Password ───────────────────────────────────────────────────────

    @Transactional
    public void changePassword(String userId, ChangePasswordRequest req) {
        AppUser user = userRepo.findById(userId)
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
    }

    // ── Resend Verification ───────────────────────────────────────────────────

    @Transactional
    public void resendVerification(String email) {
        userRepo.findByEmail(email.toLowerCase()).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                String token = createEmailToken(user.getId(), "EMAIL_VERIFICATION", 24);
                mailService.sendVerificationEmail(user.getEmail(), user.getFirstName(), token);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createEmailToken(String userId, String type, int expiryHours) {
        String token = UUID.randomUUID().toString().replace("-", "");
        emailTokenRepo.save(EmailToken.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .token(token)
                .type(type)
                .expiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS))
                .build());
        return token;
    }

    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be at least 8 characters");
        }
        if (!password.matches(".*[A-Z].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain at least one uppercase letter");
        }
        if (!password.matches(".*[0-9].*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must contain at least one digit");
        }
        if (password.contains(" ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must not contain whitespace");
        }
    }
}
