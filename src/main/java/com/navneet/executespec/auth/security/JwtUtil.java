package com.navneet.executespec.auth.security;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class JwtUtil {

  /**
   * Mandatory token-purpose discriminator (RR-128). Every JWT this class mints declares what it may
   * be used for; each consumer validates the exact purpose it accepts. Without it a refresh cookie
   * value or a 5-minute SSE query token — both designed to live in places an access token must
   * never live — replayed as {@code Authorization: Bearer} yielded a full-authority session.
   *
   * <p>Legacy tolerance: tokens minted before this claim existed carry no {@code token_use}. They
   * are accepted on the access path ONLY when they also carry neither {@code type} nor {@code
   * scope}, i.e. they are structurally access tokens. A legacy refresh/SSE token is still rejected.
   * Access tokens are short-lived, so this tolerance drains on its own.
   */
  public static final String TOKEN_USE_CLAIM = "token_use";

  static final String PRECISE_ISSUED_AT_CLAIM = "issued_at";

  public static final String TOKEN_USE_ACCESS = "access";
  public static final String TOKEN_USE_REFRESH = "refresh";
  public static final String TOKEN_USE_SSE = "sse";

  private final SecretKey secretKey;
  private final long accessTokenExpiryMinutes;
  private final long refreshTokenExpiryDays;

  /**
   * Resolved LAZILY at call time, never in the constructor. {@code JwtAuthenticationFilter} is a
   * {@link jakarta.servlet.Filter} and therefore a {@code ServletContextInitializer}, so Spring
   * instantiates it — and this bean — EARLY during embedded-web-server startup, before the shared
   * JPA {@code EntityManagerFactory} is ready. {@code FeatureFlagService}'s {@code @PostConstruct}
   * eagerly loads its cache via a Spring Data JPA repository, so injecting it directly dragged the
   * EntityManagerFactory into the filter's construction path and crash-looped {@code prod,gcp}
   * boots ({@code UnsatisfiedDependencyException} → {@code jpaSharedEM_entityManagerFactory} not
   * ready). An {@link ObjectProvider} is a lazy handle: constructing {@code JwtUtil} no longer
   * forces the feature-flag/JPA chain, and the flag is resolved on the request path where the full
   * context is up. Do NOT inject a JPA-backed bean directly here.
   */
  private final ObjectProvider<FeatureFlagService> featureFlagServiceProvider;

  public JwtUtil(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.access-token-expiry-minutes:15}") long accessTokenExpiryMinutes,
      @Value("${app.jwt.refresh-token-expiry-days:7}") long refreshTokenExpiryDays,
      ObjectProvider<FeatureFlagService> featureFlagServiceProvider) {
    // A shared profile with no JWT_SECRET supplied resolves to an empty string. JwtSecretValidator
    // is the policy gate, but it is a separate bean with no guaranteed ordering relative to this
    // constructor — so state the real cause here instead of surfacing an opaque key-length error
    // from the JJWT key factory.
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(
          "FATAL: app.jwt.secret is not configured. Supply JWT_SECRET from the secret store.");
    }
    this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
    this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    this.featureFlagServiceProvider = featureFlagServiceProvider;
  }

  public String generateAccessToken(AppUser user, OrgMember orgMember) {
    Instant now = Instant.now();
    Instant exp = now.plus(accessTokenExpiryMinutes, ChronoUnit.MINUTES);

    JwtBuilder builder =
        Jwts.builder()
            .subject(user.getId())
            .claim("email", user.getEmail())
            .claim("globalRole", user.getGlobalRole())
            .claim("tier", user.getTier())
            .claim(TOKEN_USE_CLAIM, TOKEN_USE_ACCESS)
            .claim(PRECISE_ISSUED_AT_CLAIM, now.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(secretKey);

    if (orgMember != null) {
      builder.claim("orgId", orgMember.getOrgId()).claim("orgRole", orgMember.getOrgRole());
    } else {
      builder.claim("orgId", (Object) null).claim("orgRole", (Object) null);
    }

    return builder.compact();
  }

  public String generateRefreshToken(AppUser user) {
    Instant now = Instant.now();
    Instant exp = now.plus(refreshTokenExpiryDays, ChronoUnit.DAYS);
    String jti = UUID.randomUUID().toString();

    return Jwts.builder()
        .subject(user.getId())
        .id(jti)
        .claim("type", "refresh")
        .claim(TOKEN_USE_CLAIM, TOKEN_USE_REFRESH)
        .claim(PRECISE_ISSUED_AT_CLAIM, now.toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .signWith(secretKey)
        .compact();
  }

  /**
   * Generates a short-lived SSE-scoped token for a specific run. Avoids exposing the full access
   * token in SSE query params (browser history, logs). 5-minute TTL, scoped to a single runId.
   */
  public String generateSseToken(String userId, String runId) {
    Instant now = Instant.now();
    Instant exp = now.plus(5, ChronoUnit.MINUTES);
    return Jwts.builder()
        .subject(userId)
        .claim("scope", "sse")
        .claim("runId", runId)
        .claim(TOKEN_USE_CLAIM, TOKEN_USE_SSE)
        .claim(PRECISE_ISSUED_AT_CLAIM, now.toString())
        .issuedAt(Date.from(now))
        .expiration(Date.from(exp))
        .signWith(secretKey)
        .compact();
  }

  /**
   * Validates an SSE token and checks it is scoped to the given runId. Returns the userId if valid,
   * null otherwise.
   */
  public String validateSseToken(String token, String runId) {
    try {
      Claims claims = parseToken(token);
      if (!"sse".equals(claims.get("scope", String.class))) return null;
      String tokenUse = claims.get(TOKEN_USE_CLAIM, String.class);
      if (tokenUse != null && !TOKEN_USE_SSE.equals(tokenUse)) return null;
      if (!runId.equals(claims.get("runId", String.class))) return null;
      return claims.getSubject();
    } catch (JwtException | IllegalArgumentException | DateTimeException e) {
      return null;
    }
  }

  public Claims parseToken(String token) {
    return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
  }

  public boolean isTokenValid(String token) {
    return isTokenValid(token, null);
  }

  /**
   * Validates a JWT token with optional per-user invalidation check.
   *
   * @param token the JWT to validate
   * @param userTokenInvalidatedAt if non-null, tokens issued before this instant are rejected
   *     (per-user session invalidation, complementing the global epoch)
   */
  public boolean isTokenValid(String token, Instant userTokenInvalidatedAt) {
    try {
      Claims claims = parseToken(token);
      Date issuedAt = claims.getIssuedAt();
      Instant preciseIssuedAt = preciseIssuedAt(claims, issuedAt);

      // Global force-logout check: reject tokens issued before the logout epoch. Resolved lazily —
      // see featureFlagServiceProvider's javadoc for why this must not be a constructor dependency.
      String epoch = featureFlagServiceProvider.getObject().getString("force_logout_epoch", null);
      if (epoch != null) {
        Instant logoutEpoch = Instant.parse(epoch);
        if (preciseIssuedAt != null && preciseIssuedAt.isBefore(logoutEpoch)) {
          log.debug(
              "JWT rejected: issued before force_logout_epoch (iat={}, epoch={})",
              preciseIssuedAt,
              logoutEpoch);
          return false;
        }
      }

      // Per-user invalidation check: reject tokens issued before the user's invalidation timestamp
      if (userTokenInvalidatedAt != null
          && preciseIssuedAt != null
          && preciseIssuedAt.isBefore(userTokenInvalidatedAt)) {
        log.debug(
            "JWT rejected: issued before per-user token_invalidated_at (iat={}, invalidatedAt={})",
            preciseIssuedAt,
            userTokenInvalidatedAt);
        return false;
      }

      return true;
    } catch (JwtException | IllegalArgumentException | DateTimeException e) {
      log.debug("JWT validation failed: {}", e.getMessage());
      return false;
    }
  }

  private Instant preciseIssuedAt(Claims claims, Date legacyIssuedAt) {
    String precise = claims.get(PRECISE_ISSUED_AT_CLAIM, String.class);
    if (precise != null) {
      return Instant.parse(precise);
    }
    return legacyIssuedAt == null ? null : legacyIssuedAt.toInstant();
  }

  /**
   * Access-path validator (RR-128). A token is only accepted as an API bearer when it declares
   * {@code token_use=access}, or when it is a legacy token carrying no purpose discriminator AND no
   * refresh/SSE marker. Refresh cookies and scoped SSE query tokens are rejected here.
   */
  public boolean isAccessTokenValid(String token, Instant userTokenInvalidatedAt) {
    if (!isTokenValid(token, userTokenInvalidatedAt)) return false;
    try {
      Claims claims = parseToken(token);
      String tokenUse = claims.get(TOKEN_USE_CLAIM, String.class);
      if (tokenUse != null) {
        return TOKEN_USE_ACCESS.equals(tokenUse);
      }
      // Legacy token: accept only if structurally an access token.
      return claims.get("type", String.class) == null && claims.get("scope", String.class) == null;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Refresh-rotation validator (RR-128). Requires {@code token_use=refresh}, tolerating legacy
   * tokens that carry only the older {@code type=refresh} marker. An access or SSE token presented
   * at the rotation endpoint is rejected.
   */
  public boolean isRefreshTokenValid(String token) {
    return isRefreshTokenValid(token, null);
  }

  /** Refresh validator with the same per-user session-epoch check used by access tokens. */
  public boolean isRefreshTokenValid(String token, Instant userTokenInvalidatedAt) {
    if (!isTokenValid(token, userTokenInvalidatedAt)) return false;
    try {
      Claims claims = parseToken(token);
      String tokenUse = claims.get(TOKEN_USE_CLAIM, String.class);
      if (tokenUse != null) {
        return TOKEN_USE_REFRESH.equals(tokenUse);
      }
      return "refresh".equals(claims.get("type", String.class));
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  public String extractUserId(String token) {
    return parseToken(token).getSubject();
  }

  public String extractJti(String token) {
    return parseToken(token).getId();
  }

  public Instant extractExpiry(String token) {
    return parseToken(token).getExpiration().toInstant();
  }

  public long getRefreshTokenExpiryDays() {
    return refreshTokenExpiryDays;
  }
}
