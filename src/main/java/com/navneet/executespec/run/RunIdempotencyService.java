package com.navneet.executespec.run;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Server-side idempotency for run submission (RL-2026-06-005).
 *
 * <p>The headless clients (CLI / VS Code) retry a {@code POST /api/runs} on a transient 5xx with a
 * stable {@code Idempotency-Key} header (see {@code clients/core/src/api.ts}). Without server-side
 * dedupe, a 5xx returned <i>after</i> the run was already created spawns a duplicate run — and
 * burns the user's monthly quota twice. This maps {@code (userId, key) → runId} in Redis for 24h so
 * a retried submit returns the original run instead of creating a new one.
 *
 * <p><b>Scope:</b> keyed by {@code userId} so one user's key can never reference another user's
 * run.
 *
 * <p><b>Failure policy:</b> best-effort / fail-open, mirroring {@link
 * com.navneet.executespec.context.ContextStore}. If Redis is unavailable the dedupe is simply
 * skipped and the submit proceeds exactly as before — idempotency is an optimization, never a gate
 * that can block a legitimate submission.
 *
 * <p><b>Concurrency note:</b> this handles the dominant case — a client's <i>sequential</i> retry
 * after a lost/5xx response (the {@code record} lands before the retry arrives). Two genuinely
 * simultaneous identical submits could still both miss the lookup and create two runs; that is no
 * worse than today's behaviour and is vanishingly rare for a CLI whose retries are backoff-spaced.
 */
@Service
@Slf4j
public class RunIdempotencyService {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "run:idem:";

  private final StringRedisTemplate redis;

  @Autowired
  public RunIdempotencyService(ObjectProvider<StringRedisTemplate> redisProvider) {
    this(redisProvider.getIfAvailable());
  }

  RunIdempotencyService(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** The runId previously created for this {@code (user, key)}, if any (and Redis is up). */
  public Optional<String> lookup(String userId, String idempotencyKey) {
    if (redis == null || isBlank(userId) || isBlank(idempotencyKey)) return Optional.empty();
    try {
      return Optional.ofNullable(redis.opsForValue().get(key(userId, idempotencyKey)));
    } catch (Exception e) {
      log.debug("RunIdempotency lookup failed (fail-open): {}", e.getMessage());
      return Optional.empty();
    }
  }

  /** Record the runId created for this {@code (user, key)}. Best-effort, 24h TTL. */
  public void record(String userId, String idempotencyKey, String runId) {
    if (redis == null || isBlank(userId) || isBlank(idempotencyKey) || isBlank(runId)) return;
    try {
      redis.opsForValue().set(key(userId, idempotencyKey), runId, TTL);
    } catch (Exception e) {
      log.debug("RunIdempotency record failed (fail-open): {}", e.getMessage());
    }
  }

  private static String key(String userId, String idempotencyKey) {
    return KEY_PREFIX + userId + ":" + idempotencyKey;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
