package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * RR-128 — cross-token replay must be impossible.
 *
 * <p>Before this fix a JWT carried no purpose discriminator: {@code JwtAuthenticationFilter}
 * accepted any signed, unexpired token and rebuilt full authorities from the database. That meant a
 * refresh cookie value, and — far worse — a 5-minute SSE token deliberately designed to be safe in
 * URLs and logs, could be presented as {@code Authorization: Bearer} for full account access.
 */
class JwtTokenUseTest {

  private static final String SECRET = "test-secret-for-jwt-token-use-regression-0123456789";

  private JwtUtil jwtUtil;
  private SecretKey key;

  @BeforeEach
  void setUp() {
    FeatureFlagService flags = mock(FeatureFlagService.class);
    when(flags.getString(eq("force_logout_epoch"), any())).thenReturn(null);
    jwtUtil = new JwtUtil(SECRET, 15, 7, flags);
    key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  private AppUser user() {
    AppUser u = new AppUser();
    u.setId("user-1");
    u.setEmail("user@test.local");
    u.setGlobalRole("USER");
    u.setTier("PRO");
    return u;
  }

  @Test
  void accessTokenIsAcceptedOnTheAccessPath() {
    assertThat(jwtUtil.isAccessTokenValid(jwtUtil.generateAccessToken(user(), null), null))
        .isTrue();
  }

  @Test
  void refreshTokenIsRejectedAsBearer() {
    String refresh = jwtUtil.generateRefreshToken(user());
    assertThat(jwtUtil.isTokenValid(refresh)).as("still a structurally valid JWT").isTrue();
    assertThat(jwtUtil.isAccessTokenValid(refresh, null))
        .as("a refresh cookie value is not an API bearer")
        .isFalse();
  }

  @Test
  void sseTokenIsRejectedAsBearer() {
    String sse = jwtUtil.generateSseToken("user-1", "run-1");
    assertThat(jwtUtil.validateSseToken(sse, "run-1")).isEqualTo("user-1");
    assertThat(jwtUtil.isAccessTokenValid(sse, null))
        .as("a scoped SSE query token is not an API bearer")
        .isFalse();
  }

  @Test
  void accessAndSseTokensAreRejectedAtRefreshRotation() {
    assertThat(jwtUtil.isRefreshTokenValid(jwtUtil.generateAccessToken(user(), null))).isFalse();
    assertThat(jwtUtil.isRefreshTokenValid(jwtUtil.generateSseToken("user-1", "run-1"))).isFalse();
    assertThat(jwtUtil.isRefreshTokenValid(jwtUtil.generateRefreshToken(user()))).isTrue();
  }

  @Test
  void legacyAccessShapedTokenWithoutTokenUseStillAuthenticates() {
    Instant now = Instant.now();
    String legacy =
        Jwts.builder()
            .subject("user-1")
            .claim("email", "user@test.local")
            .claim("globalRole", "USER")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
            .signWith(key)
            .compact();

    assertThat(jwtUtil.isAccessTokenValid(legacy, null))
        .as("tokens minted before token_use existed keep working until they expire")
        .isTrue();
  }

  @Test
  void legacyRefreshShapedTokenWithoutTokenUseIsStillRejectedAsBearer() {
    Instant now = Instant.now();
    String legacyRefresh =
        Jwts.builder()
            .subject("user-1")
            .id("jti-1")
            .claim("type", "refresh")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(7, ChronoUnit.DAYS)))
            .signWith(key)
            .compact();

    assertThat(jwtUtil.isAccessTokenValid(legacyRefresh, null)).isFalse();
    assertThat(jwtUtil.isRefreshTokenValid(legacyRefresh))
        .as("the legacy type=refresh marker still rotates")
        .isTrue();
  }
}
