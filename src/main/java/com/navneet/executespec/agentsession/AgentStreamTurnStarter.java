package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelRegistry;
import com.navneet.executespec.specchat.ChatSecurityAuditService;
import com.navneet.executespec.specchat.ChatToolPolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
class AgentStreamTurnStarter {

  private final AgentIntentService intentService;
  private final AgentTurnRecorder turnRecorder;
  private final AgentEventRecorder eventRecorder;
  private final AgentTracePayloadFactory tracePayloadFactory;
  private final AgentSessionRepository sessionRepo;
  private final AiProviderPreferenceService aiProviderPreferenceService;
  private final ModelAccessService modelAccessService;
  private final ModelRegistry modelRegistry;
  private final ProjectAccessGuard projectAccessGuard;
  private final ChatSecurityAuditService chatSecurityAuditService;

  /** Compatibility seam for focused tests and older package-local callers. */
  AgentStreamTurnStarter(
      AgentIntentService intentService,
      AgentTurnRecorder turnRecorder,
      AgentEventRecorder eventRecorder,
      AgentTracePayloadFactory tracePayloadFactory,
      AgentSessionRepository sessionRepo,
      AiProviderPreferenceService aiProviderPreferenceService) {
    this(
        intentService,
        turnRecorder,
        eventRecorder,
        tracePayloadFactory,
        sessionRepo,
        aiProviderPreferenceService,
        null,
        null,
        null,
        null);
  }

  AgentSessionService.StreamTurnContext begin(
      AgentSession session, String sessionId, StreamTurnRequest request, AppUserDetails user) {
    if (session.getProjectId() != null && !session.getProjectId().isBlank()) {
      if (projectAccessGuard == null) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "project authorization unavailable");
      }
      // Streaming a turn can invoke the routing model and therefore spends provider tokens.
      try {
        projectAccessGuard.assertCanWrite(session.getProjectId(), user);
      } catch (RuntimeException denied) {
        if (chatSecurityAuditService != null) {
          chatSecurityAuditService.record(
              "CHAT_TURN_DENIED",
              sessionId,
              session.getProjectId(),
              user,
              "agent_stream_turn",
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
          user,
          "agent_stream_turn",
          ChatToolPolicy.Capability.SPEND_AI,
          "ALLOWED");
    }
    // Route, guard and persist the exact text the provider will execute. `request.message()` is
    // discarded by the runner whenever `conversation` is non-empty, so routing on it left
    // conversation-only content unrouted, unguarded and absent from the durable transcript.
    String message = AgentStreamTurnSupport.routedUserText(request);
    String explicitProvider =
        request == null ? null : AgentText.blankToNull(request.providerOverride());
    String explicitModel = request == null ? null : AgentText.blankToNull(request.modelOverride());
    String sessionProvider = AgentText.blankToNull(session.getDefaultProvider());
    String accountProvider = aiProviderPreferenceService.preferredProvider(user);
    String effectiveProvider =
        explicitProvider != null
            ? aiProviderPreferenceService.updatePreferredProvider(user, explicitProvider)
            : sessionProvider != null ? sessionProvider : accountProvider;
    if (explicitModel != null) {
      if (modelAccessService != null) {
        modelAccessService.assertModelAllowed(user.getUser().getId(), explicitModel);
      }
      String modelProvider =
          modelRegistry == null ? null : modelRegistry.providerFor(explicitModel);
      if (modelProvider == null || modelProvider.isBlank()) {
        throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "Unknown model: " + explicitModel);
      }
      if (effectiveProvider == null) effectiveProvider = modelProvider;
      if (modelAccessService != null) {
        modelAccessService.assertProviderModelPair(effectiveProvider, explicitModel);
      }
    } else if (explicitProvider != null
        && session.getDefaultModel() != null
        && modelAccessService != null) {
      try {
        modelAccessService.assertProviderModelPair(effectiveProvider, session.getDefaultModel());
      } catch (org.springframework.web.server.ResponseStatusException incompatibleSessionModel) {
        // A provider-only switch returns to tier routing; never retain a stale exact model from the
        // previous provider and create an impossible pair.
        session.setDefaultModel(null);
      }
    }
    if (effectiveProvider != null) {
      session.setDefaultProvider(effectiveProvider);
    }
    if (explicitModel != null) {
      session.setDefaultModel(
          modelRegistry == null ? explicitModel : modelRegistry.canonicalize(explicitModel));
    }
    AgentIntentService.RoutedDecision routed;
    try {
      routed =
          intentService.decideWithAttribution(
              new InterpretIntentRequest(
                  sessionId,
                  message,
                  session.getProjectId(),
                  request == null ? null : request.repoContext(),
                  request == null ? null : request.clientCapabilities(),
                  // A project-scoped stream turn only reaches this line after assertCanWrite above,
                  // so write authority is already proven for this actor and project.
                  session.getProjectId() == null || session.getProjectId().isBlank()
                      ? null
                      : Boolean.TRUE),
              effectiveProvider,
              session.getDefaultModel());
    } catch (org.springframework.web.server.ResponseStatusException inputDenied) {
      if (chatSecurityAuditService != null) {
        chatSecurityAuditService.record(
            "CHAT_INPUT_DENIED",
            sessionId,
            session.getProjectId(),
            user,
            "agent_stream_input",
            ChatToolPolicy.Capability.SPEND_AI,
            "DENIED");
      }
      throw inputDenied;
    }
    com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision decision =
        routed.decision();
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
    if (message != null
        && !message.isBlank()
        && "New ExecuteSpec session".equals(session.getTitle())) {
      session.setTitle(message.length() > 80 ? message.substring(0, 80) : message);
    }
    sessionRepo.save(session);

    Map<String, Object> startPayload =
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
            "deterministic_pre_router",
            routed.attribution());
    // Persist the resolved route alongside the decision. Replay clients must not infer the
    // provider/model from the current account preference after a later provider switch.
    startPayload.put("provider", effectiveProvider);
    startPayload.put("model", session.getDefaultModel());
    startPayload.put(
        "providerSelection",
        explicitProvider == null ? "session_or_account_default" : "explicit_turn_override");
    eventRecorder.append(sessionId, turn.getId(), "STREAM_STARTED", startPayload);

    return new AgentSessionService.StreamTurnContext(
        sessionId,
        turn.getId(),
        turn.getSequence(),
        session.getProjectId(),
        session.getRunId(),
        session.getOrgId(),
        String.valueOf(session.getUserId()),
        effectiveProvider,
        explicitModel == null
            ? session.getDefaultModel()
            : modelRegistry == null ? explicitModel : modelRegistry.canonicalize(explicitModel),
        decision);
  }
}
