package com.navneet.executespec.integration;

import java.time.Instant;

import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.observability.Metrics;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Publishes completion only after the caller has settled its final publication boundary. */
@Component
@RequiredArgsConstructor
@Slf4j
class RunCompletionPublisher {
  private final SseBroadcaster broadcaster;
  private final Metrics metrics;
  private final PipelineRunRepository runRepo;
  private final ApplicationEventPublisher eventPublisher;
  private final com.navneet.executespec.analytics.EventTrackingService eventTracking;

  void publish(
      String runId,
      String actualStatus,
      String actualReason,
      String note,
      Instant startedAt,
      RunCostTracker.Snapshot cost) {
    broadcaster.emit(runId, RunEvent.runStatus(runId, actualStatus));
    broadcaster.emit(runId, RunEvent.costUpdate(runId, cost.costUsd()));
    broadcaster.emit(runId, RunEvent.complete(runId, actualStatus, actualReason));
    if (!"PAUSED".equals(actualStatus)) {
      broadcaster.complete(runId);
    }
    // V2 run-level metrics are emitted by RunService only. This orchestrator is a
    // parallel entry path — record run.outcome + run.latency here too so V1-endpoint-
    // submitted V2 runs are counted.
    metrics.recordRunOutcome(actualStatus);
    Instant effectiveStartedAt =
        startedAt != null
            ? startedAt
            : runRepo.findById(runId).map(PipelineRun::getStartedAt).orElse(Instant.now());
    metrics.recordRunLatency(
        actualStatus, java.time.Duration.between(effectiveStartedAt, Instant.now()));
    // v2.5.8 — fire Spring ApplicationEvent so RunCompletionNotifier (in-app
    // notification), RunQuotaRefundListener (refund on failure), and
    // WorkspaceGcsUploadListener (archive workspace) all get notified.
    // V1 PipelineHarness already publishes this; V2 previously did not,
    // so notifications silently never fired for any V2 run.
    try {
      if ("COMPLETE".equalsIgnoreCase(actualStatus) || "PARTIAL".equalsIgnoreCase(actualStatus)) {
        eventPublisher.publishEvent(new PipelineEvent.RunCompleted(runId));
      } else if ("FAILED".equalsIgnoreCase(actualStatus)
          || "CANCELLED".equalsIgnoreCase(actualStatus)) {
        eventPublisher.publishEvent(
            new PipelineEvent.RunFailed(runId, note == null ? actualStatus : note));
      }
    } catch (Exception eventEx) {
      log.warn("V2 run={} event publish failed (non-fatal): {}", runId, eventEx.getMessage());
    }

    // Wave 2.1 — activation funnel step 5. Fires once per user on the
    // first run that reaches COMPLETE or PARTIAL (terminal-but-usable).
    // FAILED/CANCELLED don't count as activation signals — a user who
    // submits 3 runs that all fail hasn't hit the "my code works" moment.
    if (("COMPLETE".equalsIgnoreCase(actualStatus) || "PARTIAL".equalsIgnoreCase(actualStatus))
        && !"LOCAL_EXECUTION".equalsIgnoreCase(actualReason)) {
      try {
        runRepo
            .findById(runId)
            .ifPresent(
                r -> {
                  if (r.getCreatedByUserId() != null) {
                    eventTracking.track(
                        r.getCreatedByUserId(),
                        com.navneet.executespec.analytics.EventName.RUN_COMPLETE,
                        java.util.Map.of(
                            "terminalStatus", actualStatus,
                            "statusReason", actualReason == null ? "" : actualReason,
                            "aiProvider", r.getAiProvider() == null ? "" : r.getAiProvider()));
                  }
                });
      } catch (Exception trackEx) {
        log.warn(
            "Activation track RUN_COMPLETE failed for run={}: {}", runId, trackEx.getMessage());
      }
    }
  }
}
