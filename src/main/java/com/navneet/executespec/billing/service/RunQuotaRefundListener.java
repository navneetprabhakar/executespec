package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Refunds run quota when a pipeline run fails completely.
 *
 * <p>Failed runs should not count against the user's monthly quota — the system
 * failed to deliver any useful output, so the user shouldn't lose a run slot.
 *
 * <p>PARTIAL runs are NOT refunded because some tasks completed successfully
 * and delivered usable code to the workspace.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunQuotaRefundListener {

    private final StateStore stateStore;
    private final TierEnforcementService tierEnforcementService;
    private final UsageTrackingService usageTrackingService;

    @Async
    @EventListener
    public void onRunFailed(PipelineEvent.RunFailed event) {
        String runId = event.runId();

        // Only refund for genuinely FAILED runs, not PARTIAL
        // (RunFailed event is published for both FAILED and PARTIAL outcomes)
        PipelineRun run = stateStore.findRun(runId).orElse(null);
        if (run == null) return;

        if (!"FAILED".equals(run.getStatus())) {
            log.debug("Run {} has status {} (not FAILED) — no quota refund", runId, run.getStatus());
            return;
        }

        String userId = run.getCreatedByUserId();
        if (userId == null) return;

        // Decrement both quota counters
        tierEnforcementService.decrementRunCount(userId);
        usageTrackingService.decrement(userId, "RUNS", 1);

        log.info("Refunded run quota for failed run {} (user={})", runId, userId);
    }
}
