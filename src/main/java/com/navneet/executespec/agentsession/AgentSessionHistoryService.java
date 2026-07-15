package com.navneet.executespec.agentsession;

import java.time.Instant;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Destructive, explicit reset of the durable transcript for one agent session. */
@Service
@RequiredArgsConstructor
public class AgentSessionHistoryService {

  private final AgentSessionReadService sessionReadService;
  private final AgentSessionRepository sessionRepository;
  private final AgentTurnRepository turnRepository;
  private final AgentActionEventRepository eventRepository;

  @Transactional
  HistoryClearedResponse clear(String sessionId, AppUserDetails user) {
    AgentSession session = sessionReadService.requireSession(sessionId, user);
    long turns = turnRepository.countBySessionId(sessionId);
    long events = eventRepository.countBySessionId(sessionId);

    // Events are removed first because some installations have a turn_id cascade while others
    // retain legacy rows without that relationship. Usage ledger rows intentionally remain as an
    // immutable billing/audit record; clearing a transcript must not erase spend evidence.
    eventRepository.deleteBySessionId(sessionId);
    turnRepository.deleteBySessionId(sessionId);
    session.setUpdatedAt(Instant.now());
    sessionRepository.save(session);
    return new HistoryClearedResponse(sessionId, turns, events, session.getUpdatedAt());
  }

  public record HistoryClearedResponse(
      String sessionId, long turnsDeleted, long eventsDeleted, Instant clearedAt) {}
}
