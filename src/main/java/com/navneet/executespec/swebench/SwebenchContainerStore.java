package com.navneet.executespec.swebench;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-run mapping of {@code runId → containerId} for SWE-bench tasks.
 *
 * <p>Mirrors {@link com.navneet.executespec.programbench.ProgramBenchContainerStore} but for
 * SWE-bench. Separate Redis key prefix ({@code swebench:run:}) so the two adapters don't collide.
 * {@code ShellTool} and {@code BuildProjectTool} consult both stores; if a runId is in the
 * SWE-bench store, the in-container working directory is {@code /testbed} (vs {@code /workspace}
 * for programbench).
 *
 * <p>Redis-backed; 4-hour TTL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwebenchContainerStore {

  private static final Duration TTL = Duration.ofHours(4);
  private static final String KEY_PREFIX = "swebench:run:";

  /**
   * In-container working directory for SWE-bench tasks. Hardcoded because SWE-bench's official
   * docker images all use {@code WORKDIR /testbed}; if a future task family uses a different
   * convention we'll surface this as a per-instance field on the stored value.
   */
  public static final String WORKDIR = "/testbed";

  private final StringRedisTemplate redis;

  public void put(String runId, String containerId) {
    if (runId == null || containerId == null) return;
    redis.opsForValue().set(KEY_PREFIX + runId + ":cid", containerId, TTL);
    log.info("SwebenchContainerStore.put runId={} cid={}", runId, containerId);
  }

  /** Returns the containerId for this run, or {@code null} if this isn't a SWE-bench run. */
  public String get(String runId) {
    if (runId == null) return null;
    return redis.opsForValue().get(KEY_PREFIX + runId + ":cid");
  }

  public void remove(String runId) {
    if (runId == null) return;
    redis.delete(KEY_PREFIX + runId + ":cid");
  }
}
