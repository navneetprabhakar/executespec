package com.navneet.executespec.auth.security;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
public class JwtUtil {

    private final SecretKey secretKey;
    private final long accessTokenExpiryMinutes;
    private final long refreshTokenExpiryDays;
    private final FeatureFlagService featureFlagService;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry-minutes:15}") long accessTokenExpiryMinutes,
            @Value("${app.jwt.refresh-token-expiry-days:7}") long refreshTokenExpiryDays,
            FeatureFlagService featureFlagService) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
        this.featureFlagService = featureFlagService;
    }

    public String generateAccessToken(AppUser user, OrgMember orgMember) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessTokenExpiryMinutes, ChronoUnit.MINUTES);

        JwtBuilder builder = Jwts.builder()
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("globalRole", user.getGlobalRole())
                .claim("tier", user.getTier())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey);

        if (orgMember != null) {
            builder.claim("orgId", orgMember.getOrgId())
                   .claim("orgRole", orgMember.getOrgRole());
        } else {
            builder.claim("orgId", (Object) null)
                   .claim("orgRole", (Object) null);
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
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Generates a short-lived SSE-scoped token for a specific run.
     * Avoids exposing the full access token in SSE query params (browser history, logs).
     * 5-minute TTL, scoped to a single runId.
     */
    public String generateSseToken(String userId, String runId) {
        Instant now = Instant.now();
        Instant exp = now.plus(5, ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(userId)
                .claim("scope", "sse")
                .claim("runId", runId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validates an SSE token and checks it is scoped to the given runId.
     * Returns the userId if valid, null otherwise.
     */
    public String validateSseToken(String token, String runId) {
        try {
            Claims claims = parseToken(token);
            if (!"sse".equals(claims.get("scope", String.class))) return null;
            if (!runId.equals(claims.get("runId", String.class))) return null;
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        return isTokenValid(token, null);
    }

    /**
     * Validates a JWT token with optional per-user invalidation check.
     *
     * @param token                  the JWT to validate
     * @param userTokenInvalidatedAt if non-null, tokens issued before this instant are rejected
     *                               (per-user session invalidation, complementing the global epoch)
     */
    public boolean isTokenValid(String token, Instant userTokenInvalidatedAt) {
        try {
            Claims claims = parseToken(token);
            Date issuedAt = claims.getIssuedAt();

            // Global force-logout check: reject tokens issued before the logout epoch
            String epoch = featureFlagService.getString("force_logout_epoch", null);
            if (epoch != null) {
                Instant logoutEpoch = Instant.parse(epoch);
                if (issuedAt != null && issuedAt.toInstant().isBefore(logoutEpoch)) {
                    log.debug("JWT rejected: issued before force_logout_epoch (iat={}, epoch={})",
                            issuedAt.toInstant(), logoutEpoch);
                    return false;
                }
            }

            // Per-user invalidation check: reject tokens issued before the user's invalidation timestamp
            if (userTokenInvalidatedAt != null && issuedAt != null
                    && issuedAt.toInstant().isBefore(userTokenInvalidatedAt)) {
                log.debug("JWT rejected: issued before per-user token_invalidated_at (iat={}, invalidatedAt={})",
                        issuedAt.toInstant(), userTokenInvalidatedAt);
                return false;
            }

            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
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
