package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.RepoContext;
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
class AgentOrchestratorTurnService {

  private final AgentTurnRepository turnRepo;
  private final AgentTurnReadModel turnReadModel;
  private final AgentIntentService intentService;
  private final AgentTurnRecorder turnRecorder;
  private final AgentEventRecorder eventRecorder;
  private final AgentTracePayloadFactory tracePayloadFactory;
  private final AgentSessionRepository sessionRepo;
  private final ProjectAccessGuard projectAccessGuard;

  OrchestratorTurnResponse orchestrate(
      AgentSession session, OrchestratorTurnRequest request, AppUserDetails user) {
    String turnId = AgentText.firstNonBlank(request.turnId(), UUID.randomUUID().toString());
    var existingTurn = turnRepo.findById(turnId);
    if (existingTurn.isPresent()) {
      AgentTurn existing = existingTurn.get();
      if (!session.getId().equals(existing.getSessionId())
          || !user.getUser().getId().equals(existing.getUserId())) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "turnId already belongs elsewhere");
      }
      return turnReadModel.replayOrchestratorTurn(existing);
    }

    String projectId = AgentText.firstNonBlank(request.projectId(), session.getProjectId());
    if (projectId != null) {
      projectAccessGuard.assertCanRead(projectId, user);
    }
    OrchestratorDecision rawDecision =
        intentService.decide(
            new InterpretIntentRequest(
                session.getId(),
                AgentText.firstNonBlank(request.message(), ""),
                projectId,
                toRepoContext(request),
                toClientCapabilities(request)));
    OrchestratorDecision decision = applyRequestPolicy(rawDecision, request);
    OrchestratorTurnResponse response =
        new OrchestratorTurnResponse(
            turnId,
            decision.route(),
            decision.confidence(),
            decision.reasonCode(),
            decision.requiresUserConfirmation(),
            decision.assistantReply(),
            decision.proposedAction(),
            decision.policy(),
            "orch_trace_" + UUID.randomUUID());

    turnRecorder.record(
        session,
        turnId,
        request.message(),
        response.assistantReply(),
        response.route(),
        response.confidence(),
        response.proposedAction(),
        response.requiresUserConfirmation() ? "NEEDS_CONFIRMATION" : "OK");

    eventRecorder.append(
        session.getId(),
        turnId,
        "ORCHESTRATOR_DECISION",
        tracePayloadFactory.build(
            response,
            session,
            AgentText.firstNonBlank(request.surface(), session.getClientSurface()),
            AgentText.firstNonBlank(request.clientVersion(), ""),
            request.message(),
            false,
            null,
            "deterministic_pre_router"));
    session.setUpdatedAt(Instant.now());
    if (request.message() != null
        && !request.message().isBlank()
        && "New ExecuteSpec session".equals(session.getTitle())) {
      session.setTitle(
          request.message().length() > 80 ? request.message().substring(0, 80) : request.message());
    }
    sessionRepo.save(session);
    return response;
  }

  private static OrchestratorDecision applyRequestPolicy(
      OrchestratorDecision decision, OrchestratorTurnRequest request) {
    String permissionMode =
        request == null || request.mode() == null || request.mode().permissionMode() == null
            ? ""
            : request.mode().permissionMode().trim().toLowerCase(Locale.ROOT);
    if (!"read_only".equals(permissionMode) || !decision.policy().runApiAllowed()) {
      return decision;
    }
    return new OrchestratorDecision(
        "IMPLEMENTATION_PROPOSAL",
        decision.confidence(),
        "read_only_permission_blocks_run",
        true,
        "This looks like implementation work, but your current permission mode is read-only. I"
            + " will not create a run unless you confirm an execution mode change.",
        decision.proposedAction(),
        new OrchestratorPolicy(false, true, "Read-only permission mode blocks new run submission."),
        decision.traceId());
  }

  private static RepoContext toRepoContext(OrchestratorTurnRequest request) {
    if (request == null || request.repoContext() == null) {
      return null;
    }
    var repo = request.repoContext();
    return new RepoContext(
        AgentText.firstNonBlank(
            repo.cwdName(), AgentText.leaf(AgentText.firstNonBlank(repo.gitRoot(), request.cwd()))),
        repo.gitRemote(),
        repo.branch(),
        repo.changedFiles(),
        repo.detectedStack());
  }

  private static ClientCapabilities toClientCapabilities(OrchestratorTurnRequest request) {
    if (request == null || request.clientCapabilities() == null) {
      return new ClientCapabilities(false, false, false, false);
    }
    var caps = request.clientCapabilities();
    return new ClientCapabilities(
        caps.supportsLocalHands(),
        caps.supportsRunConfirmation() || caps.supportsPlanGate(),
        caps.supportsDiff(),
        caps.supportsStreaming());
  }
}
