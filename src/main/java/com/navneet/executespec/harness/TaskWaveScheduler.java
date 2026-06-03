package com.navneet.executespec.harness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure scheduler that groups plan tasks into <b>execution waves</b> by their {@code dependsOn}
 * graph (Kahn topological levels): wave 0 = tasks with no dependencies, wave 1 = tasks whose deps
 * are all in wave 0, and so on. Every task within a wave is independent of every other task in that
 * same wave, so a wave can be executed concurrently.
 *
 * <p>This is the reusable, Spring-free, fully-unit-testable core of the parallel-execution
 * re-introduction (RL-2026-05-006, latency Phase 1). It performs <b>no</b> execution and has no
 * knowledge of workers/tools — {@code SequentialExecutor} maps wave ids back to tasks and decides
 * concurrency. Keeping scheduling pure means the (subtle) topological + cycle logic is provable in
 * isolation, separate from the (unprovable-without-a-live-run) workspace-race concerns of actually
 * running tasks concurrently.
 *
 * <p>Determinism: input order is preserved within each wave, so the schedule is stable.
 *
 * <p>Safety: a dependency cycle does not throw — the cyclic tasks are returned separately in {@link
 * Result#unscheduled} so the caller can fall back to sequential execution for them (or fail the
 * plan) rather than silently dropping work. A {@code dependsOn} id that isn't a known task is
 * treated as already-satisfied (you can't wait on something the plan never produces) and recorded
 * in {@link Result#missingDependencies} for visibility.
 */
public final class TaskWaveScheduler {

  private TaskWaveScheduler() {}

  /**
   * @param waves ordered execution waves; tasks within a wave are mutually independent
   * @param unscheduled tasks that could not be scheduled because they participate in a dependency
   *     cycle (caller should run these sequentially or reject the plan)
   * @param missingDependencies {@code dependsOn} references that point at unknown task ids
   */
  public record Result(
      List<List<String>> waves, List<String> unscheduled, List<String> missingDependencies) {}

  /**
   * Group {@code taskIds} into dependency waves.
   *
   * @param taskIds all task ids, in their natural (plan) order
   * @param dependsOn map of task id → the ids it depends on (must complete first); ids absent from
   *     the map are treated as having no dependencies
   */
  public static Result schedule(List<String> taskIds, Map<String, List<String>> dependsOn) {
    List<List<String>> waves = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    if (taskIds == null || taskIds.isEmpty()) {
      return new Result(waves, new ArrayList<>(), missing);
    }
    Set<String> known = new LinkedHashSet<>(taskIds);

    // Build remaining-dependency sets, counting only deps that are real tasks (others are
    // already-satisfied; recorded as missing for visibility). Self-deps are kept so they surface
    // as a cycle rather than silently scheduling.
    Map<String, Set<String>> remainingDeps = new LinkedHashMap<>();
    for (String id : taskIds) {
      Set<String> deps = new LinkedHashSet<>();
      List<String> declared = dependsOn == null ? null : dependsOn.get(id);
      if (declared != null) {
        for (String d : declared) {
          if (d == null || d.isBlank()) continue;
          if (known.contains(d)) {
            deps.add(d);
          } else if (!missing.contains(d)) {
            missing.add(d);
          }
        }
      }
      remainingDeps.put(id, deps);
    }

    Set<String> done = new LinkedHashSet<>();
    while (done.size() < known.size()) {
      List<String> wave = new ArrayList<>();
      for (String id : taskIds) { // preserve input order -> deterministic
        if (done.contains(id)) continue;
        if (done.containsAll(remainingDeps.get(id))) {
          wave.add(id);
        }
      }
      if (wave.isEmpty()) {
        break; // nothing became ready -> the rest is a cycle
      }
      waves.add(wave);
      done.addAll(wave);
    }

    List<String> unscheduled = new ArrayList<>();
    for (String id : taskIds) {
      if (!done.contains(id)) unscheduled.add(id);
    }
    return new Result(waves, unscheduled, missing);
  }
}
