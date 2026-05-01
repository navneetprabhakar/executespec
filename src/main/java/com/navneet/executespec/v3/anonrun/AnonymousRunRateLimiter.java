package com.navneet.executespec.v3.anonrun;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-111 follow-up — Redis-backed per-IP rate limit for the anonymous-trial endpoint. Replaces the
 * in-memory ConcurrentHashMap that lost state on JVM restart and didn't hold across MIG nodes.
 *
 * <p>INCR-then-EXPIRE pattern: first call in the window sets the TTL; subsequent calls just bump
 * the counter. Once the TTL expires the key vanishes and the next call starts a fresh window. The
 * TTL is applied only when the counter is created (value=1) so the window never auto-extends.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnonymousRunRateLimiter {

  private static final String KEY_PREFIX = "v3:anon-run:rl:";

  /** Cap per IP per window. */
  public static final int LIMIT_PER_HOUR = 3;

  public static final Duration WINDOW = Duration.ofHours(1);

  private final StringRedisTemplate redis;

  /**
   * @return true when the call is permitted; false when the IP has hit the cap.
   */
  public boolean tryConsume(String ip) {
    if (ip == null || ip.isBlank()) {
      // Defensive: an unidentifiable caller shouldn't get an unlimited
      // allowance. Treat as one shared bucket.
      ip = "_unknown";
    }
    String key = KEY_PREFIX + ip;
    Long count = redis.opsForValue().increment(key);
    if (count == null) {
      // Redis hiccup — fail open so a partial outage doesn't black-hole
      // the trial path. The cost-cap envelope is the second line of defence.
      log.warn("v3 anon-run rate-limit redis returned null for ip={} — failing open", ip);
      return true;
    }
    if (count == 1L) {
      redis.expire(key, WINDOW);
    }
    return count <= LIMIT_PER_HOUR;
  }

  /** Test seam — read the current counter without incrementing. */
  long currentCount(String ip) {
    String raw = redis.opsForValue().get(KEY_PREFIX + ip);
    return raw == null ? 0L : Long.parseLong(raw);
  }
}
