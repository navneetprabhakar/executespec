package com.navneet.executespec.integration;

import java.util.UUID;

import com.navneet.executespec.codeintel.ProjectArchitectureJobService;
import com.navneet.executespec.codeintel.ProjectArchitectureService;
import com.navneet.executespec.codeintel.StalenessService;
import com.navneet.executespec.router.LlmProvider;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refreshes stale project architecture before planning through the durable V353 job boundary.
 *
 * <p>This collaborator deliberately owns the bounded wait, promoted-result proof, and cache
 * invalidation as one focused lifecycle step. {@link RunOrchestrator} retains the surrounding
 * fail-soft policy but does not coordinate architecture-job state itself.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrePlannerArchitectureRefreshService {

  private final StalenessService stalenessService;
  private final ProjectArchitectureJobService architectureJobs;
  private final ProjectArchitectureService architectureService;

  /**
   * Refreshes only when the current staleness report proves at least one linked repository stale.
   *
   * @return {@code true} only after the durable job promoted an architecture that can be read back
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
        "V2 run={} project={} has {} stale codemap(s); regenerating before planner",
        runId,
        projectId,
        staleness.staleCount());
    UUID refreshedArchitectureId =
        architectureJobs.requestWithProviderAndAwaitArchitectureId(
            projectId, actorUserId, null, provider);
    architectureService
        .findById(refreshedArchitectureId)
        .orElseThrow(
            () -> new IllegalStateException("Promoted pre-planner architecture is unavailable"));
    stalenessService.invalidate(projectId);
    log.info("V2 run={} project={} completed durable pre-planner map refresh", runId, projectId);
    return true;
  }
}
