package com.navneet.executespec.router;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
    return record(
        runId,
        complexity,
        complexity,
        language,
        attempt,
        outcome,
        actualOutputTokens,
        maxOutputTokens,
        iterationCount,
        0.0);
  }

  public ModelTaskMetric record(
      String runId,
      String taskCategory,
      String complexity,
      String language,
      int attempt,
      TaskOutcome outcome,
      int actualOutputTokens,
      int maxOutputTokens,
      int iterationCount) {
    return record(
        runId,
        taskCategory,
        complexity,
        language,
        attempt,
        outcome,
        actualOutputTokens,
        maxOutputTokens,
        iterationCount,
        0.0);
  }

  /** Records the terminal task outcome together with task-scoped spend. */
  public ModelTaskMetric record(
      String runId,
      String taskCategory,
      String complexity,
      String language,
      int attempt,
      TaskOutcome outcome,
      int actualOutputTokens,
      int maxOutputTokens,
      int iterationCount,
      double actualCostUsd) {
    if (outcome == null
        || outcome.dispatchedProvider() == null
        || outcome.dispatchedModel() == null) {
      return null;
    }
    boolean passed =
        isSuccessful(outcome.status()) && !Boolean.FALSE.equals(outcome.smokeTestPassed());
    ModelTaskMetric metric =
        ModelTaskMetric.builder()
            .runId(runId)
            .taskId(outcome.taskId())
            .modelName(outcome.dispatchedModel())
            .provider(outcome.dispatchedProvider())
            .tier(taskCategory == null ? "UNKNOWN" : taskCategory)
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
            .actualCostUsd(Math.max(0.0, actualCostUsd))
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

  /** Returns a deterministic validation-pass prior for the same task category. */
  public Optional<QualityPrior> qualityPrior(
      String provider, String model, String taskCategory, int minSamples) {
    if (provider == null || model == null || minSamples < 1) return Optional.empty();
    try {
      List<ModelTaskMetric> candidates =
          repository
              .findTop200ByProviderIgnoreCaseAndModelNameIgnoreCaseAndTierIgnoreCaseOrderByCreatedAtDesc(
                  provider, model, normalize(taskCategory));
      String category = normalize(taskCategory);
      List<ModelTaskMetric> matching =
          candidates == null
              ? List.of()
              : candidates.stream().filter(metric -> metric != null).toList();
      if (matching.size() < minSamples) return Optional.empty();
      long passed = matching.stream().filter(ModelTaskMetric::isValidationPassed).count();
      return Optional.of(
          new QualityPrior((passed * 100.0) / matching.size(), matching.size(), category));
    } catch (RuntimeException e) {
      log.warn(
          "Unable to read task outcome prior provider={} model={}: {}",
          provider,
          model,
          e.getMessage());
      return Optional.empty();
    }
  }

  private static String normalize(String value) {
    return value == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
  }

  public record QualityPrior(double validationPassRate, int sampleSize, String taskCategory) {}

  private static boolean isSuccessful(TaskOutcome.Status status) {
    return status == TaskOutcome.Status.SUCCESS || status == TaskOutcome.Status.SUCCESS_AFTER_RETRY;
  }
}
