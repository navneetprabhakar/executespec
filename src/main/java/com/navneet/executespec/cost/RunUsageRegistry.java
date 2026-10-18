package com.navneet.executespec.cost;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Keeps terminal usage available to bounded asynchronous work without resetting its spend floor.
 */
final class RunUsageRegistry<T> {
  private final Map<String, T> runs = new HashMap<>();
  private final Map<String, Map<String, T>> tasks = new HashMap<>();
  private final Map<String, Instant> retired = new HashMap<>();

  synchronized T recordRun(String id, Supplier<T> create) {
    retired.computeIfPresent(id, (key, previous) -> Instant.now());
    return runs.computeIfAbsent(id, key -> create.get());
  }

  synchronized T recordTask(String runId, String taskId, Supplier<T> create) {
    retired.computeIfPresent(runId, (key, previous) -> Instant.now());
    return tasks
        .computeIfAbsent(runId, key -> new HashMap<>())
        .computeIfAbsent(taskId, key -> create.get());
  }

  synchronized T run(String id) {
    return runs.get(id);
  }

  synchronized T task(String runId, String taskId) {
    var byTask = tasks.get(runId);
    return byTask == null ? null : byTask.get(taskId);
  }

  synchronized T retire(String id) {
    if (runs.containsKey(id)) retired.put(id, Instant.now());
    return runs.get(id);
  }

  synchronized void cleanBefore(Instant cutoff) {
    retired
        .entrySet()
        .removeIf(
            entry -> {
              if (!entry.getValue().isBefore(cutoff)) return false;
              runs.remove(entry.getKey());
              tasks.remove(entry.getKey());
              return true;
            });
  }
}
