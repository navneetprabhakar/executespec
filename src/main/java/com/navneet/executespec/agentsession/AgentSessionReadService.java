package com.navneet.executespec.agentsession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class AgentSessionReadService {

  private final AgentSessionRepository sessionRepo;
  private final AgentTurnRepository turnRepo;
  private final AgentActionEventRepository eventRepo;
  private final AgentTurnReadModel turnReadModel;
  private final PipelineRunRepository pipelineRunRepo;

  List<AgentSessionResponse> listSessions(AppUserDetails user, int limit) {
    int capped = Math.max(1, Math.min(100, limit));
    List<AgentSession> sessions =
        sessionRepo.findByUserIdAndStatusOrderByUpdatedAtDesc(
            user.getUser().getId(), "ACTIVE", PageRequest.of(0, capped));
    Map<String, PipelineRun> latestRuns = latestLinkedRunsBySessionId(sessions);
    return sessions.stream()
        .map(session -> toResponse(session, latestRuns.get(session.getId())))
        .toList();
  }

  AgentSessionResponse getSession(String sessionId, AppUserDetails user) {
    return toResponse(requireSession(sessionId, user));
  }

  List<AgentActionEvent> listEvents(String sessionId, AppUserDetails user) {
    requireSession(sessionId, user);
    return eventRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
  }

  List<AgentTurnResponse> listTurns(
      String sessionId, String beforeTurnId, int limit, AppUserDetails user) {
    requireSession(sessionId, user);
    int capped = Math.max(1, Math.min(100, limit));
    List<AgentTurn> turns = new ArrayList<>(turnRepo.findBySessionIdOrderBySequenceAsc(sessionId));
    if (beforeTurnId != null && !beforeTurnId.isBlank()) {
      Integer beforeSequence =
          turns.stream()
              .filter(t -> beforeTurnId.equals(t.getId()))
              .map(AgentTurn::getSequence)
              .findFirst()
              .orElse(null);
      if (beforeSequence != null) {
        turns = turns.stream().filter(t -> t.getSequence() < beforeSequence).toList();
      }
    }
    if (turns.size() > capped) {
      turns = turns.subList(turns.size() - capped, turns.size());
    }
    return turns.stream().map(turnReadModel::toResponse).toList();
  }

  AgentSession requireSession(String sessionId, AppUserDetails user) {
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

  AgentSessionResponse toResponse(AgentSession s) {
    return toResponse(s, null);
  }

  private AgentSessionResponse toResponse(AgentSession s, PipelineRun latestRun) {
    String prUrl =
        latestRun == null
            ? null
            : AgentText.firstNonBlank(latestRun.getScmPrUrl(), latestRun.getGithubPrUrl());
    return new AgentSessionResponse(
        s.getId(),
        s.getProjectId(),
        s.getRunId(),
        s.getRepoRemote(),
        s.getClientSurface(),
        s.getTitle(),
        s.getStatus(),
        s.getDefaultProvider(),
        s.getDefaultModel(),
        s.getDefaultExecutionMode(),
        s.getApprovalPolicy(),
        latestRun == null ? null : latestRun.getRunId(),
        latestRun == null ? null : latestRun.getStatus(),
        latestRun == null ? null : AgentText.firstNonBlank(latestRun.getRunTitle(), s.getTitle()),
        latestRun == null ? null : latestRun.getStartedAt(),
        latestRun == null ? null : latestRun.getCompletedAt(),
        prUrl,
        s.getCreatedAt(),
        s.getUpdatedAt());
  }

  private Map<String, PipelineRun> latestLinkedRunsBySessionId(List<AgentSession> sessions) {
    if (sessions == null || sessions.isEmpty()) {
      return Map.of();
    }
    List<String> sessionIds = sessions.stream().map(AgentSession::getId).toList();
    List<AgentTurn> linkedTurns = turnRepo.findLatestLinkedRunTurnsBySessionIds(sessionIds);
    if (linkedTurns.isEmpty()) {
      return Map.of();
    }
    List<String> runIds =
        linkedTurns.stream()
            .map(AgentTurn::getLinkedRunId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    Map<String, PipelineRun> runsById =
        pipelineRunRepo.findAllById(runIds).stream()
            .collect(Collectors.toMap(PipelineRun::getRunId, Function.identity()));
    Map<String, PipelineRun> bySession = new HashMap<>();
    for (AgentTurn turn : linkedTurns) {
      PipelineRun run = runsById.get(turn.getLinkedRunId());
      if (run != null) {
        bySession.put(turn.getSessionId(), run);
      }
    }
    return bySession;
  }
}
