package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.cost.EffectiveRouteProjection;
import com.navneet.executespec.cost.RunEffectiveRouteService;
import com.navneet.executespec.harness.TaskOutcome;

import org.junit.jupiter.api.Test;

class TaskOutcomeMetricServiceTest {
  @Test
  void recordsProviderModelAndDeterministicValidationOutcome() {
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var service = new TaskOutcomeMetricService(repository);
    var outcome =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.SUCCESS)
            .smokeTestPassed(true)
            .totalLatencyMs(123)
            .dispatchedProvider("anthropic")
            .dispatchedModel("claude-sonnet-4-6")
            .build();

    var metric = service.record("run-1", "MEDIUM", "java", 1, outcome, 700, 4000, 8);

    assertThat(metric.getProvider()).isEqualTo("anthropic");
    assertThat(metric.getModelName()).isEqualTo("claude-sonnet-4-6");
    assertThat(metric.isValidationPassed()).isTrue();
    assertThat(metric.getActualOutputTokens()).isEqualTo(700);
  }

  @Test
  void attributesNewQualityEvidenceToThePhysicalLedgerRoute() {
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    RunEffectiveRouteService effectiveRoutes = mock(RunEffectiveRouteService.class);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(effectiveRoutes.forTask("run-1", "T1"))
        .thenReturn(
            new EffectiveRouteProjection(
                "openai", "gpt-5.4", EffectiveRouteProjection.Attribution.LEDGER));
    var outcome =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.SUCCESS)
            .smokeTestPassed(true)
            .dispatchedProvider("anthropic")
            .dispatchedModel("claude-sonnet-5")
            .build();

    var metric =
        new TaskOutcomeMetricService(repository, effectiveRoutes)
            .record("run-1", "BUG_FIX", "HIGH", "java", 1, outcome, 700, 4000, 8);

    assertThat(metric.getProvider()).isEqualTo("openai");
    assertThat(metric.getModelName()).isEqualTo("gpt-5.4");
  }

  @Test
  void mixedPhysicalRoutesAreNotMisattributedToTheRequestedModel() {
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    RunEffectiveRouteService effectiveRoutes = mock(RunEffectiveRouteService.class);
    when(effectiveRoutes.forTask("run-1", "T1"))
        .thenReturn(
            new EffectiveRouteProjection(null, null, EffectiveRouteProjection.Attribution.MIXED));
    var outcome =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.SUCCESS)
            .smokeTestPassed(true)
            .dispatchedProvider("anthropic")
            .dispatchedModel("claude-sonnet-5")
            .build();

    var metric =
        new TaskOutcomeMetricService(repository, effectiveRoutes)
            .record("run-1", "BUG_FIX", "HIGH", "java", 1, outcome, 700, 4000, 8);

    assertThat(metric).isNull();
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
  }

  @Test
  void successfulTaskWithoutSmokeGateIsRecordedUnmeasuredNotAsPassOrFailure() {
    // RR-396: a worker that reported success but produced no executed smoke/test evidence is
    // UNMEASURED — it must NOT be counted as a deterministic validation pass (the old behaviour),
    // and equally must not be counted as a failure. It is persisted with the UNMEASURED sentinel
    // gate and zero error count so the routing quality prior can exclude it entirely.
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var outcome =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.SUCCESS)
            .dispatchedProvider("anthropic")
            .dispatchedModel("claude-sonnet-4-6")
            .build();

    var metric =
        new TaskOutcomeMetricService(repository)
            .record("run-1", "BUG_FIX", "LOW", "java", 1, outcome, 0, 4096, 2);

    assertThat(metric.isValidationPassed()).isFalse();
    assertThat(metric.getErrorCount()).isZero();
    assertThat(metric.getFirstErrorGate()).isEqualTo(TaskOutcomeMetricService.UNMEASURED_GATE);
  }

  @Test
  void unmeasuredTasksAreExcludedFromValidationPriorNumeratorAndDenominator() {
    // RR-396: two measured passes + one UNMEASURED row must yield a 100% prior over 2 samples,
    // not 66.7% over 3 — the unmeasured task is dropped from both numerator and denominator.
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    when(repository
            .findTop200ByProviderIgnoreCaseAndModelNameIgnoreCaseAndTierIgnoreCaseOrderByCreatedAtDesc(
                "anthropic", "claude-sonnet-4-6", "BUG_FIX"))
        .thenReturn(
            List.of(metric("BUG_FIX", true), metric("BUG_FIX", true), unmeasuredMetric("BUG_FIX")));

    var prior =
        new TaskOutcomeMetricService(repository)
            .qualityPrior("anthropic", "claude-sonnet-4-6", "BUG_FIX", 2);

    assertThat(prior).isPresent();
    assertThat(prior.orElseThrow().sampleSize()).isEqualTo(2);
    assertThat(prior.orElseThrow().validationPassRate()).isEqualTo(100.0);
  }

  @Test
  void recordsObservedTaskSpendForRoutingAndAnalytics() {
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    var outcome =
        TaskOutcome.builder()
            .taskId("T1")
            .status(TaskOutcome.Status.SUCCESS)
            .smokeTestPassed(true)
            .dispatchedProvider("deepseek")
            .dispatchedModel("deepseek-v4-flash-native")
            .build();

    var metric =
        new TaskOutcomeMetricService(repository)
            .record("run-1", "BUG_FIX", "LOW", "python", 1, outcome, 100, 4096, 3, 0.0125);

    assertThat(metric.getActualCostUsd()).isEqualTo(0.0125);
  }

  @Test
  void readsCategorySpecificValidationPriorAfterMinimumSample() {
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    when(repository
            .findTop200ByProviderIgnoreCaseAndModelNameIgnoreCaseAndTierIgnoreCaseOrderByCreatedAtDesc(
                "anthropic", "claude-sonnet-4-6", "BUG_FIX"))
        .thenReturn(
            List.of(metric("BUG_FIX", true), metric("BUG_FIX", true), metric("BUG_FIX", false)));

    var prior =
        new TaskOutcomeMetricService(repository)
            .qualityPrior("anthropic", "claude-sonnet-4-6", "BUG_FIX", 3);

    assertThat(prior).isPresent();
    assertThat(prior.orElseThrow().sampleSize()).isEqualTo(3);
    assertThat(prior.orElseThrow().validationPassRate()).isEqualTo(200.0 / 3.0);
  }

  @Test
  void scopesValidationPriorToComplexityWhenRequested() {
    ModelTaskMetricRepository repository = mock(ModelTaskMetricRepository.class);
    when(repository
            .findTop200ByProviderIgnoreCaseAndModelNameIgnoreCaseAndTierIgnoreCaseOrderByCreatedAtDesc(
                "anthropic", "claude-sonnet-4-6", "BUG_FIX"))
        .thenReturn(
            List.of(
                metric("BUG_FIX", "LOW", true),
                metric("BUG_FIX", "HIGH", false),
                metric("BUG_FIX", "LOW", true)));

    var prior =
        new TaskOutcomeMetricService(repository)
            .qualityPrior("anthropic", "claude-sonnet-4-6", "BUG_FIX", "LOW", 2);

    assertThat(prior).isPresent();
    assertThat(prior.orElseThrow().sampleSize()).isEqualTo(2);
    assertThat(prior.orElseThrow().validationPassRate()).isEqualTo(100.0);
    assertThat(prior.orElseThrow().taskComplexity()).isEqualTo("LOW");
  }

  private static ModelTaskMetric metric(String category, boolean passed) {
    return metric(category, "MEDIUM", passed);
  }

  private static ModelTaskMetric unmeasuredMetric(String category) {
    return ModelTaskMetric.builder()
        .provider("anthropic")
        .modelName("claude-sonnet-4-6")
        .tier(category)
        .complexity("MEDIUM")
        .attempt(1)
        .validationPassed(false)
        .errorCount(0)
        .firstErrorGate(TaskOutcomeMetricService.UNMEASURED_GATE)
        .build();
  }

  private static ModelTaskMetric metric(String category, String complexity, boolean passed) {
    return ModelTaskMetric.builder()
        .provider("anthropic")
        .modelName("claude-sonnet-4-6")
        .tier(category)
        .complexity(complexity)
        .attempt(1)
        .validationPassed(passed)
        .build();
  }
}
