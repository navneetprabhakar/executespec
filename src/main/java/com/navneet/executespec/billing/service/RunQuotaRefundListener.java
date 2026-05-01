package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refunds run quota when a pipeline run fails completely OR when the run terminated as a
 * false-positive PARTIAL (v2.5.12).
 *
 * <p>Refund rules:
 *
 * <ul>
 *   <li>{@code FAILED} — the system failed to deliver anything, refund.
 *   <li>{@code PARTIAL} with {@code statusReason=CLEAN_WITH_INTERMEDIATE_WARNINGS} — build clean +
 *       reviewer clean, the PARTIAL was caused by a flaky intermediate smoke test. Refund.
 *   <li>Everything else ({@code PARTIAL} with build/tests/reviewer issues, {@code CANCELLED}) — no
 *       refund; the user got partial value.
 * </ul>
 *
 * <p>Historical behaviour refunded only FAILED. v2.5.10 added {@code statusReason} (V107) so we can
 * now tell "clean PARTIAL" apart from genuine partials; that's the signal this listener now
 * consults.
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

    PipelineRun run = stateStore.findRun(runId).orElse(null);
    if (run == null) return;

    boolean isFailed = "FAILED".equals(run.getStatus());
    // v2.5.12 — refund PARTIAL runs that terminated clean-on-signals.
    boolean isCleanPartial =
        "PARTIAL".equals(run.getStatus())
            && "CLEAN_WITH_INTERMEDIATE_WARNINGS".equals(run.getStatusReason());

    if (!isFailed && !isCleanPartial) {
      log.debug(
          "Run {} status={} reason={} — no quota refund",
          runId,
          run.getStatus(),
          run.getStatusReason());
      return;
    }

    String userId = run.getCreatedByUserId();
    if (userId == null) return;

    // Refund the exact number of credits that were charged for this run
    // Batch runs charged batchCredits (discounted), not creditsCharged (real-time)
    int credits;
    if ("BATCH".equals(run.getApprovedExecutionMode()) && run.getBatchCredits() != null) {
      credits = run.getBatchCredits();
    } else {
      credits = run.getCreditsCharged() != null ? run.getCreditsCharged() : 1;
    }
    tierEnforcementService.decrementRunCount(userId, credits);
    usageTrackingService.decrement(userId, "RUNS", credits);

    String kind = isFailed ? "failed" : "clean-partial";
    log.info(
        "Refunded {} credit(s) for {} run {} (user={}, reason={})",
        credits,
        kind,
        runId,
        userId,
        run.getStatusReason());
  }
}
