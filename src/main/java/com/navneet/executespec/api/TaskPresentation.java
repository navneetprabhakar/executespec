package com.navneet.executespec.api;

import java.util.Comparator;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.entity.AgentTask;

import org.slf4j.Logger;

/** Small, deterministic presentation rules shared by run-detail task mapping. */
final class TaskPresentation {

  static final Comparator<AgentTask> DISPLAY_ORDER =
      Comparator.comparingInt(
              (AgentTask task) ->
                  task.getPlanIndex() == null ? Integer.MAX_VALUE : task.getPlanIndex())
          .thenComparingInt(task -> statusOrder(task.getStatus()));

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

  static boolean isProvenComplete(AgentTask task, ObjectMapper objectMapper, Logger log) {
    if (!"COMPLETE".equals(task.getStatus())) return false;
    String json = task.getGoalDonenessJson();
    if (json == null || json.isBlank()) return true;
    try {
      JsonNode proof = objectMapper.readTree(json);
      String state = proof.path("state").asText("");
      return state.isBlank()
          || "DONE".equals(state)
          || RunDisplayPolicy.downstreamValidationDeferred(proof);
    } catch (Exception error) {
      log.warn(
          "Malformed goal proof cannot certify run completion: taskId={} ({})",
          task.getTaskId(),
          error.getMessage());
      return false;
    }
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    if (left == null || right == null) return left == right;
    return left.equalsIgnoreCase(right);
  }
}
