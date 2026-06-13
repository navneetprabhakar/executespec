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
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.entity.AgentUsageLedger;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.repository.AgentUsageLedgerRepository;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.cost.RunCostTracker;
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
  private final AgentUsageLedgerRepository usageRepo;
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
    CreateSessionRequest req =
        request == null
            ? new CreateSessionRequest(
                null, null, null, null, null, null, null, null, null, null, null)
            : request;
    String requestedId = blankToNull(req.id());
    if (requestedId != null) {
      var existing = sessionRepo.findById(requestedId);
      if (existing.isPresent()) {
        AgentSession session = existing.get();
        if (!user.getUser().getId().equals(session.getUserId())) {
          throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return toResponse(session);
      }
    }
    if (req.projectId() != null && !req.projectId().isBlank()) {
      projectAccessGuard.assertCanRead(req.projectId(), user);
    }
    Instant now = Instant.now();
    AgentSession session =
        AgentSession.builder()
            .id(firstNonBlank(requestedId, UUID.randomUUID().toString()))
            .orgId(orgId)
            .userId(user.getUser().getId())
            .projectId(blankToNull(req.projectId()))
            .repoRemote(
                blankToNull(
                    firstNonBlank(
                        req.repoRemote(),
                        req.repoContext() == null ? null : req.repoContext().gitRemote())))
            .repoRootHash(blankToNull(req.repoRootHash()))
            .clientSurface(normalize(req.surface(), "CLI"))
            .title(firstNonBlank(req.title(), "New ExecuteSpec session"))
            .defaultProvider(blankToNull(req.defaultProvider()))
            .defaultModel(blankToNull(req.defaultModel()))
            .defaultExecutionMode(normalize(req.defaultExecutionMode(), "LOCAL"))
            .approvalPolicy(normalize(req.approvalPolicy(), "PLAN"))
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

  @Transactional
  public StreamTurnContext beginStreamTurn(
      String sessionId, StreamTurnRequest request, AppUserDetails user) {
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
            .status("STREAMING")
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
            .type("STREAM_STARTED")
            .payloadJson(writeJson(decision))
            .createdAt(Instant.now())
            .build());

    return new StreamTurnContext(
        sessionId,
        turn.getId(),
        sequence,
        session.getProjectId(),
        session.getOrgId(),
        String.valueOf(session.getUserId()),
        decision);
  }

  @Transactional
  public void completeStreamTurn(
      StreamTurnContext ctx, String assistantSummary, RunCostTracker.Snapshot cost) {
    AgentTurn turn =
        turnRepo
            .findById(ctx.turnId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "turn not found"));
    turn.setAssistantSummary(firstNonBlank(assistantSummary, turn.getAssistantSummary()));
    turn.setInputTokens(cost == null ? 0 : cost.inputTokens());
    turn.setOutputTokens(cost == null ? 0 : cost.outputTokens());
    turn.setReasoningTokens(cost == null ? 0 : cost.reasoningTokens());
    turn.setCostUsd(cost == null ? 0.0 : cost.costUsd());
    turn.setStatus("OK");
    turnRepo.save(turn);

    usageRepo.save(
        AgentUsageLedger.builder()
            .id(UUID.randomUUID().toString())
            .orgId(ctx.orgId())
            .userId(ctx.userId())
            .sourceType("AGENT_TURN")
            .sourceId(ctx.turnId())
            .inputTokens(cost == null ? 0 : cost.inputTokens())
            .outputTokens(cost == null ? 0 : cost.outputTokens())
            .reasoningTokens(cost == null ? 0 : cost.reasoningTokens())
            .cacheReadTokens(cost == null ? 0 : cost.cacheReadTokens())
            .costUsd(cost == null ? 0.0 : cost.costUsd())
            .billableUnits(0)
            .billingPolicy("INCLUDED")
            .createdAt(Instant.now())
            .build());

    eventRepo.save(
        AgentActionEvent.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(ctx.sessionId())
            .turnId(ctx.turnId())
            .type("STREAM_COMPLETED")
            .payloadJson(writeJson(cost))
            .createdAt(Instant.now())
            .build());
  }

  @Transactional
  public void failStreamTurn(StreamTurnContext ctx, String message) {
    turnRepo
        .findById(ctx.turnId())
        .ifPresent(
            turn -> {
              turn.setStatus("ERROR");
              turn.setAssistantSummary(firstNonBlank(message, turn.getAssistantSummary()));
              turnRepo.save(turn);
            });
    eventRepo.save(
        AgentActionEvent.builder()
            .id(UUID.randomUUID().toString())
            .sessionId(ctx.sessionId())
            .turnId(ctx.turnId())
            .type("STREAM_FAILED")
            .payloadJson(writeJson(java.util.Map.of("message", message == null ? "" : message)))
            .createdAt(Instant.now())
            .build());
  }

  public IntentDecision interpret(InterpretIntentRequest request) {
    return intentService.interpret(request);
  }

  public ContextResolveResponse resolveContext(ContextResolveRequest request, AppUserDetails user) {
    requireOrg(user);
    String repoFullName =
        normalizeRepoFullName(
            firstNonBlank(
                request.gitRemote(),
                request.repoContext() == null ? null : request.repoContext().gitRemote()),
            request.repoName());
    if (repoFullName == null) {
      return new ContextResolveResponse(
          null, "no git remote or repository name was provided");
    }

    for (var repo : projectRepoRepo.findByGithubRepoFullNameIgnoreCase(repoFullName)) {
      for (var link : projectRepoLinkRepo.findByRepoId(repo.getId())) {
        try {
          Project project = projectAccessGuard.assertCanRead(link.getProjectId(), user);
          return new ContextResolveResponse(
              new ProjectMatch(
                  project.getId(),
                  project.getName(),
                  String.valueOf(repo.getId()),
                  repoFullName),
              "repo remote matches linked project repo " + repoFullName);
        } catch (ResponseStatusException ignored) {
          // Another org/user's link; keep looking without leaking it.
        }
      }
    }
    return new ContextResolveResponse(
        null, "no accessible project is linked to repo " + repoFullName);
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
        run.getProjectId(),
        project == null ? null : project.getName(),
        firstNonBlank(run.getRunTitle(), trim(run.getUserRequest(), 80), "Untitled run"),
        run.getStatus(),
        run.getStartedAt(),
        run.getAiProvider(),
        firstNonBlank(run.getPlannerActualModel(), run.getPlannerModel(), run.getWorkerModel()),
        run.getCostEstimateHigh(),
        run.getHardCapUsd(),
        run.getCreditsCharged(),
        inferRisk(run).toUpperCase(Locale.ROOT),
        firstNonBlank(run.getRunSummary(), trim(run.getUserRequest(), 220)),
        "Review and approve if the plan, cost, and changed areas match the requested work.",
        parseTasks(run.getPlanJson()));
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

  public record StreamTurnContext(
      String sessionId,
      String turnId,
      int sequence,
      String projectId,
      String orgId,
      String userId,
      IntentDecision decision) {}
}
