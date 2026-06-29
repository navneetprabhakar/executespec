package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.report.ValidationReportEvidenceEvent;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
class AgentValidationReportEventBridge {

  private final AgentSessionRepository sessionRepo;
  private final AgentEventRecorder eventRecorder;

  @EventListener
  void onValidationReportEvidence(ValidationReportEvidenceEvent event) {
    if (event == null || event.runId() == null || event.runId().isBlank()) {
      return;
    }
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(event.runId(), "ACTIVE");
      if (sessions == null || sessions.isEmpty()) {
        return;
      }
      for (AgentSession session : sessions) {
        eventRecorder.append(session.getId(), "RUN_VALIDATION_EVIDENCE", payload(event));
        session.setUpdatedAt(Instant.now());
        sessionRepo.save(session);
      }
    } catch (RuntimeException ex) {
      log.debug(
          "Failed to bridge validation evidence for run {} into agent sessions: {}",
          event.runId(),
          ex.getMessage());
    }
  }

  private static Map<String, Object> payload(ValidationReportEvidenceEvent event) {
    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, "runId", event.runId());
    put(payload, "reportId", event.reportId());
    put(payload, "verifiedScore", event.verifiedScore());
    put(payload, "rubricScore", event.rubricScore());
    put(
        payload,
        "pipelineResult",
        event.pipelineResult() == null ? null : event.pipelineResult().name());
    put(payload, "createdAt", event.createdAt() == null ? null : event.createdAt().toString());
    payload.put("verificationLedger", event.verificationLedger());
    put(payload, "runGoalDecision", event.runGoalDecision());
    return payload;
  }

  private static void put(Map<String, Object> payload, String key, Object value) {
    if (value != null) {
      payload.put(key, value);
    }
  }
}
