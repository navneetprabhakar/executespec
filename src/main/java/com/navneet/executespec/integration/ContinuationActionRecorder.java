package com.navneet.executespec.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.state.entity.AgentTask;
import com.navneet.executespec.state.repository.AgentTaskRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Appends durable continuation-action evidence without owning workspace mutation or acceptance. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ContinuationActionRecorder {

  private final AgentTaskRepository taskRepository;
  private final ObjectMapper objectMapper;

  public void recordExecuted(
      AgentTask task,
      String decision,
      String provider,
      String model,
      String reason,
      String limitName,
      int usedLimit,
      int maxLimit,
      String source) {
    if (task == null || decision == null) return;
    try {
      ArrayNode actions =
          CorrectionActionJson.read(objectMapper, task.getContinuationActionsJson());
      ObjectNode action = actions.addObject();
      action.put("status", "EXECUTED");
      action.put("decision", decision);
      action.put("provider", provider);
      action.put("model", model);
      action.put("reason", reason == null ? "" : reason);
      action.put("limitName", limitName);
      action.put("usedLimit", usedLimit);
      action.put("maxLimit", maxLimit);
      action.put("source", source);
      action.put("attempt", actions.size());
      task.setContinuationActionsJson(objectMapper.writeValueAsString(actions));
      taskRepository.saveAndFlush(task);
    } catch (Exception failure) {
      log.warn(
          "continuation action history append failed for task {}: {}",
          task.getTaskId(),
          failure.getMessage());
    }
  }
}
