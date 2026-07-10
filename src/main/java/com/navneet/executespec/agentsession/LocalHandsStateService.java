package com.navneet.executespec.agentsession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Persists local-hands tool-call lifecycle into the existing append-only agent action stream.
 *
 * <p>No schema change is required: the tool call id is the action id and each state transition is a
 * new event. The latest event for an action reconstructs whether the client still owes a result.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LocalHandsStateService {

  private static final String EVENT_PREFIX = "LOCAL_HANDS_";

  private final AgentSessionRepository sessionRepo;
  private final AgentActionEventRepository eventRepo;
  private final AgentEventRecorder eventRecorder;
  private final ObjectMapper objectMapper;

  public void record(
      String runId,
      String callId,
      LocalHandsState state,
      String taskId,
      String toolName,
      String permissionKind,
      String frameJson) {
    if (runId == null || runId.isBlank() || callId == null || callId.isBlank() || state == null) {
      return;
    }
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(runId, "ACTIVE");
      if (sessions == null || sessions.isEmpty()) {
        return;
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("state", state.name());
      payload.put("runId", runId);
      payload.put("callId", callId);
      put(payload, "taskId", taskId);
      put(payload, "toolName", toolName);
      put(payload, "permissionKind", permissionKind);
      put(payload, "frame", parseJson(frameJson));
      payload.put("recordedAt", Instant.now().toString());
      for (AgentSession session : sessions) {
        eventRecorder.appendAction(session.getId(), callId, EVENT_PREFIX + state.name(), payload);
      }
    } catch (RuntimeException ex) {
      // Agent-session evidence must not make the worker's tool path unavailable.
      log.warn(
          "[LocalHands] failed to persist {} for run {} call {}: {}",
          state,
          runId,
          callId,
          ex.getMessage());
    }
  }

  /** Reloadable calls for which the latest durable state still expects a client result. */
  public List<PendingCall> pending(String runId) {
    if (runId == null || runId.isBlank()) {
      return List.of();
    }
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(runId, "ACTIVE");
      if (sessions == null || sessions.isEmpty()) {
        return List.of();
      }
      List<AgentActionEvent> events =
          eventRepo.findBySessionIdOrderByCreatedAtAsc(sessions.get(0).getId());
      Map<String, PendingCall> latest = new LinkedHashMap<>();
      for (AgentActionEvent event : events) {
        if (event.getActionId() == null
            || event.getType() == null
            || !event.getType().startsWith(EVENT_PREFIX)) {
          continue;
        }
        PendingCall call = from(event);
        if (call != null) {
          latest.put(event.getActionId(), call);
        }
      }
      List<PendingCall> pending = new ArrayList<>();
      for (PendingCall call : latest.values()) {
        if (call.state() == LocalHandsState.AWAITING_CLIENT
            || call.state() == LocalHandsState.CLIENT_RECONNECTED) {
          pending.add(call);
        }
      }
      return List.copyOf(pending);
    } catch (RuntimeException ex) {
      log.warn(
          "[LocalHands] failed to reload pending calls for run {}: {}", runId, ex.getMessage());
      return List.of();
    }
  }

  private PendingCall from(AgentActionEvent event) {
    try {
      JsonNode payload = objectMapper.readTree(event.getPayloadJson());
      LocalHandsState state = LocalHandsState.valueOf(payload.path("state").asText());
      JsonNode frame = payload.get("frame");
      return new PendingCall(
          event.getActionId(),
          payload.path("runId").asText(),
          text(payload, "taskId"),
          text(payload, "toolName"),
          text(payload, "permissionKind"),
          frame == null || frame.isNull() ? "{}" : frame.toString(),
          state,
          event.getCreatedAt());
    } catch (Exception ex) {
      log.debug("[LocalHands] ignoring malformed persisted event {}", event.getId());
      return null;
    }
  }

  private Object parseJson(String value) {
    if (value == null || value.isBlank()) {
      return Map.of();
    }
    try {
      return objectMapper.readTree(value);
    } catch (Exception ex) {
      return value;
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static void put(Map<String, Object> payload, String key, Object value) {
    if (value != null) {
      payload.put(key, value);
    }
  }

  public record PendingCall(
      String callId,
      String runId,
      String taskId,
      String toolName,
      String permissionKind,
      String frameJson,
      LocalHandsState state,
      Instant updatedAt) {}
}
