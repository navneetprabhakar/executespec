package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentTurnRecorder {

  private final AgentTurnRepository turnRepo;
  private final ObjectMapper objectMapper;

  AgentTurn record(
      AgentSession session,
      String turnId,
      String message,
      String assistantSummary,
      String route,
      Double confidence,
      TypedAction proposedAction,
      String status) {
    int sequence = Math.toIntExact(turnRepo.countBySessionId(session.getId()) + 1);
    AgentTurn turn =
        AgentTurn.builder()
            .id(AgentText.firstNonBlank(turnId, UUID.randomUUID().toString()))
            .sessionId(session.getId())
            .orgId(session.getOrgId())
            .userId(session.getUserId())
            .sequence(sequence)
            .userMessage(message == null ? "" : message)
            .assistantSummary(assistantSummary)
            .intentType(route)
            .intentConfidence(confidence)
            .actionType(proposedAction == null ? null : proposedAction.type())
            .proposedActionJson(writeJson(proposedAction))
            .status(status)
            .createdAt(Instant.now())
            .build();
    return turnRepo.save(turn);
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
