package com.navneet.executespec.v2.agent;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-strategy health tracker for the hierarchical planner. Mirrors the shape of {@code
 * ProviderHealthTracker} but keys by {@link PlanStrategy}.
 *
 * <p>When a strategy fails repeatedly (5 consecutive failures within 1 hour), the breaker opens for
 * that strategy and the orchestrator degrades:
 *
 * <pre>
 *   LEGACY  open → degrade to LARGE
 *   LARGE   open → degrade to MEDIUM
 *   MEDIUM  open → degrade to SMALL
 *   SMALL   open → degrade to legacy single-phase planner
 * </pre>
 *
 * <p>Auto-recovery via Redis TTL (1h) — once the open key expires, the strategy is assumed healthy
 * again and the next run can use it.
 *
 * <p>Fail-open: any Redis error degrades to "assume healthy" so a Redis outage never blocks
 * planning. Aligns with the project pattern in {@code router.ProviderHealthTracker}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PlanStrategyCircuitBreaker {

  private static final int FAILURE_THRESHOLD = 5;
  private static final Duration OPEN_TTL = Duration.ofHours(1);
  private static final Duration FAILURE_WINDOW = Duration.ofHours(1);

  private static final String OPEN_PREFIX = "planner:strategy:open:";
  private static final String FAIL_PREFIX = "planner:strategy:fail:";

  private final StringRedisTemplate redis;

  /**
   * @return the strategy to actually use. Equals the requested strategy when healthy; degrades by
   *     one step when the breaker is open. Returns null when SMALL itself is open (caller falls
   *     back to legacy planner).
   */
  public PlanStrategy degradeIfOpen(PlanStrategy requested) {
    if (requested == null) return null;
    try {
      PlanStrategy effective = requested;
      // Walk down the strategy ladder until we find a healthy one or
      // bottom out at "no strategy" (caller uses legacy planner).
      while (effective != null && isOpen(effective)) {
        PlanStrategy next = downgrade(effective);
        log.warn(
            "PlanStrategyCircuitBreaker: {} is open — degrading to {}",
            effective,
            next == null ? "(legacy fallback)" : next);
        effective = next;
      }
      return effective;
    } catch (Exception e) {
      log.warn(
          "PlanStrategyCircuitBreaker.degradeIfOpen Redis error — assuming healthy: {}",
          e.getMessage());
      return requested;
    }
  }

  /**
   * Record a successful run for the strategy. Clears the consecutive-failure counter and any
   * open-breaker key. No-op on Redis error.
   */
  public void recordSuccess(PlanStrategy strategy) {
    if (strategy == null) return;
    try {
      redis.delete(failKey(strategy));
      redis.delete(openKey(strategy));
    } catch (Exception e) {
      log.debug("PlanStrategyCircuitBreaker.recordSuccess Redis error: {}", e.getMessage());
    }
  }

  /**
   * Record a failed run for the strategy. When 5 consecutive failures land within the rolling 1h
   * window, the breaker opens for 1h.
   */
  public void recordFailure(PlanStrategy strategy) {
    if (strategy == null) return;
    try {
      Long failures = redis.opsForValue().increment(failKey(strategy));
      redis.expire(failKey(strategy), FAILURE_WINDOW);
      if (failures != null && failures >= FAILURE_THRESHOLD) {
        redis.opsForValue().set(openKey(strategy), "1", OPEN_TTL);
        log.warn(
            "PlanStrategyCircuitBreaker: {} OPENED after {} consecutive failures (TTL {}m)",
            strategy,
            failures,
            OPEN_TTL.toMinutes());
      }
    } catch (Exception e) {
      log.debug("PlanStrategyCircuitBreaker.recordFailure Redis error: {}", e.getMessage());
    }
  }

  private boolean isOpen(PlanStrategy strategy) {
    try {
      Boolean exists = redis.hasKey(openKey(strategy));
      return Boolean.TRUE.equals(exists);
    } catch (Exception e) {
      log.debug(
          "PlanStrategyCircuitBreaker.isOpen Redis error — assuming closed: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Strategy ladder for degradation. Each strategy degrades by exactly one step when its breaker is
   * open. SINGLE_SHOT can't degrade further (it's already the smallest); for symmetry we never even
   * check it (the orchestrator never asks the breaker about SINGLE_SHOT).
   */
  private static PlanStrategy downgrade(PlanStrategy s) {
    return switch (s) {
      case LEGACY -> PlanStrategy.LARGE;
      case LARGE -> PlanStrategy.MEDIUM;
      case MEDIUM -> PlanStrategy.SMALL;
      case SMALL -> null; // legacy fallback
      case SINGLE_SHOT -> null;
    };
  }

  private static String openKey(PlanStrategy s) {
    return OPEN_PREFIX + s.name();
  }

  private static String failKey(PlanStrategy s) {
    return FAIL_PREFIX + s.name();
  }
}
