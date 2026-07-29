package com.navneet.executespec.api;

import java.util.Objects;

import com.navneet.executespec.state.entity.AgentTask;

/** Small, deterministic presentation rules shared by run-detail task mapping. */
final class TaskPresentation {

  private TaskPresentation() {}

  static int statusOrder(String status) {
    return switch (status != null ? status : "") {
      case "WORKING" -> 0;
      case "PENDING", "LOCKED" -> 1;
      case "PARTIAL", "BLOCKED" -> 2;
      case "FAILED" -> 3;
      case "COMPLETE" -> 4;
      default -> 5;
    };
  }

  static boolean routeDiverged(AgentTask task) {
    if ("MIXED".equals(task.getEffectiveRouteAttribution())) return true;
    if (!"LEDGER".equals(task.getEffectiveRouteAttribution())) return false;
    return !equalsIgnoreCase(task.getProvider(), task.getEffectiveProvider())
        || !Objects.equals(task.getModel(), task.getEffectiveModel());
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    if (left == null || right == null) return left == right;
    return left.equalsIgnoreCase(right);
  }
}
