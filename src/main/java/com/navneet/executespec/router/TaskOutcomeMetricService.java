package com.navneet.executespec.router;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.harness.TaskOutcome;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Persists deterministic task quality outcomes for future routing decisions.
 *
 * <p>New rows are keyed to the physical successful-attempt route when the canonical usage ledger
 * proves exactly one route. Mixed-route tasks are deliberately not recorded: attributing their
 * outcome to one requested model would poison future routing quality. Legacy/test callers without a
 * route projector retain the requested-route fallback for compatibility.
 */
@Service
@Slf4j
public class TaskOutcomeMetricService {

  private final ModelTaskMetricRepository repository;
  private final RunEffectiveRouteService effectiveRoutes;

  public TaskOutcomeMetricService(ModelTaskMetricRepository repository) {
    this(repository, null);
  }

  @Autowired
  public TaskOutcomeMetricService(
      ModelTaskMetricRepository repository, RunEffectiveRouteService effectiveRoutes) {
    this.repository = repository;
    this.effectiveRoutes = effectiveRoutes;
  }

  /**
   * Sentinel stored in {@code first_error_gate} for an UNMEASURED task outcome — a worker that
   * reported success but produced no executed smoke/test evidence (RR-396). Such a row is neither a
   * validation pass nor a failure; it is excluded from the routing quality prior's numerator and
   * denominator. Distinct from every {@link TaskOutcome.Status} name, so it never collides with a
   * genuine failure gate.
   */
  static final String UNMEASURED_GATE = "SMOKE_UNMEASURED";

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
    RouteKey route = routeKey(runId, outcome);
    if (route == null) {
      log.info(
          "Skipping task outcome metric for mixed physical route run={} task={}",
          runId,
          outcome.taskId());
      return null;
    }
    // RR-396 tri-state outcome: PASSED | FAILED | UNMEASURED.
    //  - A worker that did NOT complete is a measured FAILED (a genuine negative for this model).
    //  - A completed worker with an executed smoke gate is PASSED (gate true) or FAILED (gate
    // false).
    //  - A completed worker with NO executed smoke evidence (smokeTestPassed == null) is
    // UNMEASURED:
    //    it is NOT a deterministic validation pass and must not accrue positive quality evidence.
    //    Previously null (unmeasured) was read as a pass, inflating a model's validation prior with
    //    unverified tasks. UNMEASURED rows are persisted but excluded from the prior (see
    //    qualityPrior), so they neither help nor hurt the numerator/denominator.
    boolean workerCompleted = isSuccessful(outcome.status());
    boolean unmeasured = workerCompleted && outcome.smokeTestPassed() == null;
    boolean passed = workerCompleted && Boolean.TRUE.equals(outcome.smokeTestPassed());
    String firstErrorGate;
    int errorCount;
    if (unmeasured) {
      firstErrorGate = UNMEASURED_GATE;
      errorCount = 0;
    } else if (passed) {
      firstErrorGate = null;
      errorCount = 0;
    } else {
      firstErrorGate = outcome.status().name();
      errorCount = 1;
    }
    ModelTaskMetric metric =
        ModelTaskMetric.builder()
            .runId(runId)
            .taskId(outcome.taskId())
            .modelName(route.model())
            .provider(route.provider())
            .tier(taskCategory == null ? "UNKNOWN" : taskCategory)
            .complexity(complexity == null ? "UNKNOWN" : complexity)
            .language(language)
            .attempt(attempt)
            .validationPassed(passed)
            .errorCount(errorCount)
            .firstErrorGate(firstErrorGate)
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
                  // RR-396: UNMEASURED tasks (worker success with no executed smoke/test evidence)
                  // are not deterministic validation outcomes — exclude them from BOTH the
                  // numerator
                  // and the denominator so they cannot inflate (or deflate) the pass-rate prior.
                  .filter(metric -> !UNMEASURED_GATE.equals(metric.getFirstErrorGate()))
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

  private RouteKey routeKey(String runId, TaskOutcome outcome) {
    if (effectiveRoutes == null) {
      return new RouteKey(outcome.dispatchedProvider(), outcome.dispatchedModel());
    }
    EffectiveRouteProjection effective = effectiveRoutes.forTask(runId, outcome.taskId());
    return switch (effective.attribution()) {
      case LEDGER -> new RouteKey(effective.provider(), effective.model());
      case MIXED -> null;
      case UNATTRIBUTED -> new RouteKey(outcome.dispatchedProvider(), outcome.dispatchedModel());
    };
  }

  private record RouteKey(String provider, String model) {}
}
