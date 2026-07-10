package com.navneet.executespec.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
