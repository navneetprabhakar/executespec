package com.navneet.executespec.router;

import java.time.Instant;

import com.navneet.executespec.harness.TaskOutcome;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Persists deterministic task quality outcomes for future routing decisions. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TaskOutcomeMetricService {

  private final ModelTaskMetricRepository repository;

  public ModelTaskMetric record(
      String runId,
      String complexity,
      String language,
      int attempt,
      TaskOutcome outcome,
      int actualOutputTokens,
      int maxOutputTokens,
      int iterationCount) {
    if (outcome == null
        || outcome.dispatchedProvider() == null
        || outcome.dispatchedModel() == null) {
      return null;
    }
    boolean passed =
        Boolean.TRUE.equals(outcome.smokeTestPassed()) && isSuccessful(outcome.status());
    ModelTaskMetric metric =
        ModelTaskMetric.builder()
            .runId(runId)
            .taskId(outcome.taskId())
            .modelName(outcome.dispatchedModel())
            .provider(outcome.dispatchedProvider())
            .tier(complexity == null ? "UNKNOWN" : complexity)
            .complexity(complexity == null ? "UNKNOWN" : complexity)
            .language(language)
            .attempt(attempt)
            .validationPassed(passed)
            .errorCount(passed ? 0 : 1)
            .firstErrorGate(passed ? null : outcome.status().name())
            .durationMs(outcome.totalLatencyMs())
            .maxOutputTokens(maxOutputTokens)
            .actualOutputTokens(actualOutputTokens)
            .iterationCount(iterationCount)
            .createdAt(Instant.now())
            .build();
    try {
      return repository.save(metric);
    } catch (RuntimeException e) {
      log.warn(
          "Unable to persist task outcome metric run={} task={}: {}",
          runId,
          outcome.taskId(),
          e.getMessage());
      return null;
    }
  }

  private static boolean isSuccessful(TaskOutcome.Status status) {
    return status == TaskOutcome.Status.SUCCESS || status == TaskOutcome.Status.SUCCESS_AFTER_RETRY;
  }
}
