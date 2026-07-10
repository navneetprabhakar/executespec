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
 * Reconciles child-agent sessions left RUNNING when the previous JVM disappeared.
 *
 * <p>A model turn is not resumable after a process restart: the in-memory provider call and its
 * tool loop are gone. Leaving the durable child in RUNNING would nevertheless reserve its
 * descendant budget forever and make a parent appear to have an active child that can never
 * complete. Marking it CANCELLED is deliberately conservative; the parent can request a fresh,
 * bounded child after reconnect, and the durable recovery event explains why the old child ended.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentSessionStartupRecovery {

  private static final String RUNNING = "RUNNING";
  private static final String CANCELLED = "CANCELLED";

  private final AgentSessionRepository sessionRepository;
  private final AgentEventRecorder eventRecorder;

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void reconcileOnStartup() {
    reconcileRunningSessions();
  }

  /** Visible for deterministic unit/integration tests and operator recovery checks. */
  int reconcileRunningSessions() {
    List<AgentSession> sessions = sessionRepository.findByStatus(RUNNING);
    if (sessions == null || sessions.isEmpty()) return 0;
    int reconciled = 0;
    for (AgentSession session : sessions) {
      if (session == null || !RUNNING.equals(session.getStatus())) continue;
      session.setStatus(CANCELLED);
      session.setUpdatedAt(Instant.now());
      sessionRepository.save(session);
      var payload = new LinkedHashMap<String, Object>();
      payload.put("reason", "SERVER_RESTART");
      payload.put("previousStatus", RUNNING);
      payload.put("reconciledAt", session.getUpdatedAt().toString());
      eventRecorder.appendAction(
          session.getId(), "startup-recovery:" + session.getId(), "SUBAGENT_RECOVERY", payload);
      reconciled++;
    }
    log.warn(
        "Agent-session startup recovery cancelled {} stale RUNNING child session(s)", reconciled);
    return reconciled;
  }
}
