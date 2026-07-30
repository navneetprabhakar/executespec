package com.navneet.executespec.agentsession;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionEventRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionRecordResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentEventReplayResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionRouteRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnPage;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CapabilityTool;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.GateDecisionCard;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.cost.RunCostTracker;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgentSessionService {

  private final AgentIntentService intentService;
  private final AgentActionCatalog actionCatalog;
  private final AgentSessionReadService sessionReadService;
  private final AgentSessionCreationService sessionCreationService;
  private final AgentActionLifecycleService actionLifecycleService;
  private final AgentGateReadService gateReadService;
  private final AgentOrchestratorTurnService orchestratorTurnService;
  private final AgentRunConfirmationService runConfirmationService;
  private final AgentRecordedTurnService recordedTurnService;
  private final AgentStreamTurnStarter streamTurnStarter;
  private final AgentStreamTurnLifecycle streamTurnLifecycle;
  private final AgentContextResolver contextResolver;

  @Transactional
  public AgentSessionResponse createSession(CreateSessionRequest request, AppUserDetails user) {
    String orgId = requireOrg(user);
    return sessionReadService.toResponse(sessionCreationService.create(request, user, orgId));
  }

  public List<AgentSessionResponse> listSessions(AppUserDetails user, int limit) {
    return listSessions(user, limit, true);
  }

  public List<AgentSessionResponse> listSessions(
      AppUserDetails user, int limit, boolean includeArchived) {
    requireOrg(user);
    return sessionReadService.listSessions(user, limit, includeArchived);
  }

  public AgentSessionResponse getSession(String sessionId, AppUserDetails user) {
    requireOrg(user);
    return sessionReadService.getSession(sessionId, user);
  }

  @Transactional
  public AgentSessionResponse updateConversationRoute(
      String sessionId, AgentSessionRouteRequest request, AppUserDetails user) {
    AgentSession session = requireActiveSessionForUpdate(sessionId, user);
    AgentSessionRouteRequest route =
        request == null ? new AgentSessionRouteRequest(null, null) : request;
    return sessionReadService.toResponse(
        sessionCreationService.updateConversationRoute(
            session, route.provider(), route.model(), user));
  }

  public List<AgentActionEvent> listEvents(String sessionId, AppUserDetails user) {
    requireOrg(user);
    return sessionReadService.listEvents(sessionId, user);
  }

  public List<AgentActionEvent> listEvents(String sessionId, int limit, AppUserDetails user) {
    requireOrg(user);
    return sessionReadService.listEvents(sessionId, limit, user);
  }

  public AgentEventReplayResponse replayEvents(String sessionId, Long after, AppUserDetails user) {
    requireOrg(user);
    return sessionReadService.replayEvents(sessionId, after, user);
  }

  public List<AgentTurnResponse> listTurns(
      String sessionId, String beforeTurnId, int limit, AppUserDetails user) {
    requireOrg(user);
    return sessionReadService.listTurns(sessionId, beforeTurnId, limit, user);
  }

  public AgentTurnPage turnPage(
      String sessionId, String beforeTurnId, int limit, AppUserDetails user) {
    requireOrg(user);
    return sessionReadService.turnPage(sessionId, beforeTurnId, limit, user);
  }

  public List<CapabilityTool> capabilities(AppUserDetails user, String surface) {
    requireOrg(user);
    return actionCatalog.capabilities(surface, user.getUser().getTier());
  }

  @Transactional
  public ActionPlanResponse planAction(
      String sessionId, ActionPlanRequest request, AppUserDetails user) {
    AgentSession session = requireActiveSessionForUpdate(sessionId, user);
    ActionPlanRequest req =
        request == null ? new ActionPlanRequest("", null, null, null, null) : request;
    var planned =
        actionLifecycleService.plan(
            session,
            req,
            capabilities(user, AgentText.firstNonBlank(req.surface(), session.getClientSurface())),
            user);
    return new ActionPlanResponse(
        planned.actionId(),
        planned.sessionId(),
        planned.decision(),
        planned.capabilities(),
        planned.createdAt());
  }

  @Transactional
  public OrchestratorTurnResponse orchestrateTurn(
      OrchestratorTurnRequest request, AppUserDetails user) {
    if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
    }
    AgentSession session = requireActiveSessionForUpdate(request.sessionId(), user);
    return orchestratorTurnService.orchestrate(session, request, user);
  }

  @Transactional
  public OrchestratorRunConfirmationResponse confirmRunSubmission(
      OrchestratorRunConfirmationRequest request, AppUserDetails user) {
    return confirmRunSubmission(request, user, false);
  }

  @Transactional
  public OrchestratorRunConfirmationResponse confirmRunSubmission(
      OrchestratorRunConfirmationRequest request, AppUserDetails user, boolean patAuthenticated) {
    if (request == null
        || request.sessionId() == null
        || request.sessionId().isBlank()
        || request.turnId() == null
        || request.turnId().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "sessionId and turnId are required");
    }
    AgentSession session = requireActiveSessionForUpdate(request.sessionId(), user);
    return runConfirmationService.confirm(session, request, user, patAuthenticated);
  }

  @Transactional
  public ActionRecordResponse recordActionEvent(
      String sessionId, String actionId, ActionEventRequest request, AppUserDetails user) {
    AgentSession session = requireActiveSessionForUpdate(sessionId, user);
    return actionLifecycleService.recordEvent(session, actionId, request);
  }

  @Transactional
  public ActionRecordResponse recordActionResult(
      String sessionId, String actionId, ActionResultRequest request, AppUserDetails user) {
    AgentSession session = requireActiveSessionForUpdate(sessionId, user);
    return actionLifecycleService.recordResult(session, actionId, request);
  }

  @Transactional
  public TurnResponse recordTurn(String sessionId, TurnRequest request, AppUserDetails user) {
    AgentSession session = requireActiveSessionForUpdate(sessionId, user);
    return recordedTurnService.record(session, sessionId, request, user);
  }

  @Transactional
  public StreamTurnContext beginStreamTurn(
      String sessionId, StreamTurnRequest request, AppUserDetails user) {
    AgentSession session = requireActiveSessionForUpdate(sessionId, user);
    return streamTurnStarter.begin(session, sessionId, request, user);
  }

  @Transactional
  public void completeStreamTurn(
      StreamTurnContext ctx, String assistantSummary, RunCostTracker.Snapshot cost) {
    completeStreamTurn(ctx, assistantSummary, cost, "OK");
  }

  @Transactional
  public void completeStreamTurn(
      StreamTurnContext ctx, String assistantSummary, RunCostTracker.Snapshot cost, String status) {
    streamTurnLifecycle.complete(ctx, assistantSummary, cost, status);
  }

  @Transactional
  public void failStreamTurn(StreamTurnContext ctx, String message) {
    streamTurnLifecycle.fail(ctx, message, null);
  }

  @Transactional
  public void failStreamTurn(StreamTurnContext ctx, String message, RunCostTracker.Snapshot cost) {
    streamTurnLifecycle.fail(ctx, message, cost);
  }

  @Transactional
  public void disconnectStreamTurn(StreamTurnContext ctx) {
    streamTurnLifecycle.disconnect(ctx);
  }

  @Transactional
  public void disconnectStreamTurn(StreamTurnContext ctx, String partialSummary) {
    disconnectStreamTurn(ctx, partialSummary, null);
  }

  @Transactional
  public void disconnectStreamTurn(
      StreamTurnContext ctx, String partialSummary, RunCostTracker.Snapshot cost) {
    streamTurnLifecycle.disconnect(ctx, partialSummary, cost);
  }

  public IntentDecision interpret(InterpretIntentRequest request) {
    return intentService.interpret(request);
  }

  public ContextResolveResponse resolveContext(ContextResolveRequest request, AppUserDetails user) {
    requireOrg(user);
    return contextResolver.resolve(request, user);
  }

  public List<GateDecisionCard> listGates(AppUserDetails user, int limit) {
    requireOrg(user);
    return gateReadService.list(user, limit);
  }

  public GateDecisionCard getGate(String runId, AppUserDetails user) {
    return gateReadService.get(runId, user);
  }

  private AgentSession requireActiveSessionForUpdate(String sessionId, AppUserDetails user) {
    requireOrg(user);
    // Serialize every durable mutation with archive(). Both paths lock the same session row before
    // inspecting ACTIVE, so stale writers cannot commit new turns/events after archive.
    return requireActive(sessionReadService.requireSessionForUpdate(sessionId, user));
  }

  private AgentSession requireActive(AgentSession session) {
    if (!"ACTIVE".equals(session.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "session is " + session.getStatus().toLowerCase());
    }
    return session;
  }

  private String requireOrg(AppUserDetails user) {
    if (user == null || user.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    String orgId = user.getUser().getOrgId();
    if (orgId == null && user.getOrgMember() != null) {
      orgId = String.valueOf(user.getOrgMember().getOrgId());
    }
    if (orgId == null || orgId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "agent sessions require an org context");
    }
    return orgId;
  }

  public record StreamTurnContext(
      String sessionId,
      String turnId,
      int sequence,
      String projectId,
      String runId,
      String orgId,
      String userId,
      String provider,
      String model,
      OrchestratorDecision decision) {}
}
