package com.navneet.executespec.agentsession;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentTurnReadModel {

  private final AgentActionEventRepository eventRepo;
  private final ObjectMapper objectMapper;

  AgentTurnResponse toResponse(AgentTurn turn) {
    OrchestratorTurnResponse decision = storedOrchestratorResponse(turn.getId()).orElse(null);
    String route = decision == null ? turn.getIntentType() : decision.route();
    String reasonCode = decision == null ? null : decision.reasonCode();
    return new AgentTurnResponse(
        turn.getId(),
        turn.getSessionId(),
        turn.getSequence(),
        turn.getUserMessage(),
        turn.getAssistantSummary(),
        turn.getIntentType(),
        turn.getIntentConfidence(),
        route,
        reasonCode,
        turn.getActionType(),
        turn.getProposedActionJson(),
        turn.getLinkedRunId(),
        turn.getInputTokens(),
        turn.getOutputTokens(),
        turn.getReasoningTokens(),
        turn.getCostUsd(),
        turn.getStatus(),
        turn.getCreatedAt());
  }

  OrchestratorTurnResponse replayOrchestratorTurn(AgentTurn turn) {
    return storedOrchestratorResponse(turn.getId())
        .orElseGet(
            () ->
                new OrchestratorTurnResponse(
                    turn.getId(),
                    turn.getIntentType(),
                    turn.getIntentConfidence(),
                    "idempotent_replay_without_trace_payload",
                    "NEEDS_CONFIRMATION".equals(turn.getStatus()),
                    turn.getAssistantSummary(),
                    null,
                    new OrchestratorPolicy(false, false, "Replayed from stored turn metadata."),
                    null));
  }

  Optional<OrchestratorTurnResponse> storedOrchestratorResponse(String turnId) {
    List<AgentActionEvent> events = eventRepo.findByTurnIdOrderByCreatedAtAsc(turnId);
    if (events == null || events.isEmpty()) {
      return Optional.empty();
    }
    return events.stream()
        .filter(AgentTurnReadModel::isDecisionEvent)
        .map(this::readOrchestratorResponse)
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  OrchestratorTurnResponse readOrchestratorResponse(AgentActionEvent event) {
    try {
      JsonNode response = objectMapper.readTree(event.getPayloadJson()).path("response");
      if (response.isMissingNode() || response.isNull()) return null;
      return objectMapper.treeToValue(response, OrchestratorTurnResponse.class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean isDecisionEvent(AgentActionEvent event) {
    return "ORCHESTRATOR_DECISION".equals(event.getType())
        || "STREAM_STARTED".equals(event.getType());
  }
}
