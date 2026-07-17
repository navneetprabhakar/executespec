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
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.specchat.ChatSecurityAuditService;
import com.navneet.executespec.specchat.ChatToolPolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class AgentRecordedTurnService {

  private final AgentIntentService intentService;
  private final AgentTurnRecorder turnRecorder;
  private final AgentEventRecorder eventRecorder;
  private final AgentSessionRepository sessionRepo;
  private final ProjectAccessGuard projectAccessGuard;
  private final ChatSecurityAuditService chatSecurityAuditService;

  AgentRecordedTurnService(
      AgentIntentService intentService,
      AgentTurnRecorder turnRecorder,
      AgentEventRecorder eventRecorder,
      AgentSessionRepository sessionRepo) {
    this(intentService, turnRecorder, eventRecorder, sessionRepo, null, null);
  }

  @Autowired
  AgentRecordedTurnService(
      AgentIntentService intentService,
      AgentTurnRecorder turnRecorder,
      AgentEventRecorder eventRecorder,
      AgentSessionRepository sessionRepo,
      ProjectAccessGuard projectAccessGuard,
      ChatSecurityAuditService chatSecurityAuditService) {
    this.intentService = intentService;
    this.turnRecorder = turnRecorder;
    this.eventRecorder = eventRecorder;
    this.sessionRepo = sessionRepo;
    this.projectAccessGuard = projectAccessGuard;
    this.chatSecurityAuditService = chatSecurityAuditService;
  }

  TurnResponse record(AgentSession session, String sessionId, TurnRequest request) {
    return record(session, sessionId, request, null);
  }

  TurnResponse record(
      AgentSession session, String sessionId, TurnRequest request, AppUserDetails principal) {
    if (session.getProjectId() != null && !session.getProjectId().isBlank() && principal != null) {
      if (projectAccessGuard == null) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "project authorization unavailable");
      }
      try {
        projectAccessGuard.assertCanWrite(session.getProjectId(), principal);
      } catch (RuntimeException denied) {
        if (chatSecurityAuditService != null) {
          chatSecurityAuditService.record(
              "CHAT_TURN_DENIED",
              sessionId,
              session.getProjectId(),
              principal,
              "agent_recorded_turn",
              ChatToolPolicy.Capability.SPEND_AI,
              "DENIED");
        }
        throw denied;
      }
      if (chatSecurityAuditService == null) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "chat security audit unavailable");
      }
      chatSecurityAuditService.record(
          "CHAT_TURN_ALLOWED",
          sessionId,
          session.getProjectId(),
          principal,
          "agent_recorded_turn",
          ChatToolPolicy.Capability.SPEND_AI,
          "ALLOWED");
    }
    String message = request == null ? "" : request.message();
    InterpretIntentRequest intentRequest =
        new InterpretIntentRequest(
            sessionId,
            message,
            session.getProjectId(),
            request == null ? null : request.repoContext(),
            request == null ? null : request.clientCapabilities());
    OrchestratorDecision orchestratorDecision;
    try {
      orchestratorDecision =
          intentService.decide(
              intentRequest, session.getDefaultProvider(), session.getDefaultModel());
    } catch (org.springframework.web.server.ResponseStatusException inputDenied) {
      if (chatSecurityAuditService != null && principal != null) {
        chatSecurityAuditService.record(
            "CHAT_INPUT_DENIED",
            sessionId,
            session.getProjectId(),
            principal,
            "agent_recorded_input",
            ChatToolPolicy.Capability.SPEND_AI,
            "DENIED");
      }
      throw inputDenied;
    }
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
