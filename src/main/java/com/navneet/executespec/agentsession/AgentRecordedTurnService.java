package com.navneet.executespec.agentsession;

import java.time.Instant;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnResponse;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentRecordedTurnService {

  private final AgentIntentService intentService;
  private final AgentTurnRecorder turnRecorder;
  private final AgentEventRecorder eventRecorder;
  private final AgentSessionRepository sessionRepo;

  TurnResponse record(AgentSession session, String sessionId, TurnRequest request) {
    String message = request == null ? "" : request.message();
    InterpretIntentRequest intentRequest =
        new InterpretIntentRequest(
            sessionId,
            message,
            session.getProjectId(),
            request == null ? null : request.repoContext(),
            request == null ? null : request.clientCapabilities());
    OrchestratorDecision orchestratorDecision =
        intentService.decide(
            intentRequest, session.getDefaultProvider(), session.getDefaultModel());
    // The orchestrator decision is canonical for this turn. Re-running the classifier through
    // the legacy adapter would spend a second model call and could produce a different route,
    // causing the persisted transcript and returned decision to disagree.
    IntentDecision decision = AgentIntentLegacyAdapter.toLegacy(orchestratorDecision);

    AgentTurn turn =
        turnRecorder.record(
            session,
            null,
            message,
            AgentText.firstNonBlank(decision.response(), orchestratorDecision.assistantReply()),
            orchestratorDecision.route(),
            orchestratorDecision.confidence(),
            orchestratorDecision.proposedAction(),
            orchestratorDecision.requiresUserConfirmation() ? "NEEDS_CONFIRMATION" : "OK");

    session.setUpdatedAt(Instant.now());
    if (message != null
        && !message.isBlank()
        && "New ExecuteSpec session".equals(session.getTitle())) {
      session.setTitle(message.length() > 80 ? message.substring(0, 80) : message);
    }
    sessionRepo.save(session);

    // Reuse the persisted turn id as the compatibility action id so Web and CLI replay clients
    // can correlate the intent event without depending on client-local state.
    eventRecorder.append(sessionId, turn.getId(), turn.getId(), "INTENT", orchestratorDecision);

    return new TurnResponse(
        turn.getId(),
        sessionId,
        turn.getSequence(),
        decision,
        turn.getStatus(),
        turn.getCreatedAt());
  }
}
