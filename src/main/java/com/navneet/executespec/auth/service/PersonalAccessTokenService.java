package com.navneet.executespec.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.dto.CreatePatRequest;
import com.navneet.executespec.auth.entity.PersonalAccessToken;
import com.navneet.executespec.auth.repository.PersonalAccessTokenRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Issues and resolves Personal Access Tokens — the programmatic-auth credential for the CLI / VS
 * Code extension / CI. A PAT acts as the owning user: {@code JwtAuthenticationFilter} resolves it
 * to the same {@link com.navneet.executespec.auth.security.AppUserDetails} a JWT would produce.
 *
 * <p>Only the SHA-256 hash of the plaintext is stored, and it is the lookup key. The plaintext is
 * high-entropy random, so a fast digest is the right primitive (a salted password hash like bcrypt
 * would defeat the indexed lookup and buys nothing for a 256-bit random secret).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonalAccessTokenService {

  /**
   * Every PAT plaintext starts with this marker so the auth filter can route it without a DB hit.
   */
  public static final String TOKEN_PREFIX = "es_pat_";

  private static final int RANDOM_BYTES = 32;
  private static final int DISPLAY_PREFIX_LEN = 14; // "es_pat_" + 7 random chars
  private static final long LAST_USED_THROTTLE_SECONDS = 300; // 5 min

  private final PersonalAccessTokenRepository repo;
  private final SecureRandom secureRandom = new SecureRandom();

  /** Carries the one-time plaintext alongside the persisted row. The plaintext is never stored. */
  public record CreatedToken(String plaintext, PersonalAccessToken token) {}

  public CreatedToken create(String userId, CreatePatRequest req) {
    String name = req.name() == null ? "" : req.name().strip();
    if (name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token name is required");
    }
    Instant expiresAt = null;
    if (req.expiresInDays() != null) {
      if (req.expiresInDays() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresInDays must be positive");
      }
      expiresAt = Instant.now().plus(req.expiresInDays(), ChronoUnit.DAYS);
    }

    byte[] bytes = new byte[RANDOM_BYTES];
    secureRandom.nextBytes(bytes);
    String plaintext = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

    PersonalAccessToken token =
        PersonalAccessToken.builder()
            .id(UUID.randomUUID().toString())
            .userId(userId)
            .name(name)
            .tokenHash(DigestUtils.sha256Hex(plaintext))
            .tokenPrefix(plaintext.substring(0, DISPLAY_PREFIX_LEN))
            .scopes("full")
            .createdAt(Instant.now())
            .expiresAt(expiresAt)
            .build();
    repo.save(token);
    log.info("[PAT] Created token {} for user {} (expiresAt={})", token.getId(), userId, expiresAt);
    return new CreatedToken(plaintext, token);
  }

  public List<PersonalAccessToken> list(String userId) {
    return repo.findByUserIdOrderByCreatedAtDesc(userId);
  }

  public void revoke(String userId, String tokenId) {
    PersonalAccessToken token =
        repo.findByIdAndUserId(tokenId, userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Token not found"));
    if (token.getRevokedAt() == null) {
      token.setRevokedAt(Instant.now());
      repo.save(token);
      log.info("[PAT] Revoked token {} for user {}", tokenId, userId);
    }
  }

  /**
   * Resolve a presented PAT plaintext to its owning userId, or null if the token is unknown,
   * revoked (excluded by the query), or expired. Stamps {@code lastUsedAt}, throttled to ≤ once / 5
   * min to keep the per-request auth path off the write path.
   */
  public String resolveUserId(String plaintext) {
    if (plaintext == null || !plaintext.startsWith(TOKEN_PREFIX)) {
      return null;
    }
    PersonalAccessToken token =
        repo.findByTokenHashAndRevokedAtIsNull(DigestUtils.sha256Hex(plaintext)).orElse(null);
    if (token == null) {
      return null;
    }
    Instant now = Instant.now();
    if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(now)) {
      return null;
    }
    stampLastUsed(token, now);
    return token.getUserId();
  }

  private void stampLastUsed(PersonalAccessToken token, Instant now) {
    Instant last = token.getLastUsedAt();
    if (last != null && last.isAfter(now.minusSeconds(LAST_USED_THROTTLE_SECONDS))) {
      return; // throttled — avoid a DB write on every authenticated request
    }
    try {
      token.setLastUsedAt(now);
      repo.save(token);
    } catch (Exception e) {
      log.debug("[PAT] lastUsedAt stamp failed for {}: {}", token.getId(), e.getMessage());
    }
  }
}
