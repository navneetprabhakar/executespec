package com.navneet.executespec.v2.batch;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Scheduled poll loop for in-flight V2 batch jobs. Plan 5 P1 scaffolding — the poll body is a no-op
 * today; P2 wires it to {@link BatchClient#poll} and hands terminal jobs off to {@link
 * BatchResumeService}.
 *
 * <p>Shedlock prevents two MIG replicas from polling the same job simultaneously. 30-second cadence
 * matches V1 batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchPollService {

  @SuppressWarnings("unused") // wired in P2
  private final BatchClientRouter router;

  private final FeatureFlagService featureFlagService;

  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  @SchedulerLock(name = "v2-batch-poll", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
  public void pollInFlightJobs() {
    if (!featureFlagService.isEnabled(BatchCoordinator.FLAG_KEY)) {
      return; // silent no-op until flipped on
    }
    log.debug("[BatchPollService] tick — no-op scaffold; P2 wires actual polling");
  }
}
