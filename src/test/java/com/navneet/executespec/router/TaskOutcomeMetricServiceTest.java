package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

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
  void successfulTaskWithoutSmokeGateIsNotRecordedAsFailure() {
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

    assertThat(metric.isValidationPassed()).isTrue();
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

  private static ModelTaskMetric metric(String category, boolean passed) {
    return ModelTaskMetric.builder()
        .provider("anthropic")
        .modelName("claude-sonnet-4-6")
        .tier(category)
        .complexity("MEDIUM")
        .attempt(1)
        .validationPassed(passed)
        .build();
  }
}
