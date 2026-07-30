package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionLifecycleResponse;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.subagent.SubagentProtocolService;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Non-destructive terminal lifecycle for one durable agent conversation. */
@Service
@RequiredArgsConstructor
public class AgentSessionLifecycleService {

  private static final String ACTIVE = "ACTIVE";
  private static final String ARCHIVED = "ARCHIVED";

  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentActionEventRepository eventRepository;
  private final AgentEventRecorder eventRecorder;
  private final LocalHandsStateService localHandsStateService;
  private final SubagentProtocolService subagentProtocolService;
  private final PipelineRunRepository pipelineRunRepository;

  /**
   * Archives a session without deleting its transcript, event evidence, usage, or linked run.
   *
   * <p>The session row is locked before checking in-flight work. Closure is rejected while a
   * streaming turn, client-owed local tool result, or writer reservation remains active; callers
   * can safely retry after that work reaches a terminal state. Repeating a completed archive is
   * idempotent and returns the original actor/timestamp/reason.
   */
  @Transactional
  public AgentSessionLifecycleResponse archive(
      String sessionId, String reason, AppUserDetails user) {
    AgentSession session =
        sessionRepository
            .findByIdForUpdate(sessionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found"));
    authorize(session, user);

    if (ARCHIVED.equals(session.getStatus())) {
      return response(session, true);
    }
    if (!ACTIVE.equals(session.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "session cannot be archived from state " + session.getStatus());
    }
    if (turnRepository.existsBySessionIdAndStatus(sessionId, "STREAMING")) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "session has an active streaming turn");
    }
    if (localHandsStateService.hasPendingForSession(sessionId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "session has a local tool result awaiting the client");
    }
    if (subagentProtocolService.hasActiveWriterReservation(sessionId)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "session has an active writer reservation");
    }
    if (subagentProtocolService.hasActiveChildren(sessionId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "session has an active child agent");
    }
    if (hasNonTerminalLinkedRun(session)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "session has a linked run that has not finished");
    }

    Instant archivedAt = Instant.now();
    String actor = user.getUser().getId();
    String archiveReason = normalizedReason(reason);
    long preservedTurns = turnRepository.countBySessionId(sessionId);
    long preservedEvents = eventRepository.countBySessionId(sessionId);

    session.setStatus(ARCHIVED);
    session.setArchivedAt(archivedAt);
    session.setArchivedByUserId(actor);
    session.setArchiveReason(archiveReason);
    session.setUpdatedAt(archivedAt);
    sessionRepository.save(session);

    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("actorUserId", actor);
    evidence.put("archivedAt", archivedAt.toString());
    evidence.put("finalState", ARCHIVED);
    evidence.put("reason", archiveReason);
    evidence.put("preservedTurns", preservedTurns);
    evidence.put("preservedEventsBeforeArchive", preservedEvents);
    eventRecorder.append(sessionId, "SESSION_ARCHIVED", evidence);

    return response(session, false);
  }

  private AgentSessionLifecycleResponse response(AgentSession session, boolean alreadyArchived) {
    return new AgentSessionLifecycleResponse(
        session.getId(),
        session.getStatus(),
        alreadyArchived,
        session.getArchivedAt(),
        session.getArchivedByUserId(),
        session.getArchiveReason(),
        turnRepository.countBySessionId(session.getId()),
        eventRepository.countBySessionId(session.getId()));
  }

  private boolean hasNonTerminalLinkedRun(AgentSession session) {
    String linkedRunId = session.getRunId();
    if (linkedRunId == null) {
      linkedRunId =
          turnRepository
              .findLatestLinkedRunTurnsBySessionIds(java.util.List.of(session.getId()))
              .stream()
              .findFirst()
              .map(com.navneet.executespec.agentsession.entity.AgentTurn::getLinkedRunId)
              .orElse(null);
    }
    if (linkedRunId == null) return false;
    return pipelineRunRepository
        .findById(linkedRunId)
        .map(run -> !PipelineRunRepository.TERMINAL_STATUSES.contains(run.getStatus()))
        .orElse(false);
  }

  private static void authorize(AgentSession session, AppUserDetails user) {
    if (user == null || user.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    String actor = user.getUser().getId();
    String orgId = user.getUser().getOrgId();
    if (orgId == null && user.getOrgMember() != null) {
      orgId = String.valueOf(user.getOrgMember().getOrgId());
    }
    if (!actor.equals(session.getUserId())
        || (session.getOrgId() != null && !session.getOrgId().equals(orgId))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
  }

  private static String normalizedReason(String reason) {
    if (reason == null || reason.isBlank()) return "user_archived";
    String normalized = reason.strip();
    return normalized.length() <= 240 ? normalized : normalized.substring(0, 240);
  }
}
