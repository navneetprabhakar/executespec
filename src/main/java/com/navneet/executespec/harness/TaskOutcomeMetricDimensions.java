package com.navneet.executespec.harness;

import java.util.Locale;

import com.navneet.executespec.agent.PlannedTask;

/** Deterministic dimensions attached to task-outcome metrics. */
final class TaskOutcomeMetricDimensions {

  private TaskOutcomeMetricDimensions() {}

  static int desiredOutputTokens(PlannedTask.Complexity complexity) {
    return switch (complexity == null ? PlannedTask.Complexity.MEDIUM : complexity) {
      case LOW -> 4096;
      case MEDIUM -> 8192;
      case HIGH -> 16384;
    };
  }

  static String language(PlannedTask task) {
    for (String file :
        task.touchesFiles() == null ? java.util.List.<String>of() : task.touchesFiles()) {
      String lower = file.toLowerCase(Locale.ROOT);
      if (lower.endsWith(".java")) return "java";
      if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
      if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
      if (lower.endsWith(".py")) return "python";
      if (lower.endsWith(".rs")) return "rust";
      if (lower.endsWith(".go")) return "go";
    }
    return null;
  }
}
