package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TaskWaveScheduler dependency waves")
class TaskWaveSchedulerTest {

  @Test
  void linear_chain_one_task_per_wave() {
    var r =
        TaskWaveScheduler.schedule(
            List.of("A", "B", "C"), Map.of("B", List.of("A"), "C", List.of("B")));
    assertThat(r.waves()).containsExactly(List.of("A"), List.of("B"), List.of("C"));
    assertThat(r.unscheduled()).isEmpty();
  }

  @Test
  void diamond_groups_independent_tasks_in_the_same_wave() {
    var r =
        TaskWaveScheduler.schedule(
            List.of("A", "B", "C", "D"),
            Map.of("B", List.of("A"), "C", List.of("A"), "D", List.of("B", "C")));
    assertThat(r.waves()).containsExactly(List.of("A"), List.of("B", "C"), List.of("D"));
  }

  @Test
  void all_independent_is_a_single_wave_in_input_order() {
    var r = TaskWaveScheduler.schedule(List.of("A", "B", "C"), Map.of());
    assertThat(r.waves()).containsExactly(List.of("A", "B", "C"));
  }

  @Test
  void cycle_is_reported_not_thrown() {
    var r =
        TaskWaveScheduler.schedule(
            List.of("A", "B", "C"), Map.of("B", List.of("C"), "C", List.of("B")));
    assertThat(r.waves()).containsExactly(List.of("A")); // A still schedules
    assertThat(r.unscheduled()).containsExactlyInAnyOrder("B", "C");
  }

  @Test
  void self_dependency_is_a_cycle() {
    var r = TaskWaveScheduler.schedule(List.of("A"), Map.of("A", List.of("A")));
    assertThat(r.unscheduled()).containsExactly("A");
    assertThat(r.waves()).isEmpty();
  }

  @Test
  void unknown_dependency_is_treated_as_satisfied_and_recorded() {
    var r = TaskWaveScheduler.schedule(List.of("A"), Map.of("A", List.of("ghost")));
    assertThat(r.waves()).containsExactly(List.of("A"));
    assertThat(r.missingDependencies()).containsExactly("ghost");
  }

  @Test
  void empty_input_yields_no_waves() {
    var r = TaskWaveScheduler.schedule(List.of(), Map.of());
    assertThat(r.waves()).isEmpty();
    assertThat(r.unscheduled()).isEmpty();
  }

  @Test
  void bounded_schedule_groups_disjoint_declared_file_sets_up_to_cap() {
    var r =
        TaskWaveScheduler.scheduleBounded(
            List.of("A", "B", "C"),
            Map.of(),
            Map.of(
                "A", List.of("src/A.java"),
                "B", List.of("src/B.java"),
                "C", List.of("src/C.java")),
            2);

    assertThat(r.waves()).containsExactly(List.of("A", "B"), List.of("C"));
    assertThat(r.serializedTasks()).isEmpty();
  }

  @Test
  void bounded_schedule_splits_same_file_mutations() {
    var r =
        TaskWaveScheduler.scheduleBounded(
            List.of("A", "B"),
            Map.of(),
            Map.of("A", List.of("src/User.java"), "B", List.of("./src/User.java")),
            4);

    assertThat(r.waves()).containsExactly(List.of("A"), List.of("B"));
  }

  @Test
  void bounded_schedule_splits_parent_directory_conflicts() {
    var r =
        TaskWaveScheduler.scheduleBounded(
            List.of("A", "B"),
            Map.of(),
            Map.of("A", List.of("src/main"), "B", List.of("src/main/java/App.java")),
            4);

    assertThat(r.waves()).containsExactly(List.of("A"), List.of("B"));
  }

  @Test
  void bounded_schedule_serializes_unknown_or_broad_touch_sets() {
    var r =
        TaskWaveScheduler.scheduleBounded(
            List.of("A", "B", "C"),
            Map.of(),
            Map.of("A", List.of("src/A.java"), "B", List.of(), "C", List.of(".")),
            4);

    assertThat(r.waves()).containsExactly(List.of("A"), List.of("B"), List.of("C"));
    assertThat(r.serializedTasks()).containsExactly("B", "C");
  }

  @Test
  void bounded_schedule_preserves_dependency_waves_before_file_splitting() {
    var r =
        TaskWaveScheduler.scheduleBounded(
            List.of("A", "B", "C"),
            Map.of("C", List.of("A", "B")),
            Map.of(
                "A", List.of("src/A.java"),
                "B", List.of("src/B.java"),
                "C", List.of("src/C.java")),
            4);

    assertThat(r.waves()).containsExactly(List.of("A", "B"), List.of("C"));
  }
}
