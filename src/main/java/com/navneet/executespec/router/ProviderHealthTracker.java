package com.navneet.executespec.router;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed LLM provider circuit breaker.
 *
 * <p>Tracks consecutive failures per provider across all threads/instances via Redis. A provider is
 * marked UNHEALTHY after {@code UNHEALTHY_THRESHOLD} consecutive failures. Recovery is automatic
 * via Redis TTL — the unhealthy key expires after {@code RECOVERY_WINDOW}, at which point the
 * provider is assumed healthy again (same default-healthy semantics).
 *
 * <h3>Redis key design</h3>
 *
 * <pre>
 *   provider:health:{ANTHROPIC|OPENAI|GOOGLE}   → "UNHEALTHY"  (TTL = 60s)
 *   provider:failures:{ANTHROPIC|OPENAI|GOOGLE}  → consecutive failure count  (TTL = 120s)
 * </pre>
 *
 * <p>Absent health key = healthy. This means Redis downtime degrades gracefully to "assume all
 * providers healthy" (same as the old in-memory default).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderHealthTracker {

  private static final int UNHEALTHY_THRESHOLD = 10;
  private static final Duration RECOVERY_WINDOW = Duration.ofSeconds(180);
  private static final Duration FAILURE_COUNTER_TTL = Duration.ofSeconds(300);

  private static final String HEALTH_KEY_PREFIX = "provider:health:";
  private static final String FAILURE_KEY_PREFIX = "provider:failures:";
  private static final String UNHEALTHY_VALUE = "UNHEALTHY";

  private final StringRedisTemplate redis;
  private final MeterRegistry meterRegistry;

  /**
   * B-11 fix (2026-05-09) — atomic INCR + EXPIRE + threshold-check + health SET via Lua.
   *
   * <p>Pre-fix, recordFailure() did three independent Redis round-trips:
   *
   * <pre>
   *   1. INCR failures
   *   2. EXPIRE failures TTL
   *   3. (if count >= threshold) GET + SET health UNHEALTHY TTL
   * </pre>
   *
   * <p>A pod could crash between steps 1 and 2, leaving the failures counter without a TTL —
   * circuit could stick open forever (well, until a successful call cleared it). Worse: with
   * three pods racing on the same provider, INCRs interleave and the threshold check fires on
   * a count that disagrees with what the other pods see. Across pods, circuit could
   * mark-then-clear-then-mark in unexpected order.
   *
   * <p>Lua keeps all four operations on a single Redis round-trip executed atomically by the
   * Redis server — no inter-step interleave possible.
   *
   * <p>Returns the new failure count (Long) so the caller can decide whether to log the
   * "circuit opened" message. The script always returns a Long; null means the script failed
   * (Redis down) and the caller falls through to the fail-open path.
   */
  private static final DefaultRedisScript<Long> RECORD_FAILURE_SCRIPT;

  static {
    String lua =
        "local failures_key = KEYS[1] "
            + "local health_key  = KEYS[2] "
            + "local failure_ttl = tonumber(ARGV[1]) "
            + "local recovery_ttl = tonumber(ARGV[2]) "
            + "local threshold = tonumber(ARGV[3]) "
            + "local unhealthy_value = ARGV[4] "
            + "local count = redis.call('INCR', failures_key) "
            + "redis.call('EXPIRE', failures_key, failure_ttl) "
            + "if count >= threshold then "
            + "  redis.call('SET', health_key, unhealthy_value, 'EX', recovery_ttl) "
            + "end "
            + "return count";
    RECORD_FAILURE_SCRIPT = new DefaultRedisScript<>(lua, Long.class);
  }

  /**
   * Returns true if the provider is healthy (circuit closed). Absent key = healthy. Redis errors
   * degrade to healthy (fail-open).
   */
  public boolean isHealthy(LlmProvider provider) {
    try {
      String value = redis.opsForValue().get(healthKey(provider));
      return !UNHEALTHY_VALUE.equals(value);
    } catch (Exception e) {
      log.debug("Redis error checking health for {} — assuming healthy", provider, e);
      return true; // fail-open: if Redis is down, don't block LLM calls
    }
  }

  /** Records a successful call — clears failure counter and unhealthy state immediately. */
  public void recordSuccess(LlmProvider provider) {
    try {
      boolean wasUnhealthy = UNHEALTHY_VALUE.equals(redis.opsForValue().get(healthKey(provider)));
      redis.delete(healthKey(provider));
      redis.delete(failureKey(provider));
      if (wasUnhealthy) {
        log.info("Provider {} circuit CLOSED (recovered after successful call)", provider);
        Counter.builder("executespec.provider.circuit_closed")
            .tag("provider", provider.name())
            .register(meterRegistry)
            .increment();
      }
    } catch (Exception e) {
      log.debug("Redis error recording success for {}", provider, e);
    }
  }

  /**
   * Records a 429 rate-limit response — increments metric but does NOT advance the circuit-breaker
   * failure counter. Rate limits are transient quota events, not provider health events; opening
   * the circuit on 429 causes false-positive outages and unnecessary failovers.
   */
  public void recordRateLimit(LlmProvider provider) {
    Counter.builder("executespec.llm.rate_limited")
        .tag("provider", provider.name())
        .register(meterRegistry)
        .increment();
    log.debug("Provider {} rate-limited (429) — circuit breaker NOT incremented", provider);
  }

  /**
   * Records a failed call — increments consecutive failure counter. If threshold reached, sets the
   * unhealthy key with TTL-based auto-recovery.
   *
   * <p>B-11 fix (2026-05-09) — INCR + EXPIRE + threshold-check + health SET now run as a
   * single atomic Lua script (RECORD_FAILURE_SCRIPT above). Previously they were separate
   * Redis round-trips that could interleave across pods or crash mid-sequence.
   */
  public void recordFailure(LlmProvider provider) {
    try {
      // Pre-check the unhealthy state BEFORE running the script so we know whether to log
      // "circuit OPENED" exactly once across pods. This GET is a separate round-trip but it's
      // log-only — if it disagrees with the post-script state under racing pods, the worst
      // case is a duplicate or missed log line, not a state corruption.
      boolean alreadyUnhealthy =
          UNHEALTHY_VALUE.equals(redis.opsForValue().get(healthKey(provider)));

      Long count =
          redis.execute(
              RECORD_FAILURE_SCRIPT,
              List.of(failureKey(provider), healthKey(provider)),
              String.valueOf(FAILURE_COUNTER_TTL.toSeconds()),
              String.valueOf(RECOVERY_WINDOW.toSeconds()),
              String.valueOf(UNHEALTHY_THRESHOLD),
              UNHEALTHY_VALUE);

      if (count != null && count >= UNHEALTHY_THRESHOLD && !alreadyUnhealthy) {
        log.warn(
            "Provider {} circuit OPENED after {} consecutive failures — "
                + "will auto-recover in {}s",
            provider,
            count,
            RECOVERY_WINDOW.toSeconds());
        Counter.builder("executespec.provider.circuit_opened")
            .tag("provider", provider.name())
            .register(meterRegistry)
            .increment();
      }
    } catch (Exception e) {
      log.debug("Redis error recording failure for {}", provider, e);
    }
  }

  private static String healthKey(LlmProvider provider) {
    return HEALTH_KEY_PREFIX + provider.name();
  }

  private static String failureKey(LlmProvider provider) {
    return FAILURE_KEY_PREFIX + provider.name();
  }
}
