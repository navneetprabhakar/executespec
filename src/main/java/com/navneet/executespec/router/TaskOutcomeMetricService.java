package com.navneet.executespec.router;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.harness.TaskOutcome;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists deterministic task quality outcomes for future routing decisions.
 *
 * <p><b>Known route-truth limitation (C1, 2026-07-19).</b> {@code model_task_metric} is keyed on
 * {@code TaskOutcome.dispatchedProvider}/{@code dispatchedModel} — the REQUESTED, tier-resolved
 * route, not the physical route that served the task. When cross-provider failover or a managed-AI
 * tier lock substitutes the route, this metric accumulates under a model that did not do the work,
 * and {@link ModelProviderCandidateService} then consumes it as that model's quality prior.
 *
 * <p>The physical route is now recorded per task in {@code agent_task.effective_provider} / {@code
 * effective_model} (V305), so the corrected evidence exists. The write key here was deliberately
 * NOT switched in the same change: this table is read as a rolling window of the newest 200 rows
 * per {@code (provider, model, tier)} key, so re-keying splits history across two keys. Existing
 * rows are not migrated, so the effective-route key would start below {@code
 * execution.continuation.outcome-min-sample-tasks} (default 5) and return no prior at all for a
 * while, while the stale requested-route key keeps serving a wrong prior from its retained history.
 * That is a routing-ranking change and needs a measured sample-count check against real data plus
 * its own {@link ModelProviderCandidateService} regression pass before it lands.
 */
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
    return qualityPrior(provider, model, taskCategory, null, minSamples);
  }

  /** Returns a validation prior scoped to category and (when supplied) task complexity. */
  public Optional<QualityPrior> qualityPrior(
      String provider, String model, String taskCategory, String taskComplexity, int minSamples) {
    if (provider == null || model == null || minSamples < 1) return Optional.empty();
    try {
      List<ModelTaskMetric> candidates =
          repository
              .findTop200ByProviderIgnoreCaseAndModelNameIgnoreCaseAndTierIgnoreCaseOrderByCreatedAtDesc(
                  provider, model, normalize(taskCategory));
      String category = normalize(taskCategory);
      String complexity = normalize(taskComplexity);
      List<ModelTaskMetric> matching =
          candidates == null
              ? List.of()
              : candidates.stream()
                  .filter(metric -> metric != null)
                  .filter(
                      metric ->
                          "UNKNOWN".equals(complexity)
                              || complexity.equals(normalize(metric.getComplexity())))
                  .toList();
      if (matching.size() < minSamples) return Optional.empty();
      long passed = matching.stream().filter(ModelTaskMetric::isValidationPassed).count();
      return Optional.of(
          new QualityPrior(
              (passed * 100.0) / matching.size(), matching.size(), category, complexity));
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

  public record QualityPrior(
      double validationPassRate, int sampleSize, String taskCategory, String taskComplexity) {
    public QualityPrior(double validationPassRate, int sampleSize, String taskCategory) {
      this(validationPassRate, sampleSize, taskCategory, "UNKNOWN");
    }
  }

  private static boolean isSuccessful(TaskOutcome.Status status) {
    return status == TaskOutcome.Status.SUCCESS || status == TaskOutcome.Status.SUCCESS_AFTER_RETRY;
  }
}
