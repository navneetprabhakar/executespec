package com.navneet.executespec.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.cost.RunStepUsage;
import com.navneet.executespec.cost.RunStepUsageRepository;
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
 * BenchmarkController} at {@code /api/admin/benchmark}), which actually runs instances through the
 * shared codegen engine. This layer only receives + shows what a verifier publishes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BenchmarkTrackingService {

  private static final java.time.Duration ACTIVE_ATTEMPT_HEARTBEAT_TTL =
      java.time.Duration.ofMinutes(15);

  private final BenchmarkRunRepository runs;
  private final BenchmarkTaskResultRepository taskResults;
  private final BenchmarkActiveAttemptRepository activeAttempts;
  private final BenchmarkEventRepository events;
  private final SseBroadcaster sse;
  private final ObjectMapper mapper;
  private final RunStepUsageRepository usage;

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
    return start(suite, dataset, provider, model, k, totalTasks, harborJobName, userId, false);
  }

  public BenchmarkRun start(
      String suite,
      String dataset,
      String provider,
      String model,
      int k,
      int totalTasks,
      String harborJobName,
      String userId,
      boolean submissionRequired) {
    Instant now = Instant.now();
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
            .createdAt(now)
            .startedAt(now)
            .lastProgressAt(now)
            .submissionRequired(submissionRequired)
            .submissionReadiness(submissionRequired ? "IN_PROGRESS" : "NOT_REQUIRED")
            .build();
    runs.save(run);
    record(
        run,
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
    return ingest(
        runId,
        type,
        taskName,
        attempt,
        reward,
        esRunId,
        cost,
        inputTokens,
        outputTokens,
        message,
        payloadJson,
        null,
        null,
        null,
        null,
        null,
        null);
  }

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
      String payloadJson,
      String outcome,
      String reason) {
    return ingest(
        runId,
        type,
        taskName,
        attempt,
        reward,
        esRunId,
        cost,
        inputTokens,
        outputTokens,
        message,
        payloadJson,
        outcome,
        reason,
        null,
        null,
        null,
        null);
  }

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
      String payloadJson,
      String outcome,
      String reason,
      String failureClassification,
      String trajectoryLocation,
      String trajectorySha256,
      Long etaSeconds) {
    // Lock the parent row before allocating the per-run event sequence and checking the
    // task-attempt idempotency key.  Harbor publishes results concurrently; without this lock two
    // JVMs can both allocate the same seq or race the unique task-attempt insert.
    BenchmarkRun run =
        runs.findByIdForUpdate(runId)
            .orElseThrow(() -> new IllegalArgumentException("benchmark run not found: " + runId));

    String eventType = type == null ? "" : type;
    Instant now = Instant.now();
    if (taskName != null && taskName.length() > 200) {
      throw new IllegalArgumentException("taskName must be at most 200 characters");
    }
    if (attempt != null && attempt < 1) {
      throw new IllegalArgumentException("attempt must be positive");
    }
    String eventTrajectoryLocation =
        firstNonBlank(trajectoryLocation, extractField(payloadJson, "trajectoryLocation"));
    String eventTrajectorySha =
        firstNonBlank(trajectorySha256, extractField(payloadJson, "trajectorySha256"));
    validateTrajectory(eventTrajectoryLocation, eventTrajectorySha);
    Long eventEta = etaSeconds == null ? extractLong(payloadJson, "etaSeconds") : etaSeconds;
    if (eventEta != null && eventEta < 0) {
      throw new IllegalArgumentException("etaSeconds must be non-negative");
    }
    switch (eventType) {
      case "TASK_STARTED" ->
          upsertActiveAttempt(
              run, taskName, attempt, eventEta, eventTrajectoryLocation, eventTrajectorySha, now);
      case "RUN_PROGRESS" -> {
        if (taskName != null && !taskName.isBlank()) {
          upsertActiveAttempt(
              run, taskName, attempt, eventEta, eventTrajectoryLocation, eventTrajectorySha, now);
        }
        run.setLastProgressAt(now);
        if (eventEta != null && eventEta >= 0) run.setEtaSeconds(eventEta);
      }
      case "TASK_SCORED" -> {
        if (taskName != null && !taskName.isBlank()) {
          upsertTaskResult(
              runId,
              taskName,
              attempt,
              reward,
              esRunId,
              cost,
              inputTokens,
              outputTokens,
              firstNonBlank(outcome, extractField(payloadJson, "outcome")),
              firstNonBlank(reason, extractField(payloadJson, "reason")),
              failureClassification,
              eventTrajectoryLocation,
              eventTrajectorySha);
          activeAttempts.deleteByBenchmarkRunIdAndTaskNameAndAttempt(
              runId, taskName, attempt == null ? 1 : attempt);
          recompute(run);
        }
      }
      case "RUN_DONE" -> {
        recompute(run);
        run.setStatus("DONE");
        run.setFinishedAt(now);
        run.setEtaSeconds(0L);
        activeAttempts.deleteByBenchmarkRunId(runId);
        run.setActiveAttemptCount(0);
        recomputeSubmissionReadiness(run);
      }
      case "RUN_FAILED" -> {
        run.setStatus("FAILED");
        run.setFinishedAt(now);
        run.setEtaSeconds(0L);
        activeAttempts.deleteByBenchmarkRunId(runId);
        run.setActiveAttemptCount(0);
        if (message != null && !message.isBlank()) {
          run.setError(message.length() > 4000 ? message.substring(0, 4000) : message);
        }
      }
      case "SUBMISSION_STARTED" -> run.setSubmissionStatus("SUBMITTING");
      case "SUBMITTED" -> {
        run.setSubmissionStatus("SUBMITTED");
        run.setSubmissionReadiness("SUBMITTED");
        run.setSubmissionReadinessReason(null);
        String url = extractField(payloadJson, "url");
        if (url != null) {
          run.setLeaderboardUrl(url.length() > 300 ? url.substring(0, 300) : url);
        }
      }
      case "SUBMISSION_FAILED" -> {
        run.setSubmissionStatus("FAILED");
        run.setSubmissionReadiness("BLOCKED");
        run.setSubmissionReadinessReason(
            message == null || message.isBlank() ? "submission failed" : truncate(message, 500));
      }
      default -> {
        /* RUN_PROGRESS, LOG, or any future type — persisted + broadcast verbatim below. */
      }
    }

    run.setLastProgressAt(now);
    if (!"RUN_DONE".equals(eventType)
        && !"SUBMITTED".equals(eventType)
        && !"SUBMISSION_FAILED".equals(eventType)) {
      recomputeSubmissionReadiness(run);
    }

    runs.save(run);
    record(run, runId, type, message, payloadJson, taskName, reward);
    return run;
  }

  /**
   * Rebuild task and run token/cost projections from the canonical physical-attempt ledger.
   *
   * <p>Harness publishers normally include usage in {@code TASK_SCORED}. This repair path exists
   * for interrupted or historical publishers whose task result reached the tracker without usage.
   * The client cannot supply replacement numbers: every value is derived server-side from the
   * task's linked ExecuteSpec run. Reward, verifier outcome, and linkage remain immutable.
   */
  @Transactional
  public UsageReconciliation reconcileUsage(String runId) {
    BenchmarkRun run =
        runs.findByIdForUpdate(runId)
            .orElseThrow(() -> new IllegalArgumentException("benchmark run not found: " + runId));
    List<BenchmarkTaskResult> results =
        taskResults.findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(runId);
    int updated = 0;
    long calls = 0;
    for (BenchmarkTaskResult result : results) {
      if (result.getEsRunId() == null || result.getEsRunId().isBlank()) {
        continue;
      }
      List<RunStepUsage> rows = usage.findByRunIdOrderByCreatedAtAsc(result.getEsRunId());
      if (rows.isEmpty()) {
        continue;
      }
      long input = rows.stream().mapToLong(RunStepUsage::getInputTokens).sum();
      long output = rows.stream().mapToLong(RunStepUsage::getOutputTokens).sum();
      BigDecimal cost =
          rows.stream()
              .map(r -> BigDecimal.valueOf(r.getCostUsd()))
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              // benchmark_task_result.cost_usd is NUMERIC(12,6). Compare and aggregate the exact
              // persisted projection so a replay cannot oscillate on discarded sub-microdollars.
              .setScale(6, RoundingMode.HALF_UP);
      boolean changed =
          result.getInputTokens() != input
              || result.getOutputTokens() != output
              || result.getCostUsd() == null
              || result.getCostUsd().compareTo(cost) != 0;
      if (changed) {
        result.setInputTokens(input);
        result.setOutputTokens(output);
        result.setCostUsd(cost);
        taskResults.save(result);
        updated++;
      }
      calls += rows.size();
    }
    recompute(run);
    runs.save(run);
    if (updated > 0) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("source", "run_step_usage");
      payload.put("updatedTasks", updated);
      payload.put("providerCalls", calls);
      payload.put("inputTokens", run.getInputTokens());
      payload.put("outputTokens", run.getOutputTokens());
      payload.put("costUsd", run.getCostUsd());
      record(
          run,
          runId,
          "USAGE_RECONCILED",
          "Rebuilt benchmark usage from canonical provider-attempt ledger",
          json(payload),
          null,
          null);
    }
    return new UsageReconciliation(run, results.size(), updated, calls);
  }

  public record UsageReconciliation(
      BenchmarkRun run, int taskResults, int updatedTasks, long providerCalls) {}

  /** Idempotent on (run, task, attempt) — a re-published score for the same attempt is a no-op. */
  private void upsertTaskResult(
      String runId,
      String taskName,
      Integer attempt,
      Integer reward,
      String esRunId,
      BigDecimal cost,
      Long inputTokens,
      Long outputTokens,
      String outcome,
      String reason,
      String failureClassification,
      String trajectoryLocation,
      String trajectorySha256) {
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
            .outcome(truncate(outcome, 40))
            .reason(truncate(reason, 500))
            .failureClassification(classifyFailure(reward, failureClassification, outcome, reason))
            .trajectoryLocation(truncate(trajectoryLocation, 1000))
            .trajectorySha256(normalizeSha(trajectorySha256))
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
    run.setCompletedAttempts(all.size());
    run.setActiveAttemptCount((int) activeAttempts.countByBenchmarkRunId(run.getId()));
    if (run.getStatus() != null && !run.getStatus().matches("DONE|FAILED|CANCELLED")) {
      run.setEtaSeconds(estimateEtaSeconds(run));
    }
  }

  private void upsertActiveAttempt(
      BenchmarkRun run,
      String taskName,
      Integer attempt,
      Long etaSeconds,
      String trajectoryLocation,
      String trajectorySha256,
      Instant now) {
    if (taskName == null || taskName.isBlank()) {
      throw new IllegalArgumentException("TASK_STARTED requires taskName");
    }
    int att = attempt == null ? 1 : attempt;
    if (att < 1) throw new IllegalArgumentException("attempt must be positive");
    BenchmarkActiveAttempt active =
        activeAttempts
            .findByBenchmarkRunIdAndTaskNameAndAttempt(run.getId(), taskName, att)
            .orElseGet(
                () ->
                    BenchmarkActiveAttempt.builder()
                        .id(UUID.randomUUID().toString())
                        .benchmarkRunId(run.getId())
                        .taskName(taskName)
                        .attempt(att)
                        .startedAt(now)
                        .build());
    active.setLastProgressAt(now);
    active.setStaleAfter(now.plus(ACTIVE_ATTEMPT_HEARTBEAT_TTL));
    if (etaSeconds != null && etaSeconds >= 0) active.setEtaSeconds(etaSeconds);
    if (trajectoryLocation != null)
      active.setTrajectoryLocation(truncate(trajectoryLocation, 1000));
    if (trajectorySha256 != null) active.setTrajectorySha256(normalizeSha(trajectorySha256));
    activeAttempts.save(active);
    run.setActiveAttemptCount((int) activeAttempts.countByBenchmarkRunId(run.getId()));
    // Repository count may not include an unflushed insert under mocked/non-JPA stores.
    if (run.getActiveAttemptCount() == 0) run.setActiveAttemptCount(1);
    run.setLastProgressAt(now);
    if (etaSeconds != null && etaSeconds >= 0) run.setEtaSeconds(etaSeconds);
  }

  private void recomputeSubmissionReadiness(BenchmarkRun run) {
    if ("SUBMITTED".equals(run.getSubmissionReadiness())) return;
    if (!run.isSubmissionRequired()) {
      run.setSubmissionReadiness("NOT_REQUIRED");
      run.setSubmissionReadinessReason(null);
      return;
    }
    if ("FAILED".equals(run.getStatus()) || "CANCELLED".equals(run.getStatus())) {
      run.setSubmissionReadiness("BLOCKED");
      run.setSubmissionReadinessReason("benchmark ended with status " + run.getStatus());
      return;
    }
    if (!"DONE".equals(run.getStatus())) {
      run.setSubmissionReadiness("IN_PROGRESS");
      run.setSubmissionReadinessReason("benchmark has not reached DONE");
      return;
    }
    List<BenchmarkTaskResult> all =
        taskResults.findByBenchmarkRunIdOrderByTaskNameAscAttemptAsc(run.getId());
    long expected = (long) run.getTotalTasks() * Math.max(1, run.getK());
    if (expected <= 0 || all.size() != expected) {
      run.setSubmissionReadiness("BLOCKED");
      run.setSubmissionReadinessReason(
          "terminal attempts " + all.size() + "/" + Math.max(0L, expected));
      return;
    }
    long missingTrajectories =
        all.stream()
            .filter(
                t ->
                    t.getTrajectoryLocation() == null
                        || t.getTrajectoryLocation().isBlank()
                        || t.getTrajectorySha256() == null
                        || t.getTrajectorySha256().isBlank())
            .count();
    if (missingTrajectories > 0) {
      run.setSubmissionReadiness("BLOCKED");
      run.setSubmissionReadinessReason(
          missingTrajectories + " terminal attempt(s) lack trajectory location/hash");
      return;
    }
    run.setSubmissionReadiness("READY");
    run.setSubmissionReadinessReason(null);
  }

  private Long estimateEtaSeconds(BenchmarkRun run) {
    long expected = (long) run.getTotalTasks() * Math.max(1, run.getK());
    if (expected <= 0 || run.getCompletedAttempts() <= 0 || run.getStartedAt() == null) return null;
    long elapsed = run.getElapsedSeconds();
    long remaining = Math.max(0L, expected - run.getCompletedAttempts());
    return Math.max(0L, Math.round((double) elapsed * remaining / run.getCompletedAttempts()));
  }

  private String classifyFailure(Integer reward, String explicit, String outcome, String reason) {
    if (reward != null && reward > 0) return "NONE";
    String normalized = explicit == null ? "" : explicit.trim().toUpperCase();
    if (normalized.matches("TIMEOUT|INFRASTRUCTURE|VERIFIER|AGENT_OR_TEST_FAILURE|UNKNOWN")) {
      return normalized;
    }
    String evidence =
        ((outcome == null ? "" : outcome) + " " + (reason == null ? "" : reason)).toUpperCase();
    if (evidence.contains("TIMEOUT") || evidence.contains("TIMED OUT")) return "TIMEOUT";
    if (evidence.contains("INFRA")
        || evidence.contains("CONTAINER")
        || evidence.contains("BRIDGE")) {
      return "INFRASTRUCTURE";
    }
    if (evidence.contains("VERIFIER") || evidence.contains("SCORER")) return "VERIFIER";
    if (!evidence.isBlank()) return "AGENT_OR_TEST_FAILURE";
    return "UNKNOWN";
  }

  private void validateTrajectory(String location, String sha256) {
    boolean hasLocation = location != null && !location.isBlank();
    boolean hasSha = sha256 != null && !sha256.isBlank();
    if (hasLocation != hasSha) {
      throw new IllegalArgumentException(
          "trajectoryLocation and trajectorySha256 must be supplied together");
    }
    if (hasSha && !sha256.trim().matches("(?i)[0-9a-f]{64}")) {
      throw new IllegalArgumentException("trajectorySha256 must be 64 hexadecimal characters");
    }
    if (hasLocation) {
      String value = location.trim();
      // The authority is a bucket/namespace, never URL user-info. Keep ':' and '@' available only
      // after the first slash where they are ordinary object-key characters.
      boolean allowedObjectScheme =
          value.matches(
              "(?i)^(gs|s3|artifact)://[A-Za-z0-9][A-Za-z0-9._-]{0,254}/[A-Za-z0-9._/@+:-]+$");
      boolean looksLikeAnotherUri = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*$");
      boolean safeRelativePath =
          value.matches("^[A-Za-z0-9][A-Za-z0-9._/@+-]*\\.(json|jsonl|ndjson|atif|gz|zst|zip)$");
      boolean unsafe =
          value.length() > 1000
              || value.matches(".*[\\r\\n\\u0000].*")
              || value.contains("?")
              || value.contains("#")
              || value.matches("(?i).*(bearer|api[_-]?key|token=|secret=|credential=).*")
              || java.util.Arrays.stream(value.replace('\\', '/').split("/")).anyMatch(".."::equals)
              || (looksLikeAnotherUri && !allowedObjectScheme)
              || (!looksLikeAnotherUri && !safeRelativePath);
      if (unsafe) {
        throw new IllegalArgumentException(
            "trajectoryLocation must be a secret-free path or gs://, s3://, artifact:// reference");
      }
    }
  }

  private String normalizeSha(String sha256) {
    return sha256 == null ? null : sha256.trim().toLowerCase();
  }

  // ── Event persistence + SSE re-broadcast ────────────────────────────────

  private void record(
      BenchmarkRun run,
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
              ssePayload(run, type, taskName, reward, message),
              Instant.now()));
      if ("RUN_DONE".equals(type) || "RUN_FAILED".equals(type)) {
        sse.complete(runId);
      }
    } catch (Exception e) {
      // SSE is best-effort — the event is already persisted + replayable.
      log.debug("benchmark sse emit failed for {}: {}", runId, e.getMessage());
    }
  }

  private String ssePayload(
      BenchmarkRun run, String type, String taskName, Integer reward, String message) {
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
      m.put("activeAttemptCount", run.getActiveAttemptCount());
      m.put("completedAttempts", run.getCompletedAttempts());
      m.put("elapsedSeconds", run.getElapsedSeconds());
      m.put("etaSeconds", run.getEtaSeconds());
      m.put("submissionReadiness", run.getSubmissionReadiness());
      return mapper.writeValueAsString(m);
    } catch (Exception e) {
      return message == null ? "" : message;
    }
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception e) {
      log.warn("benchmark audit payload serialization failed: {}", e.getMessage());
      return "{}";
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

  private Long extractLong(String payloadJson, String field) {
    String value = extractField(payloadJson, field);
    if (value == null) return null;
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private String firstNonBlank(String first, String second) {
    if (first != null && !first.isBlank()) return first;
    if (second != null && !second.isBlank()) return second;
    return null;
  }

  private String truncate(String value, int max) {
    if (value == null) return null;
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return null;
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
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

  public List<BenchmarkActiveAttempt> activeAttempts(String id) {
    return activeAttempts.findByBenchmarkRunIdOrderByStartedAtAsc(id);
  }

  /** Live SSE feed for one run, reusing the shared broadcaster (Redis-backed + replay). */
  public SseEmitter subscribe(String id, String lastEventId) {
    return sse.subscribe(id, lastEventId);
  }
}
