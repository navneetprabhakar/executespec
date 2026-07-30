package com.navneet.executespec.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agent.Plan;

/** Deterministic task/dependency/file views used by the advisory wave scheduler. */
final class PlanTaskMetadata {

  private PlanTaskMetadata() {}

  static List<String> taskIds(Plan plan) {
    return plan.tasks().stream().map(task -> task.id()).toList();
  }

  static Map<String, List<String>> dependencies(Plan plan) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    plan.tasks().forEach(task -> result.put(task.id(), task.dependsOn()));
    return result;
  }

  static Map<String, List<String>> touchedFiles(Plan plan) {
    Map<String, List<String>> result = new LinkedHashMap<>();
    plan.tasks().forEach(task -> result.put(task.id(), task.touchesFiles()));
    return result;
  }
}
