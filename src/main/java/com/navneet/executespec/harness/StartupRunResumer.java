package com.navneet.executespec.harness;

import com.navneet.executespec.recovery.RecoveryProperties;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * On application startup, discovers all non-terminal runs and feeds them back
 * into the pipeline so deployments cause zero run loss.
 *
 * <p>Runs are dispatched to {@link PipelineHarness#resumeOnStartup} which
 * routes each run to the correct resume path based on its status. Concurrency
 * is bounded by the harness semaphore — excess runs queue naturally.</p>
 */
@Component
@Slf4j
public class StartupRunResumer {

    private static final Set<String> RESUMABLE_STATUSES = Set.of(
        "PLANNING", "AWAITING_APPROVAL", "WORKING", "STUCK", "REVIEWING", "AWAITING_MERGE"
    );

    /** Runs older than this are considered stale and won't be auto-resumed. */
    private static final Duration MAX_RUN_AGE = Duration.ofMinutes(60);

    private final PipelineHarness pipelineHarness;
    private final StateStore stateStore;
    private final RecoveryProperties recoveryProps;

    public StartupRunResumer(PipelineHarness pipelineHarness,
                             StateStore stateStore,
                             RecoveryProperties recoveryProps) {
        this.pipelineHarness = pipelineHarness;
        this.stateStore = stateStore;
        this.recoveryProps = recoveryProps;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!recoveryProps.isEnabled() || !recoveryProps.isAutoResumeOnStartup()) {
            log.info("Startup run resume is disabled — skipping");
            return;
        }

        // Non-blocking: spawn virtual thread so the application finishes starting
        Thread.ofVirtual().name("startup-run-resumer").start(this::discoverAndResume);
    }

    private void discoverAndResume() {
        int delaySeconds = recoveryProps.getStartupResumeDelaySeconds();
        log.info("Startup run resumer will scan for non-terminal runs in {}s", delaySeconds);

        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Startup run resumer interrupted during delay — aborting");
            return;
        }

        List<PipelineRun> candidates = stateStore.findRunsByStatuses(
            List.copyOf(RESUMABLE_STATUSES)
        );

        if (candidates.isEmpty()) {
            log.info("Startup run resumer: no non-terminal runs found — nothing to resume");
            return;
        }

        // Oldest first for FIFO fairness
        candidates.sort(Comparator.comparing(
            PipelineRun::getStartedAt,
            Comparator.nullsLast(Comparator.naturalOrder())
        ));

        log.info("Startup run resumer: found {} non-terminal run(s) — dispatching", candidates.size());

        int resumed = 0;
        int skipped = 0;

        for (PipelineRun run : candidates) {
            // Re-fetch to guard against status changes during the delay window
            PipelineRun fresh = stateStore.findRun(run.getRunId()).orElse(null);
            if (fresh == null || !RESUMABLE_STATUSES.contains(fresh.getStatus())) {
                log.debug("Run {} status changed to {} during delay — skipping",
                    run.getRunId(), fresh != null ? fresh.getStatus() : "deleted");
                skipped++;
                continue;
            }

            // Skip stale runs — if the run started over 60 minutes ago, it's too old
            // to resume safely (workspace may be gone, context is stale, gate TTLs expired)
            if (fresh.getStartedAt() != null
                    && Duration.between(fresh.getStartedAt(), Instant.now()).compareTo(MAX_RUN_AGE) > 0) {
                log.info("Run {} is too old to auto-resume (started {}m ago) — marking FAILED",
                    fresh.getRunId(),
                    Duration.between(fresh.getStartedAt(), Instant.now()).toMinutes());
                stateStore.markFailed(fresh.getRunId(),
                    "Auto-resume skipped: run exceeded 60-minute staleness threshold after server restart");
                skipped++;
                continue;
            }

            try {
                log.info("♻️ Auto-resuming run {} (status={}, goal={})",
                    fresh.getRunId(), fresh.getStatus(),
                    truncate(fresh.getUserRequest(), 80));
                pipelineHarness.resumeOnStartup(fresh);
                resumed++;
            } catch (Exception e) {
                log.error("Failed to resume run {} on startup: {}", fresh.getRunId(), e.getMessage(), e);
                skipped++;
            }
        }

        log.info("Startup recovery complete: resumed={}, skipped={}", resumed, skipped);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "(no goal)";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
