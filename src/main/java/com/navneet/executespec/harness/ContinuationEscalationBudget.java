package com.navneet.executespec.harness;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * RR-1563 — focused support for the continuation-escalation spend ceiling, extracted from {@link
 * SequentialExecutor} (a god-class-allowlisted file) so its size stays bounded.
 *
 * <p>Reconstructs the ALREADY-CONSUMED provider-switch / model-step-up / model-step-down counts
 * from durable {@code agent_task.continuation_actions_json} (V252) so a crash-recovery resume does
 * not restore a fresh full escalation budget.
 */
@Slf4j
final class ContinuationEscalationBudget {

  private ContinuationEscalationBudget() {}

  /**
   * Consumed escalation counts, keyed by the SHORT planner task id for per-task counters; the
   * provider-switch count is per-run. The caller applies these with {@code putIfAbsent} so an
   * explicit approval-path seed still wins.
   */
  record Consumed(
      int providerSwitches,
      Map<String, Integer> stepUpsByTask,
      Map<String, Integer> stepDownsByTask,
      Map<String, Integer> sameProviderRetriesByTask) {
    static Consumed empty() {
      return new Consumed(0, Map.of(), Map.of(), Map.of());
    }
  }

  /** RR-1563 — a continuation action that actually reserved a ceiling slot. */
  static boolean isConsumedStatus(String status) {
    return "RESERVED".equals(status) || "EXECUTED".equals(status) || "APPROVED".equals(status);
  }

  /**
   * RR-1563 — reconstruct the consumed counters from durable per-task history. Best-effort: any
   * read/parse failure blocks recovery rather than granting a new escalation budget.
   */
  static Consumed reconstruct(
      AgentTaskRepository taskRepo, ObjectMapper objectMapper, String runId) {
    if (taskRepo == null || objectMapper == null || runId == null || runId.length() < 8) {
      return Consumed.empty();
    }
    try {
      String prefix = runId.substring(0, 8) + "_";
      int providerSwitches = 0;
      Map<String, Integer> stepUps = new HashMap<>();
      Map<String, Integer> stepDowns = new HashMap<>();
      Map<String, Integer> sameRoute = new HashMap<>();
      for (var task : taskRepo.findByRunId(runId)) {
        String composite = task.getTaskId();
        String shortId =
            composite != null && composite.startsWith(prefix)
                ? composite.substring(prefix.length())
                : composite;
        int up = 0;
        int down = 0;
        int retries = 0;
        for (JsonNode action : readActions(objectMapper, task.getContinuationActionsJson())) {
          if (!isConsumedStatus(action.path("status").asText(""))) {
            continue;
          }
          switch (action.path("decision").asText("")) {
            case "CHANGE_PROVIDER" -> providerSwitches++;
            case "STEP_UP_MODEL" -> up++;
            case "STEP_DOWN_MODEL" -> down++;
            case "CONTINUE_SAME_MODEL_PROVIDER" -> retries++;
            default -> {
              // non-escalation decision — no ceiling to seed.
            }
          }
        }
        if (up > 0 && shortId != null) stepUps.put(shortId, up);
        if (down > 0 && shortId != null) stepDowns.put(shortId, down);
        if (retries > 0 && shortId != null) sameRoute.put(shortId, retries);
      }
      return new Consumed(providerSwitches, stepUps, stepDowns, sameRoute);
    } catch (Exception ex) {
      log.warn(
          "reconstruct consumed continuation counters failed run={}: {}", runId, ex.getMessage());
      throw new IllegalStateException("CONTINUATION_BUDGET_EVIDENCE_UNAVAILABLE", ex);
    }
  }

  private static Iterable<JsonNode> readActions(ObjectMapper objectMapper, String json) {
    if (json == null || json.isBlank()) {
      return objectMapper.createArrayNode();
    }
    try {
      JsonNode parsed = objectMapper.readTree(json);
      if (!parsed.isArray())
        throw new IllegalStateException("CONTINUATION_ACTION_HISTORY_MALFORMED");
      return parsed;
    } catch (Exception ex) {
      throw new IllegalStateException("CONTINUATION_ACTION_HISTORY_MALFORMED", ex);
    }
  }
}
