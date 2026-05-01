package com.navneet.executespec.router;

import java.time.Duration;
import java.util.Map;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed per-model performance tracker for adaptive model routing.
 *
 * <p>Tracks success rate and average latency per provider×model over a sliding 24-hour window.
 * Stats decay automatically via Redis TTL — if a model hasn't been called in 24 hours, its stats
 * expire and routing falls back to default.
 *
 * <h3>Redis key design</h3>
 *
 * <pre>
 *   model:stats:{PROVIDER}:{model}  → hash {success, total, latency_sum}   TTL=24h
 * </pre>
 *
 * <h3>Scoring</h3>
 *
 * Score = success_rate, but only trusted when {@code total ≥ MIN_SAMPLE_COUNT}. Below that
 * threshold, returns {@link #UNKNOWN_SCORE} so routing ignores performance and relies on health
 * circuit breakers only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelPerformanceTracker {

  private static final String KEY_PREFIX = "model:stats:";
  private static final Duration STATS_TTL = Duration.ofHours(24);
  private static final int MIN_SAMPLE_COUNT = 5; // minimum calls before score is trusted
  public static final double UNKNOWN_SCORE = -1.0; // sentinel: insufficient data

  private static final String F_SUCCESS = "success";
  private static final String F_TOTAL = "total";
  private static final String F_LATENCY_SUM = "latency_sum";

  private final StringRedisTemplate redis;

  /**
   * Records a completed LLM call (success or failure) with its latency.
   *
   * @param provider provider name (e.g. "ANTHROPIC")
   * @param model model ID (e.g. "claude-haiku-4-5-20251001")
   * @param latencyMs wall-clock latency of the call in milliseconds
   * @param success true if the call returned a valid response
   */
  public void recordCall(String provider, String model, long latencyMs, boolean success) {
    String key = statsKey(provider, model);
    try {
      HashOperations<String, String, String> ops = redis.opsForHash();
      ops.increment(key, F_TOTAL, 1);
      if (success) ops.increment(key, F_SUCCESS, 1);
      ops.increment(key, F_LATENCY_SUM, latencyMs);
      redis.expire(key, STATS_TTL); // reset 24h window on each write
    } catch (Exception e) {
      log.debug("[ModelPerf] Redis error recording call for {}:{} — skipping", provider, model, e);
    }
  }

  /**
   * Returns the success-rate score for a given provider×model.
   *
   * @return score in [0.0, 1.0], or {@link #UNKNOWN_SCORE} if insufficient data
   */
  public double getScore(String provider, String model) {
    String key = statsKey(provider, model);
    try {
      Map<Object, Object> entries = redis.opsForHash().entries(key);
      if (entries.isEmpty()) return UNKNOWN_SCORE;

      long total = parseLong(entries.get(F_TOTAL));
      long success = parseLong(entries.get(F_SUCCESS));

      if (total < MIN_SAMPLE_COUNT) return UNKNOWN_SCORE;
      return (double) success / total;
    } catch (Exception e) {
      log.debug(
          "[ModelPerf] Redis error reading score for {}:{} — returning unknown",
          provider,
          model,
          e);
      return UNKNOWN_SCORE;
    }
  }

  /**
   * Returns the average latency in milliseconds for a given provider×model. Returns -1 if data is
   * insufficient.
   */
  public long getAvgLatencyMs(String provider, String model) {
    String key = statsKey(provider, model);
    try {
      Map<Object, Object> entries = redis.opsForHash().entries(key);
      long total = parseLong(entries.get(F_TOTAL));
      long latencySum = parseLong(entries.get(F_LATENCY_SUM));
      if (total < MIN_SAMPLE_COUNT) return -1L;
      return latencySum / total;
    } catch (Exception e) {
      return -1L;
    }
  }

  /**
   * Returns true if the given provider×model has a materially better success rate than {@code
   * otherProvider×otherModel}. "Materially better" = at least 10 pp improvement with sufficient
   * samples in both.
   */
  public boolean isMateriallyBetter(
      String provider, String model, String otherProvider, String otherModel) {
    double score = getScore(provider, model);
    double otherScore = getScore(otherProvider, otherModel);
    if (score == UNKNOWN_SCORE || otherScore == UNKNOWN_SCORE) return false;
    return score - otherScore >= 0.10; // ≥10 percentage points better
  }

  private static String statsKey(String provider, String model) {
    return KEY_PREFIX + provider.toUpperCase() + ":" + model;
  }

  private static long parseLong(Object val) {
    if (val == null) return 0L;
    try {
      return Long.parseLong(val.toString());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
