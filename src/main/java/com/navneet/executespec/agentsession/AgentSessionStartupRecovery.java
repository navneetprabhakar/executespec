package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Reconciles child-agent sessions left READY or RUNNING when the previous JVM disappeared.
 *
 * <p>A model turn is not resumable after a process restart: the in-memory provider call and its
 * tool loop are gone. Leaving the durable child in READY or RUNNING would nevertheless reserve its
 * descendant budget forever and make a parent appear to have an active child that can never
 * complete. Marking it CANCELLED is deliberately conservative; the parent can request a fresh,
 * bounded child after reconnect, and the durable recovery event explains why the old child ended.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentSessionStartupRecovery {

  private static final String RUNNING = "RUNNING";
  private static final String READY = "READY";
  private static final String SUBAGENT = "SUBAGENT";
  private static final String CANCELLED = "CANCELLED";
  private static final long STALE_AFTER_SECONDS = 120;

  private final AgentSessionRepository sessionRepository;
  private final AgentEventRecorder eventRecorder;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void reconcileOnStartup() {
    reconcileRunningSessions();
  }

  /** Visible for deterministic unit/integration tests and operator recovery checks. */
  int reconcileRunningSessions() {
    return reconcileStatus(RUNNING) + reconcileStatus(READY);
  }

  private int reconcileStatus(String status) {
    // The agent_session table also stores parent/web conversations.  Only the SUBAGENT surface
    // has READY/RUNNING lifecycle rows that this boot-time reconciler is authorised to cancel.
    List<AgentSession> sessions = sessionRepository.findByStatusAndClientSurface(status, SUBAGENT);
    if (sessions == null || sessions.isEmpty()) return 0;
    int reconciled = 0;
    Instant staleBefore = Instant.now().minusSeconds(STALE_AFTER_SECONDS);
    for (AgentSession session : sessions) {
      if (session == null || !status.equals(session.getStatus())) continue;
      // Rolling restarts can execute this hook concurrently. Re-read each candidate under a row
      // lock so a peer's terminal transition wins and cannot be overwritten by this stale snapshot.
      AgentSession locked = sessionRepository.findByIdForUpdate(session.getId()).orElse(null);
      if (locked == null || !status.equals(locked.getStatus())) continue;
      session = locked;
      // A delayed ApplicationReadyEvent must not cancel a session that this JVM (or a peer) is
      // still actively updating. Older rows may have no heartbeat timestamp; those remain
      // fail-closed and are treated as stale so they cannot reserve budget forever.
      Instant updatedAt = session.getUpdatedAt();
      if (updatedAt != null && updatedAt.isAfter(staleBefore)) continue;
      session.setStatus(CANCELLED);
      session.setUpdatedAt(Instant.now());
      sessionRepository.save(session);
      var payload = new LinkedHashMap<String, Object>();
      payload.put("reason", "SERVER_RESTART");
      payload.put("previousStatus", status);
      payload.put("reconciledAt", session.getUpdatedAt().toString());
      eventRecorder.appendAction(
          session.getId(), "startup-recovery:" + session.getId(), "SUBAGENT_RECOVERY", payload);
      reconciled++;
    }
    log.warn(
        "Agent-session startup recovery cancelled {} stale {} child session(s)",
        reconciled,
        status);
    return reconciled;
  }
}
