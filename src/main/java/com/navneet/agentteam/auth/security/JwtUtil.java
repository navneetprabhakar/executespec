package com.navneet.agentteam.auth.security;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.OrgMember;
import io.jsonwebtoken.*;
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

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-expiry-minutes:15}") long accessTokenExpiryMinutes,
            @Value("${app.jwt.refresh-token-expiry-days:7}") long refreshTokenExpiryDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
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

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
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
