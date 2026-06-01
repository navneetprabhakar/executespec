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
}
