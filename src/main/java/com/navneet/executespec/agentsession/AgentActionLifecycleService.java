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
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentActionLifecycleService {

  private final AgentIntentService intentService;
  private final AgentEventRecorder eventRecorder;
  private final AgentSessionRepository sessionRepo;
  private final ProjectAccessGuard projectAccessGuard;

  PlannedAction plan(
      AgentSession session,
      ActionPlanRequest request,
      List<CapabilityTool> capabilities,
      AppUserDetails user) {
    ActionPlanRequest req =
        request == null ? new ActionPlanRequest("", null, null, null, null) : request;
    String projectId = AgentText.firstNonBlank(req.projectId(), session.getProjectId());
    InterpretIntentRequest intentRequest =
        new InterpretIntentRequest(
            session.getId(),
            req.message(),
            projectId,
            req.repoContext(),
            req.clientCapabilities(),
            AgentWriteAuthority.resolve(projectAccessGuard, projectId, user));
    OrchestratorDecision orchestratorDecision =
        intentService.decide(
            intentRequest, session.getDefaultProvider(), session.getDefaultModel());
    // Reuse the provider-aware canonical decision; re-running interpret would spend a second
    // classifier call and would fall back to the legacy Anthropic route.
    IntentDecision decision = AgentIntentLegacyAdapter.toLegacy(orchestratorDecision);
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
    // A browser disconnect followed by a CLI attach can replay the same terminal action result.
    // Return the first durable result instead of appending a second terminal row. The database
    // partial unique index (V272) is the cross-JVM fence; this read makes the common retry path
    // deterministic and keeps the response identical across surfaces.
    var existing = eventRecorder.findFirstActionResult(session.getId(), actionId);
    if (existing.isPresent()) {
      String existingStatus = eventRecorder.actionResultStatus(existing.get());
      String existingSummary = eventRecorder.actionResultSummary(existing.get());
      return new ActionRecordResponse(
          actionId,
          session.getId(),
          existingStatus,
          existingSummary,
          existing.get().getCreatedAt());
    }
    String status =
        request == null || request.status() == null || request.status().isBlank()
            ? "UNKNOWN"
            : request.status().toUpperCase(Locale.ROOT);
    String summary = request == null ? "" : AgentText.firstNonBlank(request.summary(), "");
    String runId = request == null ? "" : AgentText.firstNonBlank(request.runId(), "");
    try {
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
    } catch (AgentEventRecorder.PayloadSerializationException unserializable) {
      // RR-888 — the terminal ACTION_RESULT slot was NOT consumed (no row was inserted). Surface a
      // retryable recording error instead of poisoning the first-writer slot, so a corrected retry
      // can record the real outcome.
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          "action result payload could not be recorded; retry with a serializable result",
          unserializable);
    } catch (DataIntegrityViolationException race) {
      // Another JVM can win the partial unique index between our read and insert. Resolve the
      // expected retry to that committed first result instead of leaking a 500 to the reconnecting
      // client. If the row is unavailable, preserve the original database failure.
      var winner = eventRecorder.findFirstActionResult(session.getId(), actionId);
      if (winner.isPresent()) {
        return duplicateResult(session, actionId, winner.get());
      }
      throw race;
    }
    session.setUpdatedAt(now);
    sessionRepo.save(session);
    return new ActionRecordResponse(
        actionId, session.getId(), status, request == null ? null : request.summary(), now);
  }

  private ActionRecordResponse duplicateResult(
      AgentSession session,
      String actionId,
      com.navneet.executespec.agentsession.entity.AgentActionEvent event) {
    return new ActionRecordResponse(
        actionId,
        session.getId(),
        eventRecorder.actionResultStatus(event),
        eventRecorder.actionResultSummary(event),
        event.getCreatedAt());
  }

  record PlannedAction(
      String actionId,
      String sessionId,
      IntentDecision decision,
      List<CapabilityTool> capabilities,
      Instant createdAt) {}
}
