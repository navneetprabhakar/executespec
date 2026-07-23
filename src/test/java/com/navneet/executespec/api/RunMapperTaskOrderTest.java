package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.state.entity.AgentTask;

import org.junit.jupiter.api.Test;

/**
 * RR-1446 — the approval gate must render the planner's task sequence, not the unspecified database
 * row order. Before persisting plan_index, every task is PENDING at the gate so the status-only
 * sort was a no-op and the displayed order was whatever the DB returned. These tests shuffle the
 * DB-order input and prove {@link RunMapper#TASK_DISPLAY_ORDER} restores the planner order.
 */
class RunMapperTaskOrderTest {

  private static AgentTask task(String id, Integer planIndex, String status) {
    return AgentTask.builder().taskId(id).planIndex(planIndex).status(status).build();
  }

  private static List<String> orderedIds(List<AgentTask> shuffledDbRows) {
    List<AgentTask> copy = new ArrayList<>(shuffledDbRows);
    copy.sort(RunMapper.TASK_DISPLAY_ORDER);
    return copy.stream().map(AgentTask::getTaskId).toList();
  }

  @Test
  void ordersByPlanIndexRegardlessOfShuffledDbRows() {
    // All PENDING (the plan-gate state) — status ordering is a no-op, plan_index decides.
    List<AgentTask> dbRows =
        List.of(
            task("c", 2, "PENDING"),
            task("a", 0, "PENDING"),
            task("d", 3, "PENDING"),
            task("b", 1, "PENDING"));

    assertThat(orderedIds(dbRows)).containsExactly("a", "b", "c", "d");
  }

  @Test
  void planIndexBeatsStatusDecoration() {
    // Status would put COMPLETE last and WORKING first; plan_index must win so the sequence still
    // reflects planner intent.
    List<AgentTask> dbRows =
        List.of(
            task("second", 1, "COMPLETE"), task("third", 2, "WORKING"), task("first", 0, "FAILED"));

    assertThat(orderedIds(dbRows)).containsExactly("first", "second", "third");
  }

  @Test
  void legacyNullPlanIndexRowsSortAfterIndexedTasksAndFallBackToStatus() {
    // Legacy rows (no plan_index) sink below every indexed task; among themselves status decides.
    List<AgentTask> dbRows =
        List.of(
            task("legacyComplete", null, "COMPLETE"),
            task("indexed1", 1, "PENDING"),
            task("legacyWorking", null, "WORKING"),
            task("indexed0", 0, "PENDING"));

    assertThat(orderedIds(dbRows))
        .containsExactly("indexed0", "indexed1", "legacyWorking", "legacyComplete");
  }
}
