package com.navneet.executespec.v2.batch;

import com.navneet.executespec.config.featureflag.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poll loop for in-flight V2 batch jobs. Plan 5 P1 scaffolding —
 * the poll body is a no-op today; P2 wires it to {@link V2BatchClient#poll}
 * and hands terminal jobs off to {@link V2BatchResumeService}.
 *
 * <p>Shedlock prevents two MIG replicas from polling the same job
 * simultaneously. 30-second cadence matches V1 batch.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class V2BatchPollService {

    @SuppressWarnings("unused") // wired in P2
    private final V2BatchClientRouter router;
    private final FeatureFlagService featureFlagService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    @SchedulerLock(name = "v2-batch-poll", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void pollInFlightJobs() {
        if (!featureFlagService.isEnabled(V2BatchCoordinator.FLAG_KEY)) {
            return;  // silent no-op until flipped on
        }
        log.debug("[V2BatchPollService] tick — no-op scaffold; P2 wires actual polling");
    }
}
