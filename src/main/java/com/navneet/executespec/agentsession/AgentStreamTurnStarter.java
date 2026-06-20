package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentStreamTurnStarter {

  private final AgentIntentService intentService;
  private final AgentTurnRecorder turnRecorder;
  private final AgentEventRecorder eventRecorder;
  private final AgentTracePayloadFactory tracePayloadFactory;
  private final AgentSessionRepository sessionRepo;
  private final AiProviderPreferenceService aiProviderPreferenceService;

  AgentSessionService.StreamTurnContext begin(
      AgentSession session, String sessionId, StreamTurnRequest request, AppUserDetails user) {
    String message = request == null ? "" : request.message();
    var decision =
        intentService.decide(
            new InterpretIntentRequest(
                sessionId,
                message,
                session.getProjectId(),
                request == null ? null : request.repoContext(),
                request == null ? null : request.clientCapabilities()));
    AgentTurn turn =
        turnRecorder.record(
            session,
            null,
            message,
            decision.assistantReply(),
            decision.route(),
            decision.confidence(),
            decision.proposedAction(),
            "STREAMING");

    session.setUpdatedAt(Instant.now());
    String explicitProvider =
        request == null ? null : AgentText.blankToNull(request.providerOverride());
    String effectiveProvider =
        explicitProvider != null
            ? aiProviderPreferenceService.updatePreferredProvider(user, explicitProvider)
            : aiProviderPreferenceService.preferredProvider(user);
    if (effectiveProvider == null) {
      effectiveProvider = session.getDefaultProvider();
    }
    if (effectiveProvider != null) {
      session.setDefaultProvider(effectiveProvider);
    }
    if (message != null
        && !message.isBlank()
        && "New ExecuteSpec session".equals(session.getTitle())) {
      session.setTitle(message.length() > 80 ? message.substring(0, 80) : message);
    }
    sessionRepo.save(session);

    eventRecorder.append(
        sessionId,
        turn.getId(),
        "STREAM_STARTED",
        tracePayloadFactory.build(
            new OrchestratorTurnResponse(
                turn.getId(),
                decision.route(),
                decision.confidence(),
                decision.reasonCode(),
                decision.requiresUserConfirmation(),
                decision.assistantReply(),
                decision.proposedAction(),
                decision.policy(),
                "orch_trace_" + UUID.randomUUID()),
            session,
            session.getClientSurface(),
            "",
            message,
            false,
            null,
            "deterministic_pre_router"));

    return new AgentSessionService.StreamTurnContext(
        sessionId,
        turn.getId(),
        turn.getSequence(),
        session.getProjectId(),
        session.getRunId(),
        session.getOrgId(),
        String.valueOf(session.getUserId()),
        effectiveProvider,
        session.getDefaultModel(),
        decision);
  }
}
