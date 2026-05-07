package com.navneet.executespec.programbench;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-run mapping of {@code runId → containerId} for ProgramBench tasks.
 *
 * <p>When ExecuteSpec is asked to run a programbench task, the per-task workflow:
 *
 * <ol>
 *   <li>Pulls the cleanroom image
 *   <li>Bootstraps the container with a host-bind-mounted workspace
 *   <li>Stashes {@code runId → containerId} here via {@link #put}
 *   <li>Calls {@link com.navneet.executespec.integration.RunOrchestrator#submitRun} as normal
 *   <li>The unmodified {@code ShellTool} / {@code BuildProjectTool} consult this store on each
 *       invocation; when a containerId is present, they dispatch via {@code docker exec} instead of
 *       running on the host
 *   <li>Post-terminal cleanup: {@code docker cp} workspace out, tar it, then {@link #remove}
 * </ol>
 *
 * <p>Redis-backed so multiple JVM nodes (if any) see the same mapping. 4-hour TTL — runs that
 * exceed that are out of cost-limit territory anyway.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgramBenchContainerStore {

  private static final Duration TTL = Duration.ofHours(4);
  private static final String KEY_PREFIX = "programbench:run:";

  private final StringRedisTemplate redis;

  public void put(String runId, String containerId) {
    if (runId == null || containerId == null) return;
    redis.opsForValue().set(KEY_PREFIX + runId + ":cid", containerId, TTL);
    log.info("ProgramBenchContainerStore.put runId={} cid={}", runId, containerId);
  }

  /** Returns the containerId for this run, or {@code null} if this isn't a programbench run. */
  public String get(String runId) {
    if (runId == null) return null;
    return redis.opsForValue().get(KEY_PREFIX + runId + ":cid");
  }

  public void remove(String runId) {
    if (runId == null) return;
    redis.delete(KEY_PREFIX + runId + ":cid");
  }
}
