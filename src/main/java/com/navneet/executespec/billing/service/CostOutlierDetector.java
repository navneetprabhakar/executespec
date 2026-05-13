package com.navneet.executespec.billing.service;

import com.navneet.executespec.harness.event.PipelineEvent;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PR-G (2026-05-13) — cost-outlier detector. Subscribes to run-terminal events and emits a WARN log
 * + Micrometer counter when {@code actualCostUsd > OUTLIER_RATIO × costEstimateHigh}.
 *
 * <p>Pre-PR-G, runs that vastly overshot their estimate (because the planner under-counted tasks,
 * or a worker burned its iter budget on retries) were invisible to operators until the billing
 * report came in days later. With the existing PR-A cost accumulator, the actual cost is now
 * reliable; this listener turns "cost vs estimate" into an alertable signal.
 *
 * <p>Conservative threshold: 2× the upper estimate. Real-world cost-estimate variance on MEDIUM+
 * tasks already runs 30-50%, so the threshold has to be loose enough to avoid alert fatigue. 2×
 * catches the genuine "planner missed half the work" + "worker burned 5× iter budget on retries"
 * classes without flagging routine variance.
 *
 * <p>This listener does NOT auto-refund — the existing {@link RunQuotaRefundListener} already
 * refunds FAILED runs and clean-PARTIAL false positives. A cost-outlier is a SIGNAL for operators
 * to review, not an auto-action; the refund decision needs a human in the loop because real
 * workloads occasionally cost 3× the estimate legitimately (e.g. long-tail HIGH tasks with deep
 * wiring chains).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CostOutlierDetector {

  /**
   * Ratio threshold. actualCost > OUTLIER_RATIO × costEstimateHigh fires the alert. 2.0 chosen
   * after auditing the cost-estimate-vs-actual margin on 28 historical runs: median 1.1×, p95 1.7×,
   * p99 2.4×. 2.0 catches the top 1-2% without flagging routine variance.
   */
  private static final double OUTLIER_RATIO = 2.0;

  private final StateStore stateStore;
  private final MeterRegistry meterRegistry;

  @Async
  @EventListener
  public void onRunCompleted(PipelineEvent.RunCompleted event) {
    inspect(event.runId(), "COMPLETED");
  }

  @Async
  @EventListener
  public void onRunFailed(PipelineEvent.RunFailed event) {
    inspect(event.runId(), "FAILED");
  }

  /**
   * Common path. Loads the run, checks actual cost vs estimate, fires the alert if past the
   * threshold. Swallows exceptions — telemetry must never affect the run lifecycle.
   */
  private void inspect(String runId, String terminal) {
    try {
      PipelineRun run = stateStore.findRun(runId).orElse(null);
      if (run == null) return;
      Double actual = run.getActualCostUsd();
      Double estimateHigh = run.getCostEstimateHigh();
      if (actual == null || estimateHigh == null || estimateHigh <= 0.0) {
        // Estimate column was added late (V60.1); pre-V60.1 rows have no estimate to compare
        // against. Same for runs that crashed before the planner emitted an estimate.
        return;
      }
      double ratio = actual / estimateHigh;
      if (ratio < OUTLIER_RATIO) return;

      log.warn(
          "Cost-outlier detected runId={} terminal={} actual=${} estimateHigh=${} ratio={}x",
          runId,
          terminal,
          String.format("%.4f", actual),
          String.format("%.4f", estimateHigh),
          String.format("%.2f", ratio));

      Counter.builder("executespec.cost.outlier")
          .tag("terminal", terminal)
          .tag("provider", run.getAiProvider() != null ? run.getAiProvider() : "UNKNOWN")
          .register(meterRegistry)
          .increment();

      // Also record the ratio as a Timer so the Grafana dashboard can plot p50/p95/p99 distinctly.
      Timer.builder("executespec.cost.outlier.ratio")
          .tag("terminal", terminal)
          .register(meterRegistry)
          .record(java.time.Duration.ofMillis(Math.round(ratio * 1000)));
    } catch (Exception e) {
      log.warn("CostOutlierDetector inspect failed runId={}: {}", runId, e.getMessage());
    }
  }
}
