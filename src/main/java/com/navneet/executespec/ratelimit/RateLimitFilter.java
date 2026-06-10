package com.navneet.executespec.ratelimit;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.TierEnforcementService;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed rate limiting filter. Registered after JwtAuthenticationFilter in the Spring
 * Security filter chain.
 *
 * <p>Keying strategy:
 *
 * <ul>
 *   <li>Authenticated requests: keyed by userId
 *   <li>Unauthenticated (auth endpoints, webhooks): keyed by IP address
 * </ul>
 *
 * <p>Uses Redis INCR with 60-second TTL per sliding window bucket.
 */
@Component
@Slf4j
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

  private final RateLimitProperties properties;
  private final RateLimitPolicy policy;
  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final TierEnforcementService tierEnforcementService;
  private final Counter redisFailopenCounter;

  private static final String RATE_LIMIT_KEY_PREFIX = "rl:";
  private static final String TIER_CACHE_PREFIX = "rl:tier:";
  private static final Duration TIER_CACHE_TTL = Duration.ofMinutes(2);

  /**
   * In-memory fallback counters keyed by "identifier:group:minuteBucket". Cleared every 60s by
   * bucket rotation.
   */
  private final ConcurrentHashMap<String, AtomicInteger> localCounters = new ConcurrentHashMap<>();

  private volatile long lastCleanupBucket = 0;

  public RateLimitFilter(
      RateLimitProperties properties,
      RateLimitPolicy policy,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      TierEnforcementService tierEnforcementService,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.policy = policy;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.tierEnforcementService = tierEnforcementService;
    this.redisFailopenCounter =
        Counter.builder("executespec.redis.failopen")
            .description(
                "Rate limiter requests passed through due to Redis being unreachable (fail-open)")
            .register(meterRegistry);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

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

    // All groups are now rate-limited (READ, WRITE, PREVIEW, DEPLOY included)

    String identifier = resolveIdentifier(request, group);
    String tier = resolveTier(request, group);
    int limit = policy.getLimit(group, tier);

    long minuteBucket = Instant.now().getEpochSecond() / 60;
    String redisKey = buildRateLimitKey(identifier, group, minuteBucket);

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
        log.info(
            "[RateLimit] Blocked {}: {} {} (group={}, tier={}, count={}/{})",
            identifier,
            method,
            path,
            group,
            tier,
            count,
            limit);

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(resetSeconds));

        // SDET 2026-05-16 (BUG-007) — emit the canonical 4xx error envelope so the frontend's
        // generic ApiError parser surfaces the message + retry info instead of falling back to its
        // catch-all string. Shape matches ValidationAdvice / GlobalExceptionAdvice exactly:
        // { timestamp, status, error, message, retryAfterSeconds }.
        objectMapper.writeValue(response.getWriter(), rateLimitedBody(resetSeconds));
        return;
      }

    } catch (Exception e) {
      // Redis failure — use in-memory fallback with conservative limits (fail-open)
      log.warn("[RateLimit] Redis error, using local fallback: {}", e.getMessage());
      redisFailopenCounter.increment();
      int fallbackLimit = Math.max(1, limit / 2); // 50% of normal limit
      String localKey = identifier + ":" + group + ":" + minuteBucket;
      cleanupLocalCounters(minuteBucket);
      int localCount =
          localCounters.computeIfAbsent(localKey, k -> new AtomicInteger(0)).incrementAndGet();
      if (localCount > fallbackLimit) {
        log.info(
            "[RateLimit] Local fallback blocked {}: {} {} (group={}, count={}/{})",
            identifier,
            method,
            path,
            group,
            localCount,
            fallbackLimit);
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        long resetSeconds = 60 - (Instant.now().getEpochSecond() % 60);
        response.setHeader("Retry-After", String.valueOf(resetSeconds));
        objectMapper.writeValue(response.getWriter(), rateLimitedBody(resetSeconds));
        return;
      }
    }

    filterChain.doFilter(request, response);
  }

  /**
   * Resolve the rate limit identifier. Auth and webhook requests: IP address. Authenticated
   * requests: userId.
   */
  private String resolveIdentifier(HttpServletRequest request, String group) {
    if ("AUTH".equals(group) || "WEBHOOK".equals(group)) {
      return getClientIp(request);
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null
        && auth.isAuthenticated()
        && auth.getPrincipal() instanceof AppUserDetails details) {
      return details.getUser().getId().toString();
    }

    // Fallback to IP for unauthenticated requests
    return getClientIp(request);
  }

  /**
   * Resolve the effective tier for rate limit lookup. Uses TierEnforcementService.resolveUserTier()
   * (org tier > user tier) with a 2-min Caffeine cache.
   */
  private String resolveTier(HttpServletRequest request, String group) {
    if ("AUTH".equals(group)) {
      return policy.getAuthSubType(request.getRequestURI());
    }
    if ("WEBHOOK".equals(group)) {
      return "DEFAULT";
    }

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AppUserDetails details) {
      String userId = details.getUser().getId().toString();
      return resolveAndCacheTier(userId);
    }

    return "FREE"; // Default for unauthenticated
  }

  /** Resolve tier via Redis cache (2-min TTL), falling back to DB via TierEnforcementService. */
  private String resolveAndCacheTier(String userId) {
    try {
      String cacheKey = TIER_CACHE_PREFIX + userId;
      String cached = redisTemplate.opsForValue().get(cacheKey);
      if (cached != null) {
        return cached;
      }
      String tier = tierEnforcementService.resolveUserTier(userId);
      redisTemplate.opsForValue().set(cacheKey, tier, TIER_CACHE_TTL);
      return tier;
    } catch (Exception e) {
      log.debug("[RateLimit] Tier cache error for {}: {}", userId, e.getMessage());
      return "FREE";
    }
  }

  /** Extract client IP from X-Forwarded-For using the configured trusted proxy depth. */
  private String getClientIp(HttpServletRequest request) {
    return extractClientIp(
        request.getHeader("X-Forwarded-For"),
        request.getRemoteAddr(),
        properties.getTrustedProxyCount());
  }

  static String extractClientIp(String xff, String remoteAddr, int trustedProxyCount) {
    if (xff != null && !xff.isBlank()) {
      String[] parts = xff.split(",");
      int trustedHops = Math.max(1, trustedProxyCount);
      int clientIndex = Math.max(0, parts.length - trustedHops - 1);
      String candidate = parts[clientIndex].trim();
      if (!candidate.isBlank()) {
        return candidate;
      }
    }
    return remoteAddr;
  }

  /** Builds the Redis key for rate limiting: {prefix}{identifier}:{group}:{minuteBucket}. */
  private String buildRateLimitKey(String identifier, String group, long minuteBucket) {
    return RATE_LIMIT_KEY_PREFIX + identifier + ":" + group + ":" + minuteBucket;
  }

  /**
   * SDET 2026-05-16 (BUG-007) — canonical 429 envelope shared by the Redis + in-memory fallback
   * paths so the frontend's error parser handles rate-limit responses identically to any other 4xx.
   * Order matches {@code ValidationAdvice.baseBody} / {@code GlobalExceptionAdvice.onAny} so any
   * future tooling that walks the envelope sees the same fields in the same order.
   */
  private static Map<String, Object> rateLimitedBody(long retryAfterSeconds) {
    java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", 429);
    body.put("error", "rate_limited");
    body.put("message", "Rate limit exceeded — retry in " + retryAfterSeconds + "s.");
    body.put("retryAfterSeconds", retryAfterSeconds);
    return body;
  }

  /** Remove local counter entries from previous minute buckets to prevent memory growth. */
  private void cleanupLocalCounters(long currentBucket) {
    if (currentBucket != lastCleanupBucket) {
      lastCleanupBucket = currentBucket;
      localCounters
          .keySet()
          .removeIf(
              key -> {
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
