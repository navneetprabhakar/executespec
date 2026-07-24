package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.navneet.executespec.agent.PlannedTask;

import org.junit.jupiter.api.Test;

class RunPlanningCoordinatorExpansionWavesTest {

  @Test
  void expandsDependenciesBeforeConsumersWhileKeepingIndependentTasksParallel() {
    List<List<PlannedTask>> waves =
        RunPlanningCoordinator.expansionWaves(
            List.of(
                task("T1"),
                task("T2", "T1"),
                task("T3", "T1"),
                task("T4", "T2", "T3"),
                task("T5", "UNKNOWN")));

    assertThat(ids(waves)).containsExactly(List.of("T1", "T5"), List.of("T2", "T3"), List.of("T4"));
  }

  @Test
  void rejectsACycleBeforeExpandingContradictoryConsumers() {
    assertThatThrownBy(
            () ->
                RunPlanningCoordinator.expansionWaves(
                    List.of(task("T1"), task("T2", "T3"), task("T3", "T2"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cyclic task dependencies")
        .hasMessageContaining("T2")
        .hasMessageContaining("T3");
  }

  private static List<List<String>> ids(List<List<PlannedTask>> waves) {
    return waves.stream().map(wave -> wave.stream().map(PlannedTask::id).toList()).toList();
  }

  private static PlannedTask task(String id, String... dependsOn) {
    return PlannedTask.builder()
        .id(id)
        .title(id)
        .dependsOn(List.of(dependsOn))
        .taskType(PlannedTask.TaskType.LEAF)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .build();
  }
}
