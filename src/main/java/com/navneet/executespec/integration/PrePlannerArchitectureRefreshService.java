package com.navneet.executespec.integration;

import com.navneet.executespec.codeintel.ProjectArchitectureJobService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admits stale project architecture refresh before planning through the durable V353 job boundary.
 *
 * <p>Planning must not wait for a model-generated architecture that is only advisory context. The
 * durable worker owns generation, promotion, and staleness-cache invalidation; this collaborator
 * only detects staleness and idempotently admits the exact-provider job. {@link RunOrchestrator}
 * retains the surrounding fail-soft policy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrePlannerArchitectureRefreshService {

  private final StalenessService stalenessService;
  private final ProjectArchitectureJobService architectureJobs;

  /**
   * Refreshes only when the current staleness report proves at least one linked repository stale.
   *
   * @return {@code true} when a stale project refresh job was admitted or reused
   */
  public boolean refreshIfStale(
      String runId,
      String projectId,
      String actorUserId,
      String actorGithubPat,
      LlmProvider provider) {
    var staleness = stalenessService.report(projectId, actorGithubPat);
    if (staleness == null || !staleness.stale()) {
      return false;
    }

    log.info(
        "V2 run={} project={} has {} stale codemap(s); admitting background refresh",
        runId,
        projectId,
        staleness.staleCount());
    architectureJobs.requestWithProvider(projectId, actorUserId, null, provider);
    log.info("V2 run={} project={} admitted durable background map refresh", runId, projectId);
    return true;
  }
}
