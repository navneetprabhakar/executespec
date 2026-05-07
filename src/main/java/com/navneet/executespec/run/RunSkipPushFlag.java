package com.navneet.executespec.run;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Audit r5 (#5, 2026-05-06) — staged-PR-before-push mode.
 *
 * <p>Carries a per-run transient flag in Redis ("skip the auto-push to GitHub on this run") so
 * {@link com.navneet.executespec.integration.RunOrchestrator} can opt out of the trailing {@code
 * scmPushService.push(fresh)} call for runs the user wants to inspect locally before shipping.
 *
 * <p>Intentionally Redis-only — not persisted to {@code pipeline_run}. If the JVM crashes the
 * intent is lost and the next attempt resumes the default auto-push behaviour. Staging is a
 * per-submit decision, not a project-wide setting.
 *
 * <p>TTL is 24h, matching {@link com.navneet.executespec.context.ContextStore}'s conversation
 * snapshots and the longest realistic run. After the run terminates the key is read once (via
 * {@link #consume(String)}) and immediately deleted, so a re-run of the same id won't accidentally
 * inherit a stale flag.
 */
@Service
@RequiredArgsConstructor
public class RunSkipPushFlag {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "run:skip-push:";

  private final StringRedisTemplate redis;

  /** Set the skip-push flag for a run. Idempotent. */
  public void mark(String runId) {
    if (runId == null || runId.isBlank()) return;
    redis.opsForValue().set(KEY_PREFIX + runId, "1", TTL);
  }

  /**
   * Read the flag and delete it atomically. Returns {@code true} if the flag was set.
   *
   * <p>RunOrchestrator calls this once at the auto-push gate to decide whether to push. The delete
   * after read makes the flag a one-shot — a manual re-push later will go through normally.
   */
  public boolean consume(String runId) {
    if (runId == null || runId.isBlank()) return false;
    String value = redis.opsForValue().getAndDelete(KEY_PREFIX + runId);
    return "1".equals(value);
  }
}
