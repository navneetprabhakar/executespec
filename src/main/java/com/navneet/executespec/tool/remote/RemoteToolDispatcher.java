package com.navneet.executespec.tool.remote;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Server side of the local-hands/remote-brain model. When a run is bound to a remote tool session,
 * every tool the agent calls is dispatched to the connected client (over the run's SSE channel as a
 * {@code TOOL_CALL} event), executed there against the user's local working tree, and the result
 * comes back via {@code POST /api/runs/{id}/tool-results}. The proprietary agent loop (planner /
 * worker / reviewer, prompts, model routing, scoring) is unchanged — only the *location* of tool
 * execution moves off the server.
 *
 * <p><b>Inert by default.</b> {@link #isRemote} is false for every run until {@link #register} is
 * called — and only the LOCAL-mode run hook does that — so the dispatch branch in {@code
 * ToolRegistry} is a no-op and the server-executed pipeline is byte-for-byte unchanged.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoteToolDispatcher {

  private final SseBroadcaster broadcaster;
  private final ObjectMapper objectMapper;

  /** How long a server-side tool call blocks for the client to execute and reply. */
  private static final long TOOL_TIMEOUT_MS = 120_000;

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  private static final class Session {
    final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
  }

  /** Bind a run to remote execution. Called when a LOCAL-mode run starts with a connected client. */
  public void register(String runId) {
    sessions.put(runId, new Session());
    log.info("[RemoteTool] registered local-execution session for run {}", runId);
  }

  /** Tear down a run's session, unblocking any in-flight calls. Called at run terminal. */
  public void unregister(String runId) {
    Session s = sessions.remove(runId);
    if (s != null) {
      s.pending.values().forEach(f -> f.complete(errorEnvelope("INTERNAL", "run session closed")));
      log.info("[RemoteTool] unregistered local-execution session for run {}", runId);
    }
  }

  public boolean isRemote(String runId) {
    return runId != null && sessions.containsKey(runId);
  }

  /**
   * Dispatch one tool call to the client and block (bounded) for the result. Returns the same JSON
   * envelope shape {@code ToolRegistry} would produce locally ({@code {"ok":true,"content":...}} or
   * {@code {"ok":false,"kind":...,"message":...}}), so the agent loop is agnostic to where the tool
   * ran.
   */
  public String dispatch(String runId, String taskId, String toolName, String argsJson) {
    Session s = sessions.get(runId);
    if (s == null) {
      return errorEnvelope("INTERNAL", "no remote session bound for run " + runId);
    }
    String callId = UUID.randomUUID().toString();
    CompletableFuture<String> future = new CompletableFuture<>();
    s.pending.put(callId, future);
    try {
      broadcaster.emit(runId, RunEvent.toolCall(runId, taskId, frame(callId, runId, taskId, toolName, argsJson)));
      return future.get(TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      return errorEnvelope(
          "INTERNAL", "client tool '" + toolName + "' timed out after " + TOOL_TIMEOUT_MS + "ms");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return errorEnvelope("INTERNAL", "interrupted awaiting client tool '" + toolName + "'");
    } catch (Exception e) {
      return errorEnvelope(
          "INTERNAL", "remote dispatch of '" + toolName + "' failed: " + e.getMessage());
    } finally {
      s.pending.remove(callId);
    }
  }

  /** Called by {@code RemoteToolController} when the client returns a tool result. */
  public void complete(String runId, String callId, String resultEnvelopeJson) {
    Session s = sessions.get(runId);
    if (s == null) {
      log.debug("[RemoteTool] result for unknown session (run={}, callId={})", runId, callId);
      return;
    }
    CompletableFuture<String> f = s.pending.get(callId);
    if (f != null) {
      f.complete(resultEnvelopeJson);
    } else {
      log.debug("[RemoteTool] result for unknown callId={} (run {})", callId, runId);
    }
  }

  private String frame(
      String callId, String runId, String taskId, String toolName, String argsJson) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("callId", callId);
    node.put("runId", runId);
    if (taskId != null) node.put("taskId", taskId);
    node.put("name", toolName);
    node.set("args", parseArgs(argsJson));
    return node.toString();
  }

  private JsonNode parseArgs(String argsJson) {
    if (argsJson == null || argsJson.isBlank()) return objectMapper.createObjectNode();
    try {
      return objectMapper.readTree(argsJson);
    } catch (Exception e) {
      // The LLM emits JSON args; if somehow not, pass through as text rather than failing dispatch.
      return objectMapper.getNodeFactory().textNode(argsJson);
    }
  }

  private String errorEnvelope(String kind, String message) {
    ObjectNode env = objectMapper.createObjectNode();
    env.put("ok", false);
    env.put("kind", kind);
    env.put("message", message);
    return env.toString();
  }
}
