package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.GateDecisionCard;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ProjectMatch;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgentSessionService {

  private final AgentSessionRepository sessionRepo;
  private final AgentTurnRepository turnRepo;
  private final AgentActionEventRepository eventRepo;
  private final AgentIntentService intentService;
  private final ProjectAccessGuard projectAccessGuard;
  private final RunAccessGuard runAccessGuard;
  private final ProjectRepository projectRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final ProjectRepoLinkRepository projectRepoLinkRepo;
  private final PipelineRunRepository pipelineRunRepo;
  private final ObjectMapper objectMapper;

  @Transactional
  public AgentSessionResponse createSession(CreateSessionRequest request, AppUserDetails user) {
    String orgId = requireOrg(user);
    if (request.projectId() != null && !request.projectId().isBlank()) {
      projectAccessGuard.assertCanRead(request.projectId(), user);
    }
    Instant now = Instant.now();
    AgentSession session =
        AgentSession.builder()
            .id(UUID.randomUUID().toString())
            .orgId(orgId)
            .userId(user.getUser().getId())
            .projectId(blankToNull(request.projectId()))
            .repoRemote(blankToNull(request.repoRemote()))
            .repoRootHash(blankToNull(request.repoRootHash()))
            .clientSurface(normalize(request.clientSurface(), "CLI"))
            .title(firstNonBlank(request.title(), "New ExecuteSpec session"))
            .defaultProvider(blankToNull(request.defaultProvider()))
            .defaultModel(blankToNull(request.defaultModel()))
            .defaultExecutionMode(normalize(request.defaultExecutionMode(), "LOCAL"))
            .approvalPolicy(normalize(request.approvalPolicy(), "PLAN"))
            .createdAt(now)
            .updatedAt(now)
            .build();
    return toResponse(sessionRepo.save(session));
  }

  public List<AgentSessionResponse> listSessions(AppUserDetails user, int limit) {
    requireOrg(user);
    int capped = Math.max(1, Math.min(100, limit));
    return sessionRepo
        .findByUserIdAndStatusOrderByUpdatedAtDesc(
            user.getUser().getId(), "ACTIVE", PageRequest.of(0, capped))
        .stream()
        .map(this::toResponse)
        .toList();
  }

  public AgentSessionResponse getSession(String sessionId, AppUserDetails user) {
    return toResponse(requireSession(sessionId, user));
  }

  public List<AgentActionEvent> listEvents(String sessionId, AppUserDetails user) {
    requireSession(sessionId, user);
    return eventRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
  }

  @Transactional
  public TurnResponse recordTurn(String sessionId, TurnRequest request, AppUserDetails user) {
    AgentSession session = requireSession(sessionId, user);
    String message = request == null ? "" : request.message();
    IntentDecision decision =
        intentService.interpret(
            new InterpretIntentRequest(
                sessionId,
                message,
                session.getProjectId(),
                request == null ? null : request.repoContext(),
                request == null ? null : request.clientCapabilities()));

    int sequence = Math.toIntExact(turnRepo.countBySessionId(sessionId) + 1);
    AgentTurn turn =
        AgentTurn.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(sessionId)
            .orgId(session.getOrgId())
            .userId(session.getUserId())
            .sequence(sequence)
            .userMessage(message)
            .assistantSummary(decision.response())
            .intentType(decision.intent())
            .intentConfidence(decision.confidence())
            .actionType(decision.proposedAction() == null ? null : decision.proposedAction().type())
            .proposedActionJson(writeJson(decision.proposedAction()))
            .status(decision.needsConfirmation() ? "NEEDS_CONFIRMATION" : "OK")
            .createdAt(Instant.now())
            .build();
    turnRepo.save(turn);

    session.setUpdatedAt(Instant.now());
    if (message != null
        && !message.isBlank()
        && "New ExecuteSpec session".equals(session.getTitle())) {
      session.setTitle(message.length() > 80 ? message.substring(0, 80) : message);
    }
    sessionRepo.save(session);

    eventRepo.save(
        AgentActionEvent.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(sessionId)
            .turnId(turn.getId())
            .type("INTENT")
            .payloadJson(writeJson(decision))
            .createdAt(Instant.now())
            .build());

    return new TurnResponse(
        turn.getId(), sessionId, sequence, decision, turn.getStatus(), turn.getCreatedAt());
  }

  public IntentDecision interpret(InterpretIntentRequest request) {
    return intentService.interpret(request);
  }

  public ContextResolveResponse resolveContext(ContextResolveRequest request, AppUserDetails user) {
    requireOrg(user);
    String repoFullName = normalizeRepoFullName(request.gitRemote(), request.repoName());
    if (repoFullName == null) {
      return new ContextResolveResponse(
          null,
          List.of("link_existing_project", "create_project_from_repo", "continue_local_only"));
    }

    for (var repo : projectRepoRepo.findByGithubRepoFullNameIgnoreCase(repoFullName)) {
      for (var link : projectRepoLinkRepo.findByRepoId(repo.getId())) {
        try {
          Project project = projectAccessGuard.assertCanRead(link.getProjectId(), user);
          return new ContextResolveResponse(
              new ProjectMatch(
                  project.getId(),
                  project.getName(),
                  0.94,
                  "repo remote matches linked project repo " + repoFullName),
              List.of());
        } catch (ResponseStatusException ignored) {
          // Another org/user's link; keep looking without leaking it.
        }
      }
    }
    return new ContextResolveResponse(
        null, List.of("link_existing_project", "create_project_from_repo", "continue_local_only"));
  }

  public List<GateDecisionCard> listGates(AppUserDetails user, int limit) {
    requireOrg(user);
    int capped = Math.max(1, Math.min(50, limit));
    return pipelineRunRepo
        .findByCreatedByUserIdAndStatusOrderByStartedAtDesc(
            user.getUser().getId(), "AWAITING_APPROVAL", PageRequest.of(0, capped))
        .stream()
        .map(this::toGateCard)
        .toList();
  }

  public GateDecisionCard getGate(String runId, AppUserDetails user) {
    PipelineRun run = runAccessGuard.requireRunAccess(runId, user);
    if (!"AWAITING_APPROVAL".equals(run.getStatus())) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "run is not awaiting approval");
    }
    return toGateCard(run);
  }

  private AgentSession requireSession(String sessionId, AppUserDetails user) {
    requireOrg(user);
    AgentSession session =
        sessionRepo
            .findById(sessionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    if (!user.getUser().getId().equals(session.getUserId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
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

  private AgentSessionResponse toResponse(AgentSession s) {
    return new AgentSessionResponse(
        s.getId(),
        s.getProjectId(),
        s.getRepoRemote(),
        s.getClientSurface(),
        s.getTitle(),
        s.getStatus(),
        s.getDefaultProvider(),
        s.getDefaultModel(),
        s.getDefaultExecutionMode(),
        s.getApprovalPolicy(),
        s.getCreatedAt(),
        s.getUpdatedAt());
  }

  private GateDecisionCard toGateCard(PipelineRun run) {
    Project project =
        run.getProjectId() == null ? null : projectRepo.findById(run.getProjectId()).orElse(null);
    return new GateDecisionCard(
        run.getRunId(),
        firstNonBlank(run.getRunTitle(), trim(run.getUserRequest(), 80), "Untitled run"),
        run.getProjectId(),
        project == null ? null : project.getName(),
        run.getStatus(),
        run.getAiProvider(),
        firstNonBlank(run.getPlannerActualModel(), run.getPlannerModel(), run.getWorkerModel()),
        run.getCostEstimateHigh(),
        run.getHardCapUsd(),
        inferRisk(run),
        firstNonBlank(run.getRunSummary(), trim(run.getUserRequest(), 220)),
        parseTasks(run.getPlanJson()),
        run.getCreatedByUserId(),
        run.getStartedAt());
  }

  private List<String> parseTasks(String planJson) {
    if (planJson == null || planJson.isBlank()) return List.of();
    try {
      JsonNode root = objectMapper.readTree(planJson);
      JsonNode tasks = root.path("tasks");
      if (!tasks.isArray()) tasks = root.path("plan").path("tasks");
      if (!tasks.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      for (JsonNode t : tasks) {
        String title = firstNonBlank(t.path("title").asText(null), t.path("name").asText(null));
        if (title != null) out.add(title);
        if (out.size() >= 8) break;
      }
      return out;
    } catch (Exception ignored) {
      return List.of();
    }
  }

  private String inferRisk(PipelineRun run) {
    int tasks = parseTasks(run.getPlanJson()).size();
    if (tasks >= 6) return "high";
    if (tasks >= 3) return "medium";
    return "low";
  }

  private String writeJson(Object value) {
    if (value == null) return null;
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return "{\"error\":\"json serialization failed\"}";
    }
  }

  private static String normalizeRepoFullName(String remote, String repoName) {
    String candidate = firstNonBlank(remote, repoName);
    if (candidate == null) return null;
    String s = candidate.trim();
    if (s.endsWith(".git")) s = s.substring(0, s.length() - 4);
    int github = s.indexOf("github.com");
    if (github >= 0) {
      s = s.substring(github + "github.com".length());
    }
    s = s.replace(':', '/');
    while (s.startsWith("/")) s = s.substring(1);
    String[] parts = s.split("/");
    if (parts.length < 2) return null;
    return (parts[parts.length - 2] + "/" + parts[parts.length - 1]).toLowerCase(Locale.ROOT);
  }

  private static String normalize(String value, String fallback) {
    String v = blankToNull(value);
    return v == null ? fallback : v.trim().toUpperCase(Locale.ROOT);
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  private static String trim(String v, int max) {
    if (v == null) return null;
    return v.length() <= max ? v : v.substring(0, max - 1) + "…";
  }
}
