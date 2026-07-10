package com.navneet.executespec.tool.remote;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agentsession.LocalHandsState;
import com.navneet.executespec.agentsession.LocalHandsStateService;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
@Slf4j
public class RemoteToolDispatcher {

  private final SseBroadcaster broadcaster;
  private final ObjectMapper objectMapper;
  private final LocalHandsStateService stateService;

  /**
   * How long a server-side tool call blocks for the client to execute and reply. Configurable via
   * {@code executespec.remote-tool.timeout-ms} (default 600_000 = 10 min). Raised from the original
   * 120s so long-running client-side commands (e.g. Terminal-Bench builds / test suites executed in
   * the connected client's container) don't time out mid-flight. RL-2026-06 (terminal-bench remote
   * worker).
   */
  private final long toolTimeoutMs;

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  public RemoteToolDispatcher(
      SseBroadcaster broadcaster,
      ObjectMapper objectMapper,
      LocalHandsStateService stateService,
      @Value("${executespec.remote-tool.timeout-ms:600000}") long toolTimeoutMs) {
    this.broadcaster = broadcaster;
    this.objectMapper = objectMapper;
    this.stateService = stateService;
    this.toolTimeoutMs = toolTimeoutMs;
  }

  private static final class Session {
    final Map<String, PendingRemoteCall> pending = new ConcurrentHashMap<>();
    volatile boolean connected = true;
  }

  private record PendingRemoteCall(
      String callId,
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      String permissionKind,
      String frameJson,
      AtomicLong deadlineNanos,
      CompletableFuture<String> future) {}

  /**
   * Bind a run to remote execution. Called when a LOCAL-mode run starts with a connected client.
   */
  public void register(String runId) {
    Session existing = sessions.putIfAbsent(runId, new Session());
    if (existing == null) {
      log.info("[RemoteTool] registered local-execution session for run {}", runId);
      return;
    }
    reconnect(runId);
  }

  /** Tear down a run's session, unblocking any in-flight calls. Called at run terminal. */
  public void unregister(String runId) {
    Session s = sessions.remove(runId);
    if (s != null) {
      s.pending
          .values()
          .forEach(
              call -> {
                record(call, LocalHandsState.CLIENT_CANCELLED);
                call.future()
                    .complete(errorEnvelope("CLIENT_CANCELLED", "local-hands run session closed"));
              });
      log.info("[RemoteTool] unregistered local-execution session for run {}", runId);
    }
  }

  /**
   * Mark a client transport as disconnected without failing the worker's pending tool calls. A new
   * client can retrieve and resume them through {@link #reconnect}.
   */
  public void disconnect(String runId) {
    Session session = sessions.get(runId);
    if (session == null) {
      return;
    }
    session.connected = false;
    session.pending.values().forEach(call -> record(call, LocalHandsState.AWAITING_CLIENT));
    log.info(
        "[RemoteTool] local client disconnected for run {}; {} call(s) remain pending",
        runId,
        session.pending.size());
  }

  /** Re-emit every in-flight call after a client reconnects and return durable pending state. */
  public List<LocalHandsStateService.PendingCall> reconnect(String runId) {
    Session session = sessions.get(runId);
    if (session != null) {
      session.connected = true;
      session
          .pending
          .values()
          .forEach(
              call -> {
                call.deadlineNanos().set(deadlineAfterTimeout());
                record(call, LocalHandsState.CLIENT_RECONNECTED);
                emit(call);
              });
    }
    return stateService.pending(runId);
  }

