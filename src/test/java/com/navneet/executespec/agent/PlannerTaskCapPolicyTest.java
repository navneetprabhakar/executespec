package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class PlannerTaskCapPolicyTest {

  @Test
  void retriesWithCoarseningFeedbackInsteadOfSilentlyDroppingTasks() {
    AtomicReference<String> feedback = new AtomicReference<>();
    Plan admitted =
        PlannerTaskCapPolicy.enforce(
            plan(18),
            16,
            message -> {
              feedback.set(message);
              return plan(16);
            });

    assertThat(admitted.tasks()).hasSize(16);
    assertThat(feedback.get()).contains("18 tasks", "hard maximum of 16", "without dropping");
  }

  @Test
  void failsClosedWhenRetryStillExceedsCap() {
    assertThatThrownBy(() -> PlannerTaskCapPolicy.enforce(plan(18), 16, ignored -> plan(17)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("17 > 16");
  }

  private static Plan plan(int count) {
    List<PlannedTask> tasks =
        java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(
                index ->
                    PlannedTask.builder()
                        .id("T" + index)
                        .title("Task " + index)
                        .description("Implement task " + index)
                        .complexity(PlannedTask.Complexity.MEDIUM)
                        .dependsOn(List.of())
                        .build())
            .toList();
    return Plan.builder()
        .planTitle("Test")
        .planSummary("Test")
        .tasks(tasks)
        .smokeTestStrategy(Plan.SmokeTestStrategy.BUILD)
        .build();
  }
}
