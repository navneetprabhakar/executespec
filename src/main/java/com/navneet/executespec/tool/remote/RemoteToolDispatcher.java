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
      @Value("${executespec.remote-tool.timeout-ms:600000}") long toolTimeoutMs) {
    this.broadcaster = broadcaster;
    this.objectMapper = objectMapper;
    this.toolTimeoutMs = toolTimeoutMs;
  }

  public enum CompletionStatus {
    ACCEPTED,
    DUPLICATE,
    UNKNOWN_SESSION,
    UNKNOWN_CALL
  }

  public record CompletionResult(CompletionStatus status, String message) {
    static CompletionResult accepted() {
      return new CompletionResult(CompletionStatus.ACCEPTED, "accepted");
    }

    static CompletionResult duplicate() {
      return new CompletionResult(CompletionStatus.DUPLICATE, "tool result already completed");
    }

    static CompletionResult unknownSession() {
      return new CompletionResult(CompletionStatus.UNKNOWN_SESSION, "no active local-hands session");
    }

    static CompletionResult unknownCall() {
      return new CompletionResult(CompletionStatus.UNKNOWN_CALL, "unknown or expired tool call id");
    }
  }

  private record PendingCall(CompletableFuture<String> future, String taskId, String toolName) {}

  private static final class Session {
    final Map<String, PendingCall> pending = new ConcurrentHashMap<>();
  }

  /**
   * Bind a run to remote execution. Called when a LOCAL-mode run starts with a connected client.
   */
  public void register(String runId) {
    sessions.put(runId, new Session());
    log.info("[RemoteTool] registered local-execution session for run {}", runId);
  }

  /** Tear down a run's session, unblocking any in-flight calls. Called at run terminal. */
  public void unregister(String runId) {
    Session s = sessions.remove(runId);
    if (s != null) {
      s.pending.forEach(
          (callId, pending) -> {
            pending.future().complete(errorEnvelope("INTERNAL", "run session closed"));
            emitToolResultStatus(runId, pending.taskId(), callId, pending.toolName(), "SESSION_CLOSED", null);
          });
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
    s.pending.put(callId, new PendingCall(future, taskId, toolName));
    try {
      String frameJson = frame(callId, runId, taskId, toolName, argsJson);
      if (frameJson.length() > 256_000) {
        s.pending.remove(callId);
        return errorEnvelope(
            "INVALID_ARGS", "remote tool frame exceeded 256000 characters; refusing to emit");
      }
      broadcaster.emit(
          runId,
          RunEvent.toolCall(
              runId, taskId, frameJson, callId, toolName, argsJson, permissionKind(toolName)));
      return future.get(toolTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      emitToolResultStatus(runId, taskId, callId, toolName, "TIMEOUT", "client did not answer");
      return errorEnvelope(
          "INTERNAL", "client tool '" + toolName + "' timed out after " + toolTimeoutMs + "ms");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      emitToolResultStatus(runId, taskId, callId, toolName, "INTERRUPTED", null);
      return errorEnvelope("INTERNAL", "interrupted awaiting client tool '" + toolName + "'");
    } catch (Exception e) {
      emitToolResultStatus(runId, taskId, callId, toolName, "DISPATCH_FAILED", e.getClass().getSimpleName());
      return errorEnvelope(
          "INTERNAL", "remote dispatch of '" + toolName + "' failed: " + e.getMessage());
    } finally {
      s.pending.remove(callId);
    }
  }

  /** Called by {@code RemoteToolController} when the client returns a tool result. */
  public CompletionResult complete(String runId, String callId, String resultEnvelopeJson) {
    Session s = sessions.get(runId);
    if (s == null) {
      log.debug("[RemoteTool] result for unknown session (run={}, callId={})", runId, callId);
      emitToolResultStatus(runId, null, callId, null, "REJECTED", "unknown_session");
      return CompletionResult.unknownSession();
    }
    PendingCall pending = s.pending.get(callId);
    if (pending == null) {
      log.debug("[RemoteTool] result for unknown callId={} (run {})", callId, runId);
      emitToolResultStatus(runId, null, callId, null, "REJECTED", "unknown_call");
      return CompletionResult.unknownCall();
    }
    boolean completed = pending.future().complete(resultEnvelopeJson);
    if (!completed) {
      emitToolResultStatus(runId, pending.taskId(), callId, pending.toolName(), "DUPLICATE", null);
      return CompletionResult.duplicate();
    }
    emitToolResultStatus(runId, pending.taskId(), callId, pending.toolName(), "ACCEPTED", null);
    return CompletionResult.accepted();
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
              "spawn_subagent",
              "spawnSubagent",
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

  private void emitToolResultStatus(
      String runId, String taskId, String callId, String toolName, String status, String reason) {
    try {
      broadcaster.emit(
          runId,
          RunEvent.toolResultStatus(runId, taskId, callId, toolName, status, reason));
    } catch (Exception e) {
      log.debug(
          "[RemoteTool] failed to emit tool-result status for run={} callId={}: {}",
          runId,
          callId,
          e.getMessage());
    }
  }
}
