package com.navneet.executespec.integration;

import static java.util.stream.Collectors.toMap;

import java.util.Map;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.reviewer.model.ReviewReport;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.IntegrationRun;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.IntegrationRunRepository;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Listens to pipeline events and notifies external source systems.
 *
 * <p>This component owns the <em>outbound</em> side of integrations only. It has no dependency on
 * {@link IntegrationManager} or the V2 run orchestrator — it purely reacts to Spring events and
 * delegates to the appropriate {@link OutboundAdapter}.
 *
 * <p>Separated from {@link IntegrationManager} to make the inbound/outbound concerns explicit and
 * independently testable.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IntegrationOutboundListener {

  private final IntegrationRunRepository integrationRunRepo;
  private final StateStore stateStore;
  private final java.util.List<OutboundAdapter> outboundAdapters;

  private volatile Map<String, OutboundAdapter> adapterMap;

  private Map<String, OutboundAdapter> adapters() {
    if (adapterMap == null) {
      adapterMap = outboundAdapters.stream().collect(toMap(OutboundAdapter::sourceType, a -> a));
    }
    return adapterMap;
  }

  @EventListener
  public void onRunStarted(PipelineEvent.RunStarted event) {
    notifyOutbound(event.runId(), "PLANNING");
  }

  /**
   * Listen to MergeCompleted instead of RunCompleted — the review report is guaranteed to exist at
   * this point. RunCompleted fires before background review starts.
   */
  @EventListener
  public void onMergeCompleted(PipelineEvent.MergeCompleted event) {
    notifyOutbound(event.runId(), "COMPLETE");
  }

  @EventListener
  public void onRunFailed(PipelineEvent.RunFailed event) {
    notifyOutbound(event.runId(), "FAILED");
  }

  private void notifyOutbound(String runId, String status) {
    Runnable task =
        ContextSnapshotFactory.builder()
            .build()
            .captureAll()
            .wrap(
                () -> {
                  integrationRunRepo
                      .findByRunId(runId)
                      .ifPresent(
                          ir -> {
                            OutboundAdapter adapter = adapters().get(ir.getSourceType());
                            if (adapter == null) return;

                            PipelineRun run;
                            try {
                              run = stateStore.findRun(runId).orElse(null);
                              if (run == null) return;
                            } catch (Exception e) {
                              log.error(
                                  "[Integration] Could not load run {} for outbound notification:"
                                      + " {}",
                                  runId,
                                  e.getMessage());
                              return;
                            }

                            try {
                              dispatch(adapter, ir, run, status);
                            } catch (Exception e) {
                              log.error(
                                  "[Integration] Outbound notification failed for run {} to {}: {}",
                                  runId,
                                  ir.getSourceType(),
                                  e.getMessage());
                            }
                          });
                });
    Thread.ofVirtual().name("integration-outbound-" + runId).start(task);
  }

  private void dispatch(
      OutboundAdapter adapter, IntegrationRun ir, PipelineRun run, String status) {
    switch (status) {
      case "PLANNING" -> adapter.onRunStarted(ir, run);
      case "COMPLETE" -> {
        ReviewReport review = stateStore.getReviewReportV2(run.getRunId());
        adapter.onRunComplete(ir, run, review);
      }
      case "FAILED" ->
          adapter.onRunFailed(
              ir, run, run.getFailureReason() != null ? run.getFailureReason() : "Unknown failure");
      default ->
          log.warn("[Integration] Unknown outbound status '{}' for run {}", status, run.getRunId());
    }
  }
}
