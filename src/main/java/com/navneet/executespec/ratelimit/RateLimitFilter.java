package com.navneet.executespec.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis-backed rate limiting filter. Registered after JwtAuthenticationFilter
 * in the Spring Security filter chain.
 *
 * <p>Keying strategy:
 * <ul>
 *   <li>Authenticated requests: keyed by userId</li>
 *   <li>Unauthenticated (auth endpoints, webhooks): keyed by IP address</li>
 * </ul>
 *
 * <p>Uses Redis INCR with 60-second TTL per sliding window bucket.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitProperties properties;
    private final RateLimitPolicy policy;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** In-memory fallback counters keyed by "identifier:group:minuteBucket". Cleared every 60s by bucket rotation. */
    private final ConcurrentHashMap<String, AtomicInteger> localCounters = new ConcurrentHashMap<>();
    private volatile long lastCleanupBucket = 0;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Skip rate limiting for health checks and static resources
        if (path.equals("/api/health") || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String group = policy.classifyGroup(method, path);

        // Only rate-limit security-sensitive groups (AUTH, RUNS, WEBHOOK).
        // READ and WRITE are internal app traffic — no limit needed.
        if ("READ".equals(group) || "WRITE".equals(group)) {
            filterChain.doFilter(request, response);
            return;
        }

        String identifier = resolveIdentifier(request, group);
        String tier = resolveTier(request, group);
        int limit = policy.getLimit(group, tier);

        // Redis key: rl:{identifier}:{group}:{minuteBucket}
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        String redisKey = "rl:" + identifier + ":" + group + ":" + minuteBucket;

        try {
            Long count = redisTemplate.opsForValue().increment(redisKey);
            if (count != null && count == 1) {
                // First request in this bucket — set TTL
                redisTemplate.expire(redisKey, Duration.ofSeconds(60));
            }

            int remaining = Math.max(0, limit - (count != null ? count.intValue() : 0));
            long resetSeconds = 60 - (Instant.now().getEpochSecond() % 60);

            // Add rate limit headers to ALL responses
            response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            response.setHeader("X-RateLimit-Reset", String.valueOf(resetSeconds));

            if (count != null && count > limit) {
                // Rate limited
                log.info("[RateLimit] Blocked {}: {} {} (group={}, count={}/{})",
                        identifier, method, path, group, count, limit);

                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setHeader("Retry-After", String.valueOf(resetSeconds));

                objectMapper.writeValue(response.getWriter(), Map.of(
                        "error", "Rate limit exceeded",
                        "retryAfterSeconds", resetSeconds
                ));
                return;
            }

        } catch (Exception e) {
            // Redis failure — use in-memory fallback with conservative limits
            log.warn("[RateLimit] Redis error, using local fallback: {}", e.getMessage());
            int fallbackLimit = Math.max(1, limit / 2); // 50% of normal limit
            String localKey = identifier + ":" + group + ":" + minuteBucket;
            cleanupLocalCounters(minuteBucket);
            int localCount = localCounters.computeIfAbsent(localKey, k -> new AtomicInteger(0))
                    .incrementAndGet();
            if (localCount > fallbackLimit) {
                log.info("[RateLimit] Local fallback blocked {}: {} {} (group={}, count={}/{})",
                        identifier, method, path, group, localCount, fallbackLimit);
                response.setStatus(429);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                long resetSeconds = 60 - (Instant.now().getEpochSecond() % 60);
                response.setHeader("Retry-After", String.valueOf(resetSeconds));
                objectMapper.writeValue(response.getWriter(), Map.of(
                        "error", "Rate limit exceeded",
                        "retryAfterSeconds", resetSeconds
                ));
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Resolve the rate limit identifier.
     * Auth and webhook requests: IP address.
     * Authenticated requests: userId.
     */
    private String resolveIdentifier(HttpServletRequest request, String group) {
        if ("AUTH".equals(group) || "WEBHOOK".equals(group)) {
            return getClientIp(request);
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String userId) {
            return userId;
        }

        // Fallback to IP for unauthenticated requests
        return getClientIp(request);
    }

    /**
     * Resolve the tier key for rate limit lookup.
     * AUTH group: sub-type (login, register, forgot).
     * Other groups: user tier (FREE, PRO, ENTERPRISE).
     */
    private String resolveTier(HttpServletRequest request, String group) {
        if ("AUTH".equals(group)) {
            return policy.getAuthSubType(request.getRequestURI());
        }
        if ("WEBHOOK".equals(group)) {
            return "DEFAULT";
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null) {
            // Check if tier is stored in authorities or details
            for (var authority : auth.getAuthorities()) {
                String a = authority.getAuthority();
                if ("ROLE_ENTERPRISE".equals(a) || "TIER_ENTERPRISE".equals(a)) return "ENTERPRISE";
                if ("ROLE_PRO".equals(a) || "TIER_PRO".equals(a)) return "PRO";
            }
        }

        return "FREE"; // Default
    }

    /**
     * Extract client IP using the rightmost X-Forwarded-For entry (set by infrastructure,
     * not the client). The leftmost entry is client-controlled and can be spoofed.
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            // Use the rightmost entry minus trusted proxy count (default: last entry = proxy-added)
            int trustedIndex = Math.max(0, parts.length - 1);
            return parts[trustedIndex].trim();
        }
        return request.getRemoteAddr();
    }

    /** Remove local counter entries from previous minute buckets to prevent memory growth. */
    private void cleanupLocalCounters(long currentBucket) {
        if (currentBucket != lastCleanupBucket) {
            lastCleanupBucket = currentBucket;
            localCounters.keySet().removeIf(key -> {
                String[] parts = key.split(":");
                if (parts.length >= 3) {
                    try {
                        long keyBucket = Long.parseLong(parts[parts.length - 1]);
                        return keyBucket < currentBucket;
                    } catch (NumberFormatException e) {
                        return true;
                    }
                }
                return true;
            });
        }
    }
}
