package com.navneet.executespec.router;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed LLM provider circuit breaker.
 *
 * <p>Tracks consecutive failures per provider across all threads/instances via Redis.
 * A provider is marked UNHEALTHY after {@code UNHEALTHY_THRESHOLD} consecutive failures.
 * Recovery is automatic via Redis TTL — the unhealthy key expires after {@code RECOVERY_WINDOW},
 * at which point the provider is assumed healthy again (same default-healthy semantics).
 *
 * <h3>Redis key design</h3>
 * <pre>
 *   provider:health:{ANTHROPIC|OPENAI|GOOGLE}   → "UNHEALTHY"  (TTL = 60s)
 *   provider:failures:{ANTHROPIC|OPENAI|GOOGLE}  → consecutive failure count  (TTL = 120s)
 * </pre>
 *
 * <p>Absent health key = healthy. This means Redis downtime degrades gracefully to
 * "assume all providers healthy" (same as the old in-memory default).
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
     * Returns true if the provider is healthy (circuit closed).
     * Absent key = healthy. Redis errors degrade to healthy (fail-open).
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

    /**
     * Records a successful call — clears failure counter and unhealthy state immediately.
     */
    public void recordSuccess(LlmProvider provider) {
        try {
            boolean wasUnhealthy = UNHEALTHY_VALUE.equals(redis.opsForValue().get(healthKey(provider)));
            redis.delete(healthKey(provider));
            redis.delete(failureKey(provider));
            if (wasUnhealthy) {
                log.info("Provider {} circuit CLOSED (recovered after successful call)", provider);
                Counter.builder("executespec.provider.circuit_closed")
                        .tag("provider", provider.name())
                        .register(meterRegistry).increment();
            }
        } catch (Exception e) {
            log.debug("Redis error recording success for {}", provider, e);
        }
    }

    /**
     * Records a 429 rate-limit response — increments metric but does NOT advance
     * the circuit-breaker failure counter. Rate limits are transient quota events,
     * not provider health events; opening the circuit on 429 causes false-positive
     * outages and unnecessary failovers.
     */
    public void recordRateLimit(LlmProvider provider) {
        Counter.builder("executespec.llm.rate_limited")
                .tag("provider", provider.name())
                .register(meterRegistry).increment();
        log.debug("Provider {} rate-limited (429) — circuit breaker NOT incremented", provider);
    }

    /**
     * Records a failed call — increments consecutive failure counter.
     * If threshold reached, sets the unhealthy key with TTL-based auto-recovery.
     */
    public void recordFailure(LlmProvider provider) {
        try {
            Long count = redis.opsForValue().increment(failureKey(provider));
            redis.expire(failureKey(provider), FAILURE_COUNTER_TTL);

            if (count != null && count >= UNHEALTHY_THRESHOLD) {
                // Check if already marked unhealthy to avoid redundant logging
                boolean alreadyUnhealthy = UNHEALTHY_VALUE.equals(
                        redis.opsForValue().get(healthKey(provider)));

                redis.opsForValue().set(healthKey(provider), UNHEALTHY_VALUE, RECOVERY_WINDOW);

                if (!alreadyUnhealthy) {
                    log.warn("Provider {} circuit OPENED after {} consecutive failures — "
                            + "will auto-recover in {}s",
                            provider, count, RECOVERY_WINDOW.toSeconds());
                    Counter.builder("executespec.provider.circuit_opened")
                            .tag("provider", provider.name())
                            .register(meterRegistry).increment();
                }
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
