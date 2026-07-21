package com.navneet.executespec.auth.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.navneet.executespec.auth.dto.CreatePatRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.PersonalAccessToken;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.PersonalAccessTokenRepository;
import com.navneet.executespec.auth.security.PatScopePolicy;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

  /** Anti-abuse device-login cap — max concurrently-active PATs. FREE = 1, all other tiers = 3. */
  private static final int CAP_FREE = 1;

  private static final int CAP_OTHER = 3;

  /** Bounded default lifetime applied when a client omits {@code expiresInDays} (RR-1337). */
  public static final int DEFAULT_EXPIRY_DAYS = 90;

  /** Upper bound on any requested PAT lifetime. */
  public static final int MAX_EXPIRY_DAYS = 365;

  private final PersonalAccessTokenRepository repo;
  private final AppUserRepository appUserRepo;
  private final OrgRepository orgRepo;
  private final SecureRandom secureRandom = new SecureRandom();

  /** Carries the one-time plaintext alongside the persisted row. The plaintext is never stored. */
  public record CreatedToken(
      String plaintext, PersonalAccessToken token, List<String> autoRevokedTokenIds) {}

  /** Secret-free resolved identity and capabilities for the authentication filter. */
  public record ResolvedToken(String tokenId, String userId, String scopes) {}

  @Transactional
  public CreatedToken create(String userId, CreatePatRequest req) {
    String name = req.name() == null ? "" : req.name().strip();
    if (name.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token name is required");
    }
    // RR-1337 — a PAT is a device credential; it must always have a bounded lifetime. Callers that
    // omit expiresInDays (the web CLI/VS Code device-login mint did exactly this) previously got a
    // token that never expired, so a stolen device credential stayed valid until manual revocation.
    int expiresInDays = req.expiresInDays() == null ? DEFAULT_EXPIRY_DAYS : req.expiresInDays();
    if (expiresInDays <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expiresInDays must be positive");
    }
    if (expiresInDays > MAX_EXPIRY_DAYS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "expiresInDays must be at most " + MAX_EXPIRY_DAYS);
    }
    Instant expiresAt = Instant.now().plus(expiresInDays, ChronoUnit.DAYS);

    // Serialize the complete read/evict/insert sequence on the owning user row. Without this lock,
    // concurrent device logins can all observe the same active set and exceed the tier cap.
    AppUser lockedUser =
        appUserRepo
            .findByIdForUpdate(userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

    // Anti-abuse: cap concurrently-active device logins (FREE 1 / others 3) by auto-revoking the
    // least-recently-used active token(s) so the new one fits — login never fails.
    List<String> autoRevokedTokenIds = enforceDeviceCap(userId, lockedUser);

    String scopes;
    try {
      scopes = PatScopePolicy.normalizeRequested(req.scopes());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
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
            .scopes(scopes)
            .createdAt(Instant.now())
            .expiresAt(expiresAt)
            .build();
    repo.save(token);
    log.info("[PAT] Created token {} for user {} (expiresAt={})", token.getId(), userId, expiresAt);
    return new CreatedToken(plaintext, token, List.copyOf(autoRevokedTokenIds));
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
    ResolvedToken resolved = resolveToken(plaintext);
    return resolved == null ? null : resolved.userId();
  }

  /** Resolve PAT identity plus persisted capabilities without exposing the token hash/plaintext. */
  public ResolvedToken resolveToken(String plaintext) {
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
    return new ResolvedToken(token.getId(), token.getUserId(), token.getScopes());
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
      log.debug(
          "[PAT] lastUsedAt stamp failed for {} errorClass={}",
          token.getId(),
          e.getClass().getSimpleName());
    }
  }

  /**
   * Enforce the per-tier device-login cap by revoking the least-recently-used active token(s) until
   * a new one will fit. Login never fails — the newest device wins; an older device is signed out
   * (its next request 401s). FREE = 1 active token; every other tier = 3.
   */
  private List<String> enforceDeviceCap(String userId, AppUser lockedUser) {
    int cap = deviceLoginCap(effectiveTier(lockedUser));
    Instant now = Instant.now();
    List<PersonalAccessToken> active =
        repo.findByUserIdAndRevokedAtIsNull(userId).stream()
            .filter(t -> t.getExpiresAt() == null || t.getExpiresAt().isAfter(now))
            .sorted(Comparator.comparing(PersonalAccessTokenService::lastActiveAt))
            .collect(Collectors.toCollection(ArrayList::new));
    int evict = active.size() - (cap - 1); // leave room for the incoming token
    List<String> revokedIds = new ArrayList<>();
    for (int i = 0; i < evict; i++) {
      PersonalAccessToken old = active.get(i);
      old.setRevokedAt(now);
      repo.save(old);
      revokedIds.add(old.getId());
      log.info(
          "[PAT] device-login cap {} reached for user {} — auto-revoked LRU token {}",
          cap,
          userId,
          old.getId());
    }
    return revokedIds;
  }

  /** LRU ordering key: last use if present, else creation time. */
  private static Instant lastActiveAt(PersonalAccessToken t) {
    return t.getLastUsedAt() != null ? t.getLastUsedAt() : t.getCreatedAt();
  }

  private static int deviceLoginCap(String tier) {
    return "FREE".equalsIgnoreCase(tier) ? CAP_FREE : CAP_OTHER;
  }

  /**
   * Effective tier: the org's tier when the user belongs to one, else the user's own (default FREE)
   * — mirrors {@code TierEnforcementService}'s org-or-user resolution.
   */
  private String effectiveTier(AppUser user) {
    if (user.getOrgId() != null) {
      return orgRepo.findById(user.getOrgId()).map(Org::getTier).orElseGet(user::getTier);
    }
    return user.getTier() != null ? user.getTier() : "FREE";
  }
}
