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
  private static final List<String> REPLAYABLE_TYPES =
      List.of(
          EVENT_PREFIX + LocalHandsState.AWAITING_CLIENT.name(),
          EVENT_PREFIX + LocalHandsState.CLIENT_RECONNECTED.name(),
          EVENT_PREFIX + LocalHandsState.CLIENT_PROTOCOL_ERROR.name(),
          EVENT_PREFIX + LocalHandsState.CLIENT_TIMEOUT.name(),
          EVENT_PREFIX + LocalHandsState.CLIENT_CANCELLED.name(),
          EVENT_PREFIX + LocalHandsState.WORKER_RESTARTED.name(),
          EVENT_PREFIX + LocalHandsState.COMPLETED.name());

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

  /**
   * Persist the result envelope with the terminal transition. Keeping the envelope in the
   * append-only stream makes a retried client result idempotent across JVMs; the old implementation
   * only stored the state, so a restart lost the authoritative response body.
   */
  public boolean recordCompleted(
      String runId,
      String callId,
      String taskId,
      String toolName,
      String permissionKind,
      String frameJson,
      String resultEnvelope) {
    return recordInternal(
        runId,
        callId,
        LocalHandsState.COMPLETED,
        taskId,
        toolName,
        permissionKind,
        frameJson,
        resultEnvelope);
  }

  /**
   * Accept a result that arrived after a JVM restart. There is no worker future to unblock, but
   * recording the terminal response prevents a client retry from being treated as an unknown call
   * and preserves the exact result for a later audit/recovery surface.
   */
  public boolean acceptRestartedResult(String runId, String callId, String resultEnvelope) {
    return acceptRestartedResult(runId, callId, resultEnvelope, Long.MAX_VALUE);
  }

  /**
   * Accept a late result only while the original remote lease is still valid.
   *
   * <p>A result that arrives after a JVM restart has no worker future to unblock. It is useful as
   * durable handoff evidence only for the same bounded window in which the old remote call could
   * have remained in flight. Without this check, a client holding an old call id could close an
   * arbitrarily old restart marker long after the run had moved on. A non-positive window is
   * treated as no valid recovery window; a null event timestamp is rejected in bounded mode.
   */
  public boolean acceptRestartedResult(
      String runId, String callId, String resultEnvelope, long maxAgeMillis) {
    if (runId == null || runId.isBlank() || callId == null || callId.isBlank()) return false;
    if (maxAgeMillis <= 0) return false;
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(runId, "ACTIVE");
      if (sessions == null || sessions.isEmpty()) return false;
      PendingCall restarted = latestCall(sessions, callId, LocalHandsState.WORKER_RESTARTED);
      if (restarted == null) return false;
      if (maxAgeMillis != Long.MAX_VALUE) {
        Instant updatedAt = restarted.updatedAt();
        Instant cutoff = Instant.now().minusMillis(maxAgeMillis);
        if (updatedAt == null || updatedAt.isBefore(cutoff)) return false;
      }
      return recordCompleted(
          runId,
          callId,
          restarted.taskId(),
          restarted.toolName(),
          restarted.permissionKind(),
          restarted.frameJson(),
          resultEnvelope);
    } catch (RuntimeException ex) {
      log.warn(
          "[LocalHands] failed to persist restarted result for run {} call {}: {}",
          runId,
          callId,
          ex.getMessage());
      return false;
    }
  }

  private boolean recordInternal(
      String runId,
      String callId,
      LocalHandsState state,
      String taskId,
      String toolName,
      String permissionKind,
      String frameJson,
      String resultEnvelope) {
    if (runId == null || runId.isBlank() || callId == null || callId.isBlank() || state == null) {
      return false;
    }
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(runId, "ACTIVE");
      if (sessions == null || sessions.isEmpty()) return false;
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("state", state.name());
      payload.put("runId", runId);
      payload.put("callId", callId);
      put(payload, "taskId", taskId);
      put(payload, "toolName", toolName);
      put(payload, "permissionKind", permissionKind);
      put(payload, "frame", parseJson(frameJson));
      put(payload, "result", parseJson(resultEnvelope));
      payload.put("recordedAt", Instant.now().toString());
      boolean persisted = false;
      for (AgentSession session : sessions) {
        try {
          eventRecorder.appendAction(session.getId(), callId, EVENT_PREFIX + state.name(), payload);
          persisted = true;
        } catch (RuntimeException ex) {
          // V264 makes COMPLETED first-writer-wins across JVMs. A duplicate terminal insert is an
          // expected retry outcome, not a worker-path failure.
          log.debug(
              "[LocalHands] terminal state already recorded for run {} call {} session {}",
              runId,
              callId,
              session.getId());
        }
      }
      return persisted;
    } catch (RuntimeException ex) {
      log.warn(
          "[LocalHands] failed to persist {} for run {} call {}: {}",
          state,
          runId,
          callId,
          ex.getMessage());
      return false;
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
      // A run can briefly have more than one active agent session (for example while a CLI
      // reconnects and the old session is still being retired).  State writes are intentionally
      // fanned out to every active session, so reading only the newest session can lose a pending
      // tool call when that session did not receive the original event.  Reconstruct the latest
      // state across all active sessions and let the event timestamp provide deterministic
      // last-write-wins semantics for duplicated action ids.
      Map<String, PendingCall> latest = new LinkedHashMap<>();
      for (AgentSession session : sessions) {
        List<AgentActionEvent> events =
            eventRepo.findBySessionIdAndTypeInOrderByCreatedAtAsc(
                session.getId(), REPLAYABLE_TYPES);
        for (AgentActionEvent event : events) {
          if (event.getActionId() == null
              || event.getType() == null
              || !event.getType().startsWith(EVENT_PREFIX)) {
            continue;
          }
          PendingCall call = from(event);
          if (call == null) {
            continue;
          }
          PendingCall previous = latest.get(event.getActionId());
          if (previous == null
              || previous.updatedAt() == null
              || (call.updatedAt() != null && call.updatedAt().isAfter(previous.updatedAt()))) {
            latest.put(event.getActionId(), call);
          }
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

  /** Close durable calls whose worker future belonged to a previous JVM. */
  public int closeForWorkerRestart(String runId) {
    if (runId == null || runId.isBlank()) return 0;
    try {
      List<AgentSession> sessions =
          sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc(runId, "ACTIVE");
      if (sessions == null || sessions.isEmpty()) return 0;
      int closed = 0;
      for (PendingCall call : pending(runId)) {
        record(
            runId,
            call.callId(),
            LocalHandsState.WORKER_RESTARTED,
            call.taskId(),
            call.toolName(),
            call.permissionKind(),
            call.frameJson());
        closed++;
      }
      return closed;
    } catch (RuntimeException ex) {
      log.warn("[LocalHands] failed to close restart calls for run {}: {}", runId, ex.getMessage());
      return 0;
    }
  }

  private PendingCall latestCall(
      List<AgentSession> sessions, String callId, LocalHandsState expectedState) {
    PendingCall latest = null;
    for (AgentSession session : sessions) {
      List<AgentActionEvent> events =
          eventRepo.findBySessionIdAndTypeInOrderByCreatedAtAsc(session.getId(), REPLAYABLE_TYPES);
      for (AgentActionEvent event : events) {
        if (!callId.equals(event.getActionId())) continue;
        PendingCall call = from(event);
        if (call == null || call.state() != expectedState) continue;
        if (latest == null
            || latest.updatedAt() == null
            || (call.updatedAt() != null && call.updatedAt().isAfter(latest.updatedAt()))) {
          latest = call;
        }
      }
    }
    return latest;
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
