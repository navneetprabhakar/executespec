package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.InputSanitizer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class AgentTurnRecorder {

  private final AgentTurnRepository turnRepo;
  private final ObjectMapper objectMapper;
  private final InputSanitizer inputSanitizer;
  private final ContextSanitizer contextSanitizer;

  /** Compatibility constructor for focused unit tests. Production uses injected sanitizers. */
  AgentTurnRecorder(AgentTurnRepository turnRepo, ObjectMapper objectMapper) {
    this(turnRepo, objectMapper, new InputSanitizer(), new ContextSanitizer());
  }

  @Autowired
  AgentTurnRecorder(
      AgentTurnRepository turnRepo,
      ObjectMapper objectMapper,
      InputSanitizer inputSanitizer,
      ContextSanitizer contextSanitizer) {
    this.turnRepo = turnRepo;
    this.objectMapper = objectMapper;
    this.inputSanitizer = inputSanitizer;
    this.contextSanitizer = contextSanitizer;
  }

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
    String safeMessage = sanitizeUserMessage(message);
    AgentTurn turn =
        AgentTurn.builder()
            .id(AgentText.firstNonBlank(turnId, UUID.randomUUID().toString()))
            .sessionId(session.getId())
            .orgId(session.getOrgId())
            .userId(session.getUserId())
            .sequence(sequence)
            .userMessage(safeMessage)
            .assistantSummary(contextSanitizer.sanitizeOutput(assistantSummary))
            .intentType(route)
            .intentConfidence(confidence)
            .actionType(proposedAction == null ? null : proposedAction.type())
            .proposedActionJson(writeJson(proposedAction))
            .status(status)
            .createdAt(Instant.now())
            .build();
    return turnRepo.save(turn);
  }

  private String sanitizeUserMessage(String message) {
    String normalized = inputSanitizer.normalize(message == null ? "" : message);
    int localContext = normalized.indexOf("EXECUTESPEC_LOCAL_CONTEXT:");
    String instruction = localContext < 0 ? normalized : normalized.substring(0, localContext);
    inputSanitizer.scan(instruction);
    if (localContext < 0) return normalized;
    return instruction
        + "\n\n"
        + com.navneet.executespec.agent.PromptTrustBoundary.wrap(
            "LOCAL_CONTEXT",
            contextSanitizer.sanitizeInput(
                normalized.substring(localContext), "agent-turn-local-context"));
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
