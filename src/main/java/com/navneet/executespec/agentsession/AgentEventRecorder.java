package com.navneet.executespec.agentsession;

import java.time.Instant;
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

  AgentActionEvent append(String sessionId, String type, Object payload) {
    return append(sessionId, null, null, type, payload);
  }

  AgentActionEvent append(String sessionId, String turnId, String type, Object payload) {
    return append(sessionId, turnId, null, type, payload);
  }

  AgentActionEvent appendAction(String sessionId, String actionId, String type, Object payload) {
    return append(sessionId, null, actionId, type, payload);
  }

  private AgentActionEvent append(
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
