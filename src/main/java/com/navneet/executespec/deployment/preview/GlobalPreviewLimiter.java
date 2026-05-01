package com.navneet.executespec.deployment.preview;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed per-user preview concurrency limiter.
 *
 * <p>Maintains a Redis counter {@code preview:count:{userId}} that is incremented on each preview
 * start and decremented on each stop. The counter auto-expires after 1 hour to handle app crashes
 * without leaking slots.
 *
 * <p>Per-tier limits:
 *
 * <ul>
 *   <li>FREE → 1 concurrent preview
 *   <li>PRO → 3 concurrent previews
 *   <li>ENTERPRISE → 10 concurrent previews
 * </ul>
 *
 * <p>The in-memory check in {@code PreviewService} handles single-instance correctness; this
 * limiter enforces correctness across multiple app instances. Fail-open on Redis errors: if Redis
 * is unreachable, {@code tryAcquire} returns {@code true} (in-memory check acts as fallback).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class GlobalPreviewLimiter {

  private static final String KEY_PREFIX = "preview:count:";
  private static final Duration COUNTER_TTL = Duration.ofHours(1);

  private static final int FREE_LIMIT = 1;
  private static final int PRO_LIMIT = 3;
  private static final int ENTERPRISE_LIMIT = 10;

  private final StringRedisTemplate redis;

  /**
   * Atomically increments the user's preview counter and checks against the tier limit.
   *
   * @param userId the user attempting to start a preview
   * @param tier the user's subscription tier (FREE/PRO/ENTERPRISE/MAX)
   * @return {@code true} if the slot was acquired, {@code false} if the limit is exceeded
   */
  public boolean tryAcquire(String userId, String tier) {
    int limit = limitForTier(tier);
    if (limit < 0) return true; // unlimited

    String key = KEY_PREFIX + userId;
    try {
      Long count = redis.opsForValue().increment(key);
      if (count == null) return true; // fail-open

      // Set TTL on first write (subsequent increments don't reset it)
      if (count == 1) {
        redis.expire(key, COUNTER_TTL);
      }

      if (count > limit) {
        // Over limit — decrement and reject
        redis.opsForValue().decrement(key);
        log.debug(
            "[GlobalPreviewLimiter] User {} at limit ({}/{} for {})",
            userId,
            count - 1,
            limit,
            tier);
        return false;
      }

      log.debug(
          "[GlobalPreviewLimiter] Acquired slot for user {} ({}/{} for {})",
          userId,
          count,
          limit,
          tier);
      return true;
    } catch (Exception e) {
      log.warn(
          "[GlobalPreviewLimiter] Redis error — fail-open for user {}: {}", userId, e.getMessage());
      return true; // fail-open: in-memory check acts as fallback
    }
  }

  /** Decrements the user's preview counter when a preview stops. Never decrements below zero. */
  public void release(String userId) {
    String key = KEY_PREFIX + userId;
    try {
      Long current = redis.opsForValue().decrement(key);
      if (current != null && current < 0) {
        redis.opsForValue().set(key, "0");
      }
      log.debug("[GlobalPreviewLimiter] Released slot for user {} (count={})", userId, current);
    } catch (Exception e) {
      log.warn(
          "[GlobalPreviewLimiter] Redis error releasing slot for user {}: {}",
          userId,
          e.getMessage());
    }
  }

  private int limitForTier(String tier) {
    if (tier == null) return FREE_LIMIT;
    return switch (tier.toUpperCase()) {
      case "ENTERPRISE", "TEAM" -> ENTERPRISE_LIMIT;
      case "PRO" -> PRO_LIMIT;
      default -> FREE_LIMIT;
    };
  }
}
