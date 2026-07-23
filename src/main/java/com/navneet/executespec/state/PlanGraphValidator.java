package com.navneet.executespec.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.navneet.executespec.agent.model.TaskDefinition;
import com.navneet.executespec.agent.model.TaskPlan;

/**
 * Validates a planner {@link TaskPlan}'s task graph before it is persisted (RR-073).
 *
 * <p>{@code StateStore.savePlan} used to build its old→new id map and prefix dependency references
 * with no validation: a null/blank/duplicate task id silently collapsed rows, and an unresolved
 * dependency was blindly prefixed into a reference that could never resolve — persisting a plan
 * whose graph cannot execute. This validator rejects those plans up front so a corrupt graph never
 * reaches the database.
 *
 * <p>Validation runs on the planner-local ids (before run-prefixing) and is pure — it never mutates
 * the plan.
 */
public final class PlanGraphValidator {

  private PlanGraphValidator() {}

  /** Thrown when a plan's task graph is structurally invalid. */
  public static class InvalidPlanException extends IllegalArgumentException {
    public InvalidPlanException(String message) {
      super(message);
    }
  }

  /**
   * @throws InvalidPlanException if any task id is null/blank or duplicated, any {@code dependsOn}
   *     references an unknown task, or the dependency graph contains a cycle.
   */
  public static void validate(TaskPlan plan) {
    if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
      return; // nothing to persist; empty plans are handled by callers
    }
    List<TaskDefinition> tasks = plan.getTasks();

    // 1) Unique, non-null ids.
    Set<String> ids = new HashSet<>();
    for (TaskDefinition t : tasks) {
      String id = t.getTaskId();
      if (id == null || id.isBlank()) {
        throw new InvalidPlanException("plan contains a task with a null/blank taskId");
      }
      if (!ids.add(id)) {
        throw new InvalidPlanException("plan contains a duplicate taskId: " + id);
      }
    }

    // 2) Every dependency references a known task in the same plan.
    Map<String, List<String>> adjacency = new HashMap<>();
    for (TaskDefinition t : tasks) {
      List<String> deps = t.getDependsOn() != null ? t.getDependsOn() : List.of();
      for (String dep : deps) {
        if (dep == null || dep.isBlank()) {
          throw new InvalidPlanException("task " + t.getTaskId() + " has a null/blank dependency");
        }
        if (!ids.contains(dep)) {
          throw new InvalidPlanException(
              "task " + t.getTaskId() + " depends on unknown task: " + dep);
        }
      }
      adjacency.put(t.getTaskId(), new ArrayList<>(deps));
    }

    // 3) Acyclic (DFS three-colour cycle detection).
    Set<String> visiting = new HashSet<>();
    Set<String> done = new HashSet<>();
    for (String id : adjacency.keySet()) {
      if (hasCycle(id, adjacency, visiting, done)) {
        throw new InvalidPlanException("plan dependency graph contains a cycle involving: " + id);
      }
    }
  }

  private static boolean hasCycle(
      String id, Map<String, List<String>> adjacency, Set<String> visiting, Set<String> done) {
    if (done.contains(id)) return false;
    if (!visiting.add(id)) return true; // re-entered a node still on the stack
    for (String dep : adjacency.getOrDefault(id, List.of())) {
      if (hasCycle(dep, adjacency, visiting, done)) return true;
    }
    visiting.remove(id);
    done.add(id);
    return false;
  }
}
