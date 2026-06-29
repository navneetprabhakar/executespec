package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.sse.RunEvent;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
class AgentRunEventBridge {

  private static final Map<String, String> EVENT_TYPES =
      Map.ofEntries(
          Map.entry("TASK_WAVE_PLAN", "RUN_TASK_WAVE_PLAN"),
          Map.entry("GOAL_DECISION", "RUN_GOAL_DECISION"),
          Map.entry("CONTINUATION_DECISION", "RUN_CONTINUATION_DECISION"),
          Map.entry("CONTINUATION_ACTION", "RUN_CONTINUATION_ACTION"),
          Map.entry("CONTINUATION_ACTION_SKIPPED", "RUN_CONTINUATION_ACTION_SKIPPED"));

  private final AgentSessionRepository sessionRepo;
  private final AgentEventRecorder eventRecorder;

  @EventListener
  void onRunEvent(RunEvent event) {
    if (event == null || event.runId() == null || event.runId().isBlank()) {
      return;
    }
    String agentEventType = EVENT_TYPES.get(event.type());
    if (agentEventType == null) {
      return;
    }
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(event.runId(), "ACTIVE");
      if (sessions == null || sessions.isEmpty()) {
        return;
      }
      for (AgentSession session : sessions) {
        eventRecorder.append(session.getId(), agentEventType, payload(event));
        session.setUpdatedAt(Instant.now());
        sessionRepo.save(session);
      }
    } catch (RuntimeException ex) {
      log.debug(
          "Failed to bridge run event {} for run {} into agent sessions: {}",
          event.type(),
          event.runId(),
          ex.getMessage());
    }
  }

  private static Map<String, Object> payload(RunEvent event) {
    Map<String, Object> payload = new LinkedHashMap<>();
    put(payload, "runEventType", event.type());
    put(payload, "runId", event.runId());
    put(payload, "taskId", event.taskId());
    put(payload, "summary", event.payload());
    put(payload, "timestamp", event.timestamp() == null ? null : event.timestamp().toString());
    payload.put("schemaVersion", event.schemaVersion());
    payload.put("data", event.data() == null ? Map.of() : event.data());
    return payload;
  }

  private static void put(Map<String, Object> payload, String key, Object value) {
    if (value != null) {
      payload.put(key, value);
    }
  }
}
