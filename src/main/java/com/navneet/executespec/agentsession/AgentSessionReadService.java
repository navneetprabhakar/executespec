package com.navneet.executespec.agentsession;

import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

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

  List<AgentSessionResponse> listSessions(AppUserDetails user, int limit) {
    int capped = Math.max(1, Math.min(100, limit));
    return sessionRepo
        .findByUserIdAndStatusOrderByUpdatedAtDesc(
            user.getUser().getId(), "ACTIVE", PageRequest.of(0, capped))
        .stream()
        .map(this::toResponse)
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
        s.getCreatedAt(),
        s.getUpdatedAt());
  }
}
