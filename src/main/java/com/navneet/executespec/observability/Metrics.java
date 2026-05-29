package com.navneet.executespec.observability;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

/**
 * Central facade for all V2 Micrometer metrics.
 *
 * <p>Every V2 metric name is prefixed {@code executespec.v2.*} so it cleanly separates from V1's
 * existing meters and can be side-by-side compared on dashboards. Tag keys are consistent across
 * metrics: {@code provider}, {@code model}, {@code classification}, {@code outcome}, {@code tool},
 * {@code tier} (e.g. {@code worker} / {@code planner} / {@code reviewer}).
 *
 * <h2>Metric catalog</h2>
 *
 * <ul>
 *   <li><b>{@code executespec.v2.run.outcome}</b> (counter) — tagged {overall} — V2 run terminal
 *       state per {@link com.navneet.executespec.rollout.Run.Status}.
 *   <li><b>{@code executespec.v2.run.latency}</b> (timer) — tagged {overall} — end-to-end V2
 *       pipeline latency.
 *   <li><b>{@code executespec.v2.planner.latency}</b> (timer) — tagged {classification, provider,
 *       model} — planner call duration.
 *   <li><b>{@code executespec.v2.planner.tasks}</b> (distribution) — tagged {classification} —
 *       tasks-per-plan count.
 *   <li><b>{@code executespec.v2.task.status}</b> (counter) — tagged {status} — per-task outcomes
 *       from the executor (SUCCESS | SUCCESS_AFTER_RETRY | ...)
 *   <li><b>{@code executespec.v2.task.latency}</b> (timer) — tagged {status} — per-task wall clock.
 *   <li><b>{@code executespec.v2.task.smoketest}</b> (counter) — tagged {result} — smoke-test
 *       pass/fail per task (pass | fail | retry_success).
 *   <li><b>{@code executespec.v2.worker.iterations}</b> (distribution) — tagged {outcome, provider,
 *       model} — iteration count per worker run.
 *   <li><b>{@code executespec.v2.worker.outcome}</b> (counter) — tagged {outcome, provider, model}
 *       — terminal worker state.
 *   <li><b>{@code executespec.v2.worker.toolcall}</b> (counter) — tagged {tool} — dispatched tool
 *       calls per tool name.
 *   <li><b>{@code executespec.v2.llm.latency}</b> (timer) — tagged {provider, model, outcome} —
 *       LlmRouter dispatch latency per provider call.
 *   <li><b>{@code executespec.v2.reviewer.verdict}</b> (counter) — tagged {verdict} — CLEAN /
 *       FIXED_ISSUES / UNRESOLVED_ISSUES / REVIEW_FAILED.
 *   <li><b>{@code executespec.v2.context.save}</b> (counter) — tagged {result} — ok | skipped |
 *       failed.
 *   <li><b>{@code executespec.v2.context.save.bytes}</b> (distribution) — serialized context size
 *       per save.
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class Metrics {

  public static final String NS = "executespec.v2.";

  private final MeterRegistry registry;

  public void recordRunOutcome(String overall) {
    Counter.builder(NS + "run.outcome")
        .tag("overall", nonNull(overall))
        .register(registry)
        .increment();
  }

  public void recordRunLatency(String overall, Duration elapsed) {
    Timer.builder(NS + "run.latency")
        .tag("overall", nonNull(overall))
        .register(registry)
        .record(elapsed);
  }

  public void recordPlannerLatency(
      String classification, String provider, String model, Duration elapsed) {
    Timer.builder(NS + "planner.latency")
        .tags(
            Tags.of(
                Tag.of("classification", nonNull(classification)),
                Tag.of("provider", nonNull(provider)),
                Tag.of("model", nonNull(model))))
        .register(registry)
        .record(elapsed);
  }

  public void recordPlannerTaskCount(String classification, int tasks) {
    DistributionSummary.builder(NS + "planner.tasks")
        .tag("classification", nonNull(classification))
        .register(registry)
        .record(tasks);
  }

  public void recordTaskStatus(String status) {
    Counter.builder(NS + "task.status")
        .tag("status", nonNull(status))
        .register(registry)
        .increment();
  }

  public void recordTaskLatency(String status, Duration elapsed) {
    Timer.builder(NS + "task.latency")
        .tag("status", nonNull(status))
        .register(registry)
        .record(elapsed);
  }

  public void recordSmokeTest(String result) {
    Counter.builder(NS + "task.smoketest")
        .tag("result", nonNull(result))
        .register(registry)
        .increment();
  }

  public void recordWorkerIterations(
      String outcome, String provider, String model, int iterations) {
    DistributionSummary.builder(NS + "worker.iterations")
        .tags(
            Tags.of(
                Tag.of("outcome", nonNull(outcome)),
                Tag.of("provider", nonNull(provider)),
                Tag.of("model", nonNull(model))))
        .register(registry)
        .record(iterations);
  }

  public void recordWorkerOutcome(String outcome, String provider, String model) {
    Counter.builder(NS + "worker.outcome")
        .tags(
            Tags.of(
                Tag.of("outcome", nonNull(outcome)),
                Tag.of("provider", nonNull(provider)),
                Tag.of("model", nonNull(model))))
        .register(registry)
        .increment();
  }

  public void recordToolCall(String tool) {
    Counter.builder(NS + "worker.toolcall")
        .tag("tool", nonNull(tool))
        .register(registry)
        .increment();
  }

  public void recordLlmLatency(String provider, String model, String outcome, Duration elapsed) {
    Timer.builder(NS + "llm.latency")
        .tags(
            Tags.of(
                Tag.of("provider", nonNull(provider)),
                Tag.of("model", nonNull(model)),
                Tag.of("outcome", nonNull(outcome))))
        .register(registry)
        .record(elapsed);
  }

  /**
   * V165 BYOK fix — counts every dispatched LLM call by which side provided the API key (USER / ORG
   * / PLATFORM). Dashboards filter by tier to spot regressions: e.g. a TEAM/ENT row showing
   * PLATFORM counts is the gap that the audit deep-dive identified. Source label comes from {@link
   * com.navneet.executespec.agent.RunKeyContext.Source}.
   */
  public void recordKeySource(String provider, String source) {
    Counter.builder(NS + "llm.key_source")
        .tags(Tags.of(Tag.of("provider", nonNull(provider)), Tag.of("source", nonNull(source))))
        .register(registry)
        .increment();
  }

  /**
   * [Q-NEW-07] (audit Sprint 4 follow-up, 2026-05-01) — counts every SmartClassifier verifier
   * engagement by outcome: * OK — verifier ran and produced a parsed verdict *
   * SKIPPED_MODEL_RESOLVE — ModelResolver couldn't pick a model * SKIPPED_PROMPT — PromptAssembler
   * failed (template / variables) * SKIPPED_LLM_CALL — router.call threw (4xx/5xx/timeout/etc) *
   * SKIPPED_EMPTY_BODY — model returned whitespace-only response * SKIPPED_JSON_PARSE — model
   * output didn't yield a parseable JSON object
   *
   * <p>Dashboards filter by `result` to spot silent skips. The audit caught this issue via spec-row
   * inspection only (every row had "LLM verifier unavailable") — with this counter, future skips
   * surface immediately in Grafana / Prometheus alerts.
   */
  public void recordClassifierVerifier(String result) {
    Counter.builder(NS + "classifier.verifier.engaged")
        .tag("result", nonNull(result))
        .register(registry)
        .increment();
  }

  public void recordReviewerVerdict(String verdict) {
    Counter.builder(NS + "reviewer.verdict")
        .tag("verdict", nonNull(verdict))
        .register(registry)
        .increment();
  }

  public void recordContextSave(String result, int bytes) {
    Counter.builder(NS + "context.save")
        .tag("result", nonNull(result))
        .register(registry)
        .increment();
    if (bytes > 0) {
      DistributionSummary.builder(NS + "context.save.bytes").register(registry).record(bytes);
    }
  }

  private static String nonNull(String s) {
    return (s == null || s.isBlank()) ? "unknown" : s;
  }
}
