package com.navneet.executespec.run;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Per-run marker that asks the executor to stop for confirmation before spending on a
 * model/provider continuation action.
 *
 * <p>Redis-only and one-shot like {@link RunLocalExecutionFlag}: the submit handler sets it for a
 * new run, and {@code RunOrchestrator} consumes it once when building the executor request. This is
 * intentionally a tightening override only; it cannot disable an operator-level confirmation
 * requirement from YAML.
 */
@Service
@RequiredArgsConstructor
public class RunContinuationConfirmationFlag {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "run:continuation-confirm:";

  private final StringRedisTemplate redis;

  public void mark(String runId) {
    if (runId == null || runId.isBlank()) return;
    redis.opsForValue().set(KEY_PREFIX + runId, "1", TTL);
  }

  public boolean consume(String runId) {
    if (runId == null || runId.isBlank()) return false;
    String value = redis.opsForValue().getAndDelete(KEY_PREFIX + runId);
    return "1".equals(value);
  }
}