  /** Reloadable pending calls for clients that reconnect without an active SSE replay cursor. */
  public List<LocalHandsStateService.PendingCall> pendingCalls(String runId) {
    return stateService.pending(runId);
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
    String permissionKind = permissionKind(toolName);
    try {
      String frameJson = frame(callId, runId, taskId, toolName, argsJson);
      PendingRemoteCall call =
          new PendingRemoteCall(
              callId,
              runId,
              taskId,
              toolName,
              argsJson,
              permissionKind,
              frameJson,
              new AtomicLong(deadlineAfterTimeout()),
              future);
      s.pending.put(callId, call);
      record(call, LocalHandsState.AWAITING_CLIENT);
      emit(call);
      return await(call);
    } catch (TimeoutException e) {
      PendingRemoteCall call = s.pending.get(callId);
      if (call != null) {
        record(call, LocalHandsState.CLIENT_TIMEOUT);
      }
      return errorEnvelope(
          "CLIENT_TIMEOUT",
          "client tool '" + toolName + "' timed out after " + toolTimeoutMs + "ms");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      PendingRemoteCall call = s.pending.get(callId);
      if (call != null) {
        record(call, LocalHandsState.CLIENT_CANCELLED);
      }
      return errorEnvelope(
          "CLIENT_CANCELLED", "interrupted awaiting client tool '" + toolName + "'");
    } catch (Exception e) {
      return errorEnvelope(
          "REMOTE_DISPATCH", "remote dispatch of '" + toolName + "' failed: " + e.getMessage());
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
    PendingRemoteCall call = s.pending.get(callId);
    if (call != null) {
      record(call, LocalHandsState.COMPLETED);
      call.future().complete(resultEnvelopeJson);
    } else {
      log.debug("[RemoteTool] result for unknown callId={} (run {})", callId, runId);
    }
  }

  /** Fail a malformed client frame immediately instead of leaving the worker blocked to timeout. */
  public boolean rejectMalformed(String runId, String callId) {
    Session session = sessions.get(runId);
    if (session == null) return false;
    PendingRemoteCall call = session.pending.get(callId);
    if (call == null) return false;
    record(call, LocalHandsState.CLIENT_PROTOCOL_ERROR);
    return call.future()
        .complete(
            errorEnvelope(
                "CLIENT_PROTOCOL_ERROR", "client returned a malformed tool-result envelope"));
  }

  private String await(PendingRemoteCall call)
      throws InterruptedException, java.util.concurrent.ExecutionException, TimeoutException {
    while (true) {
      long deadline = call.deadlineNanos().get();
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new TimeoutException();
      try {
        return call.future().get(remaining, TimeUnit.NANOSECONDS);
      } catch (TimeoutException timeout) {
        // A reconnect renews the deadline. If it raced with this wait expiring, wait against the
        // renewed lease instead of converting a healthy handoff into CLIENT_TIMEOUT.
        if (call.deadlineNanos().get() > deadline) continue;
        throw timeout;
      }
    }
  }

  private long deadlineAfterTimeout() {
    return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(toolTimeoutMs);
  }

  private void emit(PendingRemoteCall call) {
    broadcaster.emit(
        call.runId(),
        RunEvent.toolCall(
            call.runId(),
            call.taskId(),
            call.frameJson(),
            call.callId(),
            call.toolName(),
            call.argsJson(),
            call.permissionKind()));
  }

  private void record(PendingRemoteCall call, LocalHandsState state) {
    stateService.record(
        call.runId(),
        call.callId(),
        state,
        call.taskId(),
        call.toolName(),
        call.permissionKind(),
        call.frameJson());
  }

  private String frame(
      String callId, String runId, String taskId, String toolName, String argsJson) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("callId", callId);
    node.put("runId", runId);
    if (taskId != null) node.put("taskId", taskId);
    node.put("name", toolName);
    node.put("permissionKind", permissionKind(toolName));
    node.set("args", parseArgs(argsJson));
    return node.toString();
  }

  private String permissionKind(String toolName) {
    if (toolName == null || toolName.isBlank()) return "shell";
    return switch (toolName) {
      case "readFile",
              "read_file",
              "listFiles",
              "glob",
              "grep",
              "searchFiles",
              "search_files",
              "gitStatus",
              "gitDiff",
              "web_fetch",
              "webFetch",
              "web_search",
              "webSearch",
              "lsp",
              "ast_search",
              "astSearch",
              "update_plan",
              "updatePlan",
              "todo",
              "completeTask" ->
          "read";
      case "writeFile",
              "write_file",
              "editFile",
              "edit_file",
              "multiEdit",
              "multi_edit",
              "applyPatch",
              "apply_patch",
              "astEdit",
              "ast_edit",
              "move",
              "rename",
              "delete",
              "deletePath" ->
          "edit";
      case "gitCommit" -> "commit";
      case "shell",
              "bash",
              "executeCommand",
              "runCommand",
              "terminalCommand",
              "backgroundProcess",
              "background_process",
              "buildProject",
              "build",
              "runBuild",
              "runTests",
              "testProject",
              "installDependencies",
              "lintProject",
              "formatProject",
              "mcp_client",
              "mcpClient",
              "diagnostics" ->
          "shell";
      default -> "shell";
    };
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
