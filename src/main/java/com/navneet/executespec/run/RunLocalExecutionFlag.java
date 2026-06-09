package com.navneet.executespec.run;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * P3 local-hands (RL-2026-06-014) — per-run transient marker that this run should execute in LOCAL
 * mode (the server-side brain runs as normal, but worker tool calls are proxied to the caller's
 * machine; see {@link com.navneet.executespec.api.dto.RunRequest#localExecution}).
 *
 * <p>Mirrors {@link RunSkipPushFlag} exactly: a Redis-only, 24h-TTL key set at submit and consumed
 * once on the pipeline thread, so a re-run of the same id won't inherit a stale flag and a JVM
 * crash simply loses the intent (default = remote execution). Intentionally not persisted to {@code
 * pipeline_run} — LOCAL is a per-submit decision.
 */
@Service
@RequiredArgsConstructor
public class RunLocalExecutionFlag {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "run:local-exec:";

  private final StringRedisTemplate redis;

  /** Set the local-execution flag for a run. Idempotent. */
  public void mark(String runId) {
    if (runId == null || runId.isBlank()) return;
    redis.opsForValue().set(KEY_PREFIX + runId, "1", TTL);
  }

  /**
   * Read the flag and delete it atomically. Returns {@code true} if the flag was set.
   *
   * <p>RunOrchestrator calls this once at the top of the pipeline to choose LOCAL vs remote
   * execution. The delete-after-read makes it one-shot.
   */
  public boolean consume(String runId) {
    if (runId == null || runId.isBlank()) return false;
    String value = redis.opsForValue().getAndDelete(KEY_PREFIX + runId);
    return "1".equals(value);
  }
}
