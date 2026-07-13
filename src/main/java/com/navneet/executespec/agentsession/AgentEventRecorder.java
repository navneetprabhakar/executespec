package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentEventRecorder {

  private final AgentActionEventRepository eventRepo;
  private final ObjectMapper objectMapper;

  Optional<AgentActionEvent> findFirstActionResult(String sessionId, String actionId) {
    if (sessionId == null || actionId == null || actionId.isBlank()) return Optional.empty();
    return eventRepo.findFirstBySessionIdAndActionIdAndTypeOrderByCreatedAtAscIdAsc(
        sessionId, actionId, "ACTION_RESULT");
  }

  String actionResultStatus(AgentActionEvent event) {
    return readString(event, "status", "UNKNOWN");
  }

  String actionResultSummary(AgentActionEvent event) {
    return readString(event, "summary", "");
  }

  private String readString(AgentActionEvent event, String field, String fallback) {
    if (event == null || event.getPayloadJson() == null) return fallback;
    try {
      var node = objectMapper.readTree(event.getPayloadJson()).get(field);
      return node == null || node.isNull() ? fallback : node.asText(fallback);
    } catch (Exception ignored) {
      return fallback;
    }
  }

  AgentActionEvent append(String sessionId, String type, Object payload) {
    return append(sessionId, null, null, type, payload);
  }

  AgentActionEvent append(String sessionId, String turnId, String type, Object payload) {
    return append(sessionId, turnId, null, type, payload);
  }

  /**
   * Records a turn event with an explicit action correlation id. Compatibility turn recording
   * predates action events, but replay clients still need a stable cross-surface identifier.
   */
  AgentActionEvent append(
      String sessionId, String turnId, String actionId, String type, Object payload) {
    return appendInternal(sessionId, turnId, actionId, type, payload);
  }

  AgentActionEvent appendAction(String sessionId, String actionId, String type, Object payload) {
    return appendInternal(sessionId, null, actionId, type, payload);
  }

  private AgentActionEvent appendInternal(
      String sessionId, String turnId, String actionId, String type, Object payload) {
    return eventRepo.save(
        AgentActionEvent.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(sessionId)
            .turnId(turnId)
            .actionId(actionId)
            .type(type)
            .payloadJson(writeJson(payload))
            .createdAt(Instant.now())
            .build());
  }

  private String writeJson(Object value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{\"error\":\"json serialization failed\"}";
    }
  }
}
