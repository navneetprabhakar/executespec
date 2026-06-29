package com.navneet.executespec.run;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Per-run override for whether automatic model/provider continuation requires a compatible
 * benchmark quality prior.
 *
 * <p>Redis-only and one-shot like {@link RunContinuationConfirmationFlag}: submit stores the
 * caller's explicit choice and {@code RunOrchestrator} consumes it once at pipeline start. Absence
 * means the server YAML default remains authoritative.
 */
@Service
@RequiredArgsConstructor
public class RunContinuationBenchmarkPriorFlag {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "run:continuation-benchmark-prior:";

  private final StringRedisTemplate redis;

  public void mark(String runId, boolean required) {
    if (runId == null || runId.isBlank()) return;
    redis.opsForValue().set(KEY_PREFIX + runId, required ? "1" : "0", TTL);
  }

  public Optional<Boolean> consume(String runId) {
    if (runId == null || runId.isBlank()) return Optional.empty();
    String value = redis.opsForValue().getAndDelete(KEY_PREFIX + runId);
    if ("1".equals(value)) {
      return Optional.of(true);
    }
    if ("0".equals(value)) {
      return Optional.of(false);
    }
    return Optional.empty();
  }
}
