package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentRunConfirmationService {

  private final AgentTurnRepository turnRepo;
  private final AgentTurnReadModel turnReadModel;
  private final AgentEventRecorder eventRecorder;
  private final AgentTracePayloadFactory tracePayloadFactory;
  private final AgentSessionRepository sessionRepo;
  private final ProjectAccessGuard projectAccessGuard;
  private final AgentRunSubmissionGate runSubmissionGate;

  OrchestratorRunConfirmationResponse confirm(
      AgentSession session, OrchestratorRunConfirmationRequest request, AppUserDetails user) {
    return confirm(session, request, user, false);
  }

  OrchestratorRunConfirmationResponse confirm(
      AgentSession session,
      OrchestratorRunConfirmationRequest request,
      AppUserDetails user,
      boolean patAuthenticated) {
    AgentTurn turn =
        turnRepo
            .findById(request.turnId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found"));
    if (!session.getId().equals(turn.getSessionId())
        || !user.getUser().getId().equals(turn.getUserId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
    if (turn.getLinkedRunId() != null && !turn.getLinkedRunId().isBlank()) {
      return new OrchestratorRunConfirmationResponse(
          session.getId(),
          turn.getId(),
          turn.getLinkedRunId(),
          turn.getIntentType(),
          false,
          "orch_trace_" + UUID.randomUUID(),
          "ALREADY_SUBMITTED");
    }

    OrchestratorTurnResponse decision =
        turnReadModel
            .storedOrchestratorResponse(turn.getId())
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "stored orchestrator decision is missing"));
    if (!"CODE_RUN_REQUIRED".equals(decision.route())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored orchestrator decision is not a run route");
    }
    if (!decision.policy().runApiAllowed()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "stored orchestrator policy does not allow run submission");
    }

    String projectId =
        decision.proposedAction() == null
            ? session.getProjectId()
            : AgentText.firstNonBlank(
                AgentActionArguments.stringArg(decision.proposedAction().arguments(), "projectId"),
                session.getProjectId());
    if (projectId != null) {
      projectAccessGuard.assertCanWrite(projectId, user);
    }

    AgentRunSubmissionGate.Submission submission =
        runSubmissionGate.submitConfirmed(session, turn, decision, request, user, patAuthenticated);
    turn.setLinkedRunId(submission.runId());
    turn.setStatus("RUN_SUBMITTED");
    turnRepo.save(turn);

    String traceId = "orch_trace_" + UUID.randomUUID();
    OrchestratorRunConfirmationResponse response =
        new OrchestratorRunConfirmationResponse(
            session.getId(),
            turn.getId(),
            submission.runId(),
            decision.route(),
            submission.calledRunApi(),
            traceId,
            submission.status());
    Map<String, Object> payload =
        tracePayloadFactory.build(
            decision,
            session,
            session.getClientSurface(),
            "",
            turn.getUserMessage(),
            submission.calledRunApi(),
            submission.runId(),
            "confirmed_run_submission_gate");
    payload.put("confirmationResponse", response);
    eventRecorder.append(session.getId(), turn.getId(), "ORCHESTRATOR_RUN_SUBMITTED", payload);
    session.setUpdatedAt(Instant.now());
    sessionRepo.save(session);
    return response;
  }
}
