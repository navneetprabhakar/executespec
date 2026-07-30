package com.navneet.executespec.agentsession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentEventReplayResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnPage;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.SessionSnapshot;
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

  private static final int LEGACY_SNAPSHOT_PAGE_SIZE = 500;
  private static final int CURSOR_REPLAY_PAGE_SIZE = 500;

  private final AgentSessionRepository sessionRepo;
  private final AgentTurnRepository turnRepo;
  private final AgentActionEventRepository eventRepo;
  private final AgentTurnReadModel turnReadModel;
  private final PipelineRunRepository pipelineRunRepo;

  List<AgentSessionResponse> listSessions(AppUserDetails user, int limit) {
    return listSessions(user, limit, false);
  }

  List<AgentSessionResponse> listSessions(AppUserDetails user, int limit, boolean includeArchived) {
    int capped = Math.max(1, Math.min(100, limit));
    String userOrgId = user.getUser().getOrgId();
    // RR-1025 — the base query is (user, status) only, so after an org move/revocation a user could
    // still list prior-org session titles/status/PR URLs. Restrict the result to the caller's
    // current effective tenant before any linked-run projection is loaded.
    List<AgentSession> sessions =
        (includeArchived
                ? sessionRepo.findByUserIdAndStatusInOrderByUpdatedAtDesc(
                    user.getUser().getId(),
                    List.of("ACTIVE", "ARCHIVED"),
                    PageRequest.of(0, capped))
                : sessionRepo.findByUserIdAndStatusOrderByUpdatedAtDesc(
                    user.getUser().getId(), "ACTIVE", PageRequest.of(0, capped)))
            .stream().filter(session -> belongsToTenant(session, userOrgId)).toList();
    Map<String, PipelineRun> latestRuns = latestLinkedRunsBySessionId(sessions);
    return sessions.stream()
        .map(session -> toResponse(session, latestRuns.get(session.getId())))
        .toList();
  }

  AgentSessionResponse getSession(String sessionId, AppUserDetails user) {
    return toResponse(requireSession(sessionId, user));
  }

  List<AgentActionEvent> listEvents(String sessionId, AppUserDetails user) {
    return listEvents(sessionId, 500, user);
  }

  List<AgentActionEvent> listEvents(String sessionId, int limit, AppUserDetails user) {
    requireSession(sessionId, user);
    int capped = Math.max(1, Math.min(500, limit));
    // Compatibility route remains chronological; new clients use /events/replay for cursors.
    return eventRepo.findBySessionIdOrderByCreatedAtAsc(sessionId, PageRequest.of(0, capped));
  }

  AgentEventReplayResponse replayEvents(String sessionId, Long after, AppUserDetails user) {
    AgentSession session = requireSession(sessionId, user);
    boolean snapshot = after == null || after < 0;
    List<AgentActionEvent> events;
    boolean deltaTruncated = false;
    if (snapshot) {
      events =
          eventRepo.findBySessionIdOrderByStreamSequenceAsc(
              sessionId, PageRequest.of(0, LEGACY_SNAPSHOT_PAGE_SIZE));
    } else {
      // Fetch one sentinel row beyond the response ceiling so a stale reconnect cursor cannot
      // materialise an unbounded long-lived session and the client still gets an exact next cursor.
      events =
          eventRepo.findBySessionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc(
              sessionId, after, PageRequest.of(0, CURSOR_REPLAY_PAGE_SIZE + 1));
      deltaTruncated = events.size() > CURSOR_REPLAY_PAGE_SIZE;
      if (deltaTruncated) {
        events = List.copyOf(events.subList(0, CURSOR_REPLAY_PAGE_SIZE));
      }
    }
    long last = after == null || after < 0 ? 0 : after;
    if (after != null && after >= 0) {
      // Keep the cursor contract defensive even if a replica/query implementation returns a stale
      // row. Legacy null-sequence rows remain visible for compatibility, but a durable sequence
      // at or below the caller's cursor must never be replayed as a new event.
      events =
          events.stream()
              .filter(
                  event ->
                      event != null
                          && (event.getStreamSequence() == null
                              || event.getStreamSequence() > after))
              .toList();
    }
    // V262 makes stream_sequence non-null for new rows, but tolerate a legacy/corrupt row rather
    // than turning an authenticated reconnect into a 500. Null-sequence events remain visible in
    // the replay payload; the cursor advances only to the greatest durable sequence available.
    for (AgentActionEvent event : events) {
      if (event.getStreamSequence() != null) {
        last = Math.max(last, event.getStreamSequence());
      }
    }
    boolean legacyEventsOmitted =
        !snapshot && eventRepo.countBySessionIdAndStreamSequenceIsNull(sessionId) > 0;
    boolean truncated = deltaTruncated;
    boolean hasMore = deltaTruncated;
    if (snapshot) {
      truncated = eventRepo.countBySessionId(sessionId) > LEGACY_SNAPSHOT_PAGE_SIZE;
      hasMore = truncated;
    }
    if (legacyEventsOmitted) {
      // A cursor cannot establish a complete ordering across pre-V262 rows. Return an explicit
      // full snapshot instead of letting a client treat an incomplete delta as authoritative.
      long totalEvents = eventRepo.countBySessionId(sessionId);
      events =
          eventRepo.findBySessionIdOrderByStreamSequenceAsc(
              sessionId, PageRequest.of(0, LEGACY_SNAPSHOT_PAGE_SIZE));
      snapshot = true;
      legacyEventsOmitted = false;
      truncated = totalEvents > LEGACY_SNAPSHOT_PAGE_SIZE;
      hasMore = truncated;
      last = 0;
      for (AgentActionEvent event : events) {
        if (event.getStreamSequence() != null) {
          last = Math.max(last, event.getStreamSequence());
        }
      }
    }
    Long nextSequence =
        hasMore
                && events.stream()
                    .map(AgentActionEvent::getStreamSequence)
                    .filter(java.util.Objects::nonNull)
                    .max(Long::compareTo)
                    .isPresent()
            ? events.stream()
                .map(AgentActionEvent::getStreamSequence)
                .filter(java.util.Objects::nonNull)
                .max(Long::compareTo)
                .orElse(null)
            : null;
    return new AgentEventReplayResponse(
        events,
        last,
        snapshot,
        snapshot
            ? new SessionSnapshot(
                session.getId(), session.getStatus(), session.getTitle(), session.getUpdatedAt())
            : null,
        legacyEventsOmitted,
        truncated,
        hasMore,
        nextSequence);
  }

  List<AgentTurnResponse> listTurns(
      String sessionId, String beforeTurnId, int limit, AppUserDetails user) {
    requireSession(sessionId, user);
    int capped = Math.max(1, Math.min(100, limit));
    List<AgentTurn> turns;
    if (beforeTurnId != null && !beforeTurnId.isBlank()) {
      Integer beforeSequence =
          turnRepo
              .findById(beforeTurnId)
              .filter(turn -> sessionId.equals(turn.getSessionId()))
              .map(AgentTurn::getSequence)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_REQUEST, "turn cursor is not in this session"));
      turns =
          turnRepo.findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
              sessionId, beforeSequence, PageRequest.of(0, capped));
    } else {
      turns = turnRepo.findBySessionIdOrderBySequenceDesc(sessionId, PageRequest.of(0, capped));
    }
    return turns.reversed().stream().map(turnReadModel::toResponse).toList();
  }

  AgentTurnPage turnPage(String sessionId, String beforeTurnId, int limit, AppUserDetails user) {
    AgentSession session = requireSession(sessionId, user);
    List<AgentTurnResponse> turns = listTurns(sessionId, beforeTurnId, limit, user);
    long total = turnRepo.countBySessionId(sessionId);
    String nextBeforeTurnId = turns.isEmpty() ? null : turns.getFirst().turnId();
    int oldestSequence =
        turns.isEmpty() ? 0 : turns.stream().mapToInt(AgentTurnResponse::sequence).min().orElse(0);
    boolean hasMore = oldestSequence > 1;
    return new AgentTurnPage(
        turns,
        total,
        hasMore,
        hasMore ? nextBeforeTurnId : null,
        session.getStatus(),
        "ARCHIVED".equals(session.getStatus()),
        session.getArchivedAt());
  }

  AgentSession requireSession(String sessionId, AppUserDetails user) {
    return authorize(
        sessionRepo
            .findById(sessionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")),
        user);
  }

  AgentSession requireSessionForUpdate(String sessionId, AppUserDetails user) {
    return authorize(
        sessionRepo
            .findByIdForUpdate(sessionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found")),
        user);
  }

  private AgentSession authorize(AgentSession session, AppUserDetails user) {
    if (!user.getUser().getId().equals(session.getUserId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
    if (!belongsToTenant(session, user.getUser().getOrgId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
    }
    return session;
  }

  /**
   * RR-1025 — one effective-tenant rule shared by list, detail and idempotency replay. A session
   * with no org is a legacy pre-org row that the owner check already covers. Otherwise the caller's
   * current org must be present and match; a null caller org (org moved/revoked) no longer skips
   * the comparison and silently authorizes prior-org access.
   */
  private static boolean belongsToTenant(AgentSession session, String userOrgId) {
    if (session.getOrgId() == null) {
      return true;
    }
    return userOrgId != null && userOrgId.equals(session.getOrgId());
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
        s.getUpdatedAt(),
        s.getArchivedAt(),
        s.getArchivedByUserId(),
        s.getArchiveReason());
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
