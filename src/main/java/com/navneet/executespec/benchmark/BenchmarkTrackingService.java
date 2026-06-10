package com.navneet.executespec.benchmark;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The COMMON, suite-agnostic benchmark <b>tracking</b> layer (RL-2026-06, see {@code
 * docs/plans/benchmark-closed-loop-2026-06-09.md}). ExecuteSpec is the event <b>sink + display</b>,
 * NOT an orchestrator: an external verifier/harness (Harbor for Terminal-Bench; the SWE-bench /
 * ProgramBench harnesses next) runs the benchmark and <b>publishes events</b> here; this service
 * persists them ({@link BenchmarkRun} / {@link BenchmarkTaskResult} / {@link BenchmarkEvent}),
 * aggregates resolved% + cost, and re-broadcasts over SSE (reusing {@link SseBroadcaster}, keyed by
 * the benchmark-run id) so the in-login {@code /benchmark} dashboard updates live.
 *
 * <p>Suite-agnostic by construction — every suite's harness POSTs the <b>same</b> event shapes
 * ({@code TASK_SCORED} / {@code RUN_PROGRESS} / {@code RUN_DONE} / {@code SUBMISSION_*}); the
 * {@code suite} discriminator ({@link BenchmarkSuite}) is the only difference. Operator-only — the
 * {@link BenchmarkTrackingController} is SUPER_ADMIN +
 * {@code @Profile({"local","dev","benchmark"})}.
 *
 * <p>Distinct from the {@code benchmark/} SWE-bench <b>execution</b> harness ({@link
 * BenchmarkRunService} at {@code /api/admin/benchmark}), which actually runs instances through the
 * production pipeline. This layer only receives + shows what a verifier publishes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkTrackingService {

  private final BenchmarkRunRepository runs;
  private final BenchmarkTaskResultRepository taskResults;
  private final BenchmarkEventRepository events;
  private final SseBroadcaster sse;
  private final ObjectMapper mapper;

  // ── Create ──────────────────────────────────────────────────────────────

  /** Register a new tracked benchmark run (status RUNNING) and emit the opening event. */
  @Transactional
  public BenchmarkRun start(
      String suite,
      String dataset,
      String provider,
      String model,
      int k,
      int totalTasks,
      String harborJobName,
      String userId) {
    BenchmarkRun run =
        BenchmarkRun.builder()
            .id(UUID.randomUUID().toString())
            .suite(suite)
            .dataset(dataset == null ? "" : dataset)
            .provider(provider)
            .model(model)
            .k(Math.max(1, k))
            .totalTasks(Math.max(0, totalTasks))
            .resolvedTasks(0)
            .status("RUNNING")
            .costUsd(BigDecimal.ZERO)
            .harborJobName(harborJobName)
            .createdByUserId(userId)
            .createdAt(Instant.now())
            .startedAt(Instant.now())
            .build();
    runs.save(run);
    record(
        run.getId(),
        "RUN_STARTED",
        suite + " · " + model + " · k=" + run.getK() + " · " + run.getTotalTasks() + " tasks",
        null,
        null,
        null);
    return run;
  }

  // ── Ingest one published event ──────────────────────────────────────────

  /**
   * Ingest one event published by the external verifier. Updates aggregates for {@code TASK_SCORED}
   * / {@code RUN_DONE} / {@code SUBMISSION_*}, persists the event with a per-run seq, and
   * re-broadcasts over SSE. Unknown types are persisted + broadcast verbatim (forward-compatible).
   */
  @Transactional
  public BenchmarkRun ingest(
      String runId,
      String type,
      String taskName,
      Integer attempt,
      Integer reward,
      String esRunId,
      BigDecimal cost,
      Long inputTokens,
      Long outputTokens,
      String message,
      String payloadJson) {
    BenchmarkRun run =
        runs.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("benchmark run not found: " + runId));

    switch (type == null ? "" : type) {
      case "TASK_SCORED" -> {
        if (taskName != null && !taskName.isBlank()) {
          upsertTaskResult(
              runId, taskName, attempt, reward, esRunId, cost, inputTokens, outputTokens);
          recompute(run);
        }
      }
      case "RUN_DONE" -> {
        recompute(run);
        run.setStatus("DONE");
        run.setFinishedAt(Instant.now());
      }
      case "RUN_FAILED" -> {
        run.setStatus("FAILED");
        run.setFinishedAt(Instant.now());
        if (message != null && !message.isBlank()) {
          run.setError(message.length() > 4000 ? message.substring(0, 4000) : message);
        }
      }
      case "SUBMISSION_STARTED" -> run.setSubmissionStatus("SUBMITTING");
      case "SUBMITTED" -> {
        run.setSubmissionStatus("SUBMITTED");
        String url = extractField(payloadJson, "url");
        if (url != null) {
          run.setLeaderboardUrl(url.length() > 300 ? url.substring(0, 300) : url);
        }
      }
      case "SUBMISSION_FAILED" -> run.setSubmissionStatus("FAILED");
      default -> {
        /* RUN_PROGRESS, LOG, or any future type — persisted + broadcast verbatim below. */
      }
    }

    runs.save(run);
    record(runId, type, message, payloadJson, taskName, reward);
    return run;
  }

  /** Idempotent on (run, task, attempt) — a re-published score for the same attempt is a no-op. */
  private void upsertTaskResult(
      String runId,
      String taskName,
      Integer attempt,
      Integer reward,
      String esRunId,
      BigDecimal cost,
      Long inputTokens,
      Long outputTokens) {
    int att = attempt == null ? 1 : attempt;
    boolean exists =
        taskResults.findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(runId).stream()
            .anyMatch(t -> t.getTaskName().equals(taskName) && t.getAttempt() == att);
    if (exists) {
      return;
    }
    taskResults.save(
        BenchmarkTaskResult.builder()
            .id(UUID.randomUUID().toString())
            .benchmarkRunId(runId)
            .taskName(taskName)
            .attempt(att)
            .reward(reward == null ? 0 : Math.max(0, reward))
            .esRunId(esRunId)
            .costUsd(cost == null ? BigDecimal.ZERO : cost)
            .inputTokens(inputTokens == null ? 0L : Math.max(0L, inputTokens))
            .outputTokens(outputTokens == null ? 0L : Math.max(0L, outputTokens))
            .createdAt(Instant.now())
            .build());
  }

  /** Headline resolved% = distinct tasks with ≥1 passing attempt; cost = sum of attempt costs. */
  private void recompute(BenchmarkRun run) {
    List<BenchmarkTaskResult> all =
        taskResults.findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(run.getId());
    long resolved =
        all.stream()
            .filter(t -> t.getReward() > 0)
            .map(BenchmarkTaskResult::getTaskName)
            .distinct()
            .count();
    BigDecimal cost =
        all.stream()
            .map(BenchmarkTaskResult::getCostUsd)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    long inTok = all.stream().mapToLong(BenchmarkTaskResult::getInputTokens).sum();
    long outTok = all.stream().mapToLong(BenchmarkTaskResult::getOutputTokens).sum();
    run.setResolvedTasks((int) resolved);
    run.setCostUsd(cost);
    run.setInputTokens(inTok);
    run.setOutputTokens(outTok);
  }

  // ── Event persistence + SSE re-broadcast ────────────────────────────────

  private void record(
      String runId,
      String type,
      String message,
      String payloadJson,
      String taskName,
      Integer reward) {
    int seq = (int) events.countByBenchmarkRunId(runId);
    events.save(
        BenchmarkEvent.builder()
            .id(UUID.randomUUID().toString())
            .benchmarkRunId(runId)
            .seq(seq)
            .type(type == null ? "UNKNOWN" : type)
            .message(message)
            .payload(payloadJson)
            .createdAt(Instant.now())
            .build());

    try {
      sse.emit(
          runId,
          new RunEvent(
              type == null ? "UNKNOWN" : type,
              runId,
              taskName,
              ssePayload(type, taskName, reward, message),
              Instant.now()));
      if ("RUN_DONE".equals(type) || "RUN_FAILED".equals(type)) {
        sse.complete(runId);
      }
    } catch (Exception e) {
      // SSE is best-effort — the event is already persisted + replayable.
      log.debug("benchmark sse emit failed for {}: {}", runId, e.getMessage());
    }
  }

  private String ssePayload(String type, String taskName, Integer reward, String message) {
    try {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("type", type);
      if (taskName != null) {
        m.put("task", taskName);
      }
      if (reward != null) {
        m.put("reward", reward);
      }
      if (message != null) {
        m.put("message", message);
      }
      return mapper.writeValueAsString(m);
    } catch (Exception e) {
      return message == null ? "" : message;
    }
  }

  private String extractField(String payloadJson, String field) {
    if (payloadJson == null || payloadJson.isBlank()) {
      return null;
    }
    try {
      var node = mapper.readTree(payloadJson).path(field);
      return node.isMissingNode() || node.isNull() ? null : node.asText();
    } catch (Exception e) {
      return null;
    }
  }

  // ── Reads ───────────────────────────────────────────────────────────────

  public List<BenchmarkRun> list() {
    return runs.findTop50ByOrderByCreatedAtDesc();
  }

  public BenchmarkRun get(String id) {
    return runs.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("benchmark run not found: " + id));
  }

  public List<BenchmarkTaskResult> taskResults(String id) {
    return taskResults.findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(id);
  }

  public List<BenchmarkEvent> events(String id) {
    return events.findByBenchmarkRunIdOrderBySeqAsc(id);
  }

  /** Live SSE feed for one run, reusing the shared broadcaster (Redis-backed + replay). */
  public SseEmitter subscribe(String id, String lastEventId) {
    return sse.subscribe(id, lastEventId);
  }
}
