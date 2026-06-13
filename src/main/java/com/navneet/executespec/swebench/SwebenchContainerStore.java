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
 * SWE-bench store, the in-container working directory comes from the stored task family ({@code
 * /testbed} for classic SWE-bench, {@code /app} for SWE-bench Pro) instead of the programbench
 * {@code /workspace}.
 *
 * <p>Redis-backed; 4-hour TTL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SwebenchContainerStore {

  private static final Duration TTL = Duration.ofHours(4);
  private static final String KEY_PREFIX = "swebench:run:";

  /** Default in-container working directory for classic SWE-bench tasks. */
  public static final String WORKDIR = "/testbed";

  private final StringRedisTemplate redis;

  public void put(String runId, String containerId) {
    put(runId, containerId, WORKDIR);
  }

  public void put(String runId, String containerId, String workdir) {
    if (runId == null || containerId == null) return;
    redis.opsForValue().set(KEY_PREFIX + runId + ":cid", containerId, TTL);
    redis
        .opsForValue()
        .set(
            KEY_PREFIX + runId + ":workdir",
            workdir == null || workdir.isBlank() ? WORKDIR : workdir,
            TTL);
    log.info(
        "SwebenchContainerStore.put runId={} cid={} workdir={}",
        runId,
        containerId,
        getWorkdir(runId));
  }

  /** Returns the containerId for this run, or {@code null} if this isn't a SWE-bench run. */
  public String get(String runId) {
    if (runId == null) return null;
    return redis.opsForValue().get(KEY_PREFIX + runId + ":cid");
  }

  public String getWorkdir(String runId) {
    if (runId == null) return WORKDIR;
    String workdir = redis.opsForValue().get(KEY_PREFIX + runId + ":workdir");
    return workdir == null || workdir.isBlank() ? WORKDIR : workdir;
  }

  public void remove(String runId) {
    if (runId == null) return;
    redis.delete(KEY_PREFIX + runId + ":cid");
    redis.delete(KEY_PREFIX + runId + ":workdir");
  }
}
