package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionEventRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionRecordResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CapabilityTool;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentActionLifecycleService {

  private final AgentIntentService intentService;
  private final AgentEventRecorder eventRecorder;
  private final AgentSessionRepository sessionRepo;

  PlannedAction plan(
      AgentSession session, ActionPlanRequest request, List<CapabilityTool> capabilities) {
    ActionPlanRequest req =
        request == null ? new ActionPlanRequest("", null, null, null, null) : request;
    InterpretIntentRequest intentRequest =
        new InterpretIntentRequest(
            session.getId(),
            req.message(),
            AgentText.firstNonBlank(req.projectId(), session.getProjectId()),
            req.repoContext(),
            req.clientCapabilities());
    OrchestratorDecision orchestratorDecision = intentService.decide(intentRequest);
    IntentDecision decision = intentService.interpret(intentRequest);
    String actionId = UUID.randomUUID().toString();
    Instant now = Instant.now();
    eventRecorder.appendAction(
        session.getId(),
        actionId,
        "ACTION_PLANNED",
        Map.of(
            "actionId", actionId,
            "surface", AgentText.firstNonBlank(req.surface(), session.getClientSurface()),
            "decision", decision,
            "orchestratorDecision", orchestratorDecision));
    session.setUpdatedAt(now);
    sessionRepo.save(session);
    return new PlannedAction(actionId, session.getId(), decision, capabilities, now);
  }

  ActionRecordResponse recordEvent(
      AgentSession session, String actionId, ActionEventRequest request) {
    Instant now = Instant.now();
    String type =
        request == null || request.type() == null || request.type().isBlank()
            ? "ACTION_EVENT"
            : request.type();
    eventRecorder.appendAction(
        session.getId(),
        actionId,
        type,
        Map.of(
            "actionId",
            actionId,
            "payload",
            request == null || request.payload() == null ? Map.of() : request.payload()));
    session.setUpdatedAt(now);
    sessionRepo.save(session);
    return new ActionRecordResponse(actionId, session.getId(), type, null, now);
  }

  ActionRecordResponse recordResult(
      AgentSession session, String actionId, ActionResultRequest request) {
    Instant now = Instant.now();
    String status =
        request == null || request.status() == null || request.status().isBlank()
            ? "UNKNOWN"
            : request.status().toUpperCase(Locale.ROOT);
    String summary = request == null ? "" : AgentText.firstNonBlank(request.summary(), "");
    String runId = request == null ? "" : AgentText.firstNonBlank(request.runId(), "");
    eventRecorder.appendAction(
        session.getId(),
        actionId,
        "ACTION_RESULT",
        Map.of(
            "actionId", actionId,
            "status", status,
            "summary", summary == null ? "" : summary,
            "runId", runId == null ? "" : runId,
            "touchedFiles",
                request == null || request.touchedFiles() == null
                    ? List.of()
                    : request.touchedFiles(),
            "verification",
                request == null || request.verification() == null
                    ? Map.of()
                    : request.verification()));
    session.setUpdatedAt(now);
    sessionRepo.save(session);
    return new ActionRecordResponse(
        actionId, session.getId(), status, request == null ? null : request.summary(), now);
  }

  record PlannedAction(
      String actionId,
      String sessionId,
      IntentDecision decision,
      List<CapabilityTool> capabilities,
      Instant createdAt) {}
}
