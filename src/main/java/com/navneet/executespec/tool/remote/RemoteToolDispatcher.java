package com.navneet.executespec.tool.remote;

import java.time.Instant;
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
import com.navneet.executespec.engine.AbsoluteDeadlineBudget;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.beans.factory.annotation.Autowired;
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

  /**
   * Model-visible tool profile for a remote execution session. LOCAL_HANDS is backed by the
   * released CLI runtime; TERMINAL_BENCH is deliberately limited to the Harbor adapter's smaller
   * canonical contract. The profile affects schema advertisement only, never result transport.
   */
  public enum ToolProfile {
    LOCAL_HANDS,
    TERMINAL_BENCH
  }

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

  /** Persisted calls older than this are not offered as a restart handoff. */
  private final long restartRecoveryWindowMs;

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  @Autowired
  public RemoteToolDispatcher(
      SseBroadcaster broadcaster,
      ObjectMapper objectMapper,
      LocalHandsStateService stateService,
      @Value("${executespec.remote-tool.timeout-ms:600000}") long toolTimeoutMs) {
    this(broadcaster, objectMapper, stateService, toolTimeoutMs, toolTimeoutMs);
  }

  /** Visible for tests; restart recovery must remain bounded by the original remote lease. */
  RemoteToolDispatcher(
      SseBroadcaster broadcaster,
      ObjectMapper objectMapper,
      LocalHandsStateService stateService,
      long toolTimeoutMs,
      long restartRecoveryWindowMs) {
    this.broadcaster = broadcaster;
    this.objectMapper = objectMapper;
    this.stateService = stateService;
    this.toolTimeoutMs = toolTimeoutMs;
    this.restartRecoveryWindowMs = restartRecoveryWindowMs;
  }

  private static final class Session {
    final Map<String, PendingRemoteCall> pending = new ConcurrentHashMap<>();

    final ToolProfile toolProfile;

    final Long absoluteDeadlineEpochMs;
    final Long shutdownReserveMs;

    /** True once this process has an in-memory worker wait that a result can unblock. */
    volatile boolean workerWaitRebound;

    volatile boolean connected = true;

    Session() {
      this(true, ToolProfile.LOCAL_HANDS, null, null);
    }

    Session(boolean workerWaitRebound) {
      this(workerWaitRebound, ToolProfile.LOCAL_HANDS, null, null);
    }

    Session(boolean workerWaitRebound, ToolProfile toolProfile) {
      this(workerWaitRebound, toolProfile, null, null);
    }

    Session(
        boolean workerWaitRebound,
        ToolProfile toolProfile,
        Long absoluteDeadlineEpochMs,
        Long shutdownReserveMs) {
      this.workerWaitRebound = workerWaitRebound;
      this.toolProfile = toolProfile;
      this.absoluteDeadlineEpochMs = absoluteDeadlineEpochMs;
      this.shutdownReserveMs = shutdownReserveMs;
    }
  }

  private record PendingRemoteCall(
      String callId,
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      String permissionKind,
      String frameJson,
      Long absoluteDeadlineEpochMs,
      Long shutdownReserveMs,
      AtomicLong deadlineNanos,
      CompletableFuture<String> future) {}

  /**
   * Bind a run to remote execution. Called when a LOCAL-mode run starts with a connected client.
   */
  public void register(String runId) {
    register(runId, ToolProfile.LOCAL_HANDS);
  }

  /** Bind a run with an explicit model-visible remote tool profile. */
  public void register(String runId, ToolProfile toolProfile) {
    register(runId, toolProfile, null, null);
  }

  /** Bind a deadline-aware benchmark session; product local-hands sessions remain unchanged. */
  public void register(
      String runId, ToolProfile toolProfile, Long absoluteDeadlineEpochMs, Long shutdownReserveMs) {
    ToolProfile resolved = toolProfile == null ? ToolProfile.LOCAL_HANDS : toolProfile;
    Session existing =
        sessions.putIfAbsent(
            runId,
            new Session(
                true, resolved, absoluteDeadlineEpochMs, Math.max(0L, orZero(shutdownReserveMs))));
    if (existing == null) {
      log.info(
          "[RemoteTool] registered local-execution session for run {} with profile {}",
          runId,
          resolved);
      return;
    }
    if (existing.toolProfile != resolved) {
      log.warn(
          "[RemoteTool] run {} already uses profile {}; ignoring conflicting registration {}",
          runId,
          existing.toolProfile,
          resolved);
    }
    reconnect(runId);
  }

  /**
   * Profile used to advertise tools for this remote session, or null when the run is not remote.
   */
  public ToolProfile toolProfile(String runId) {
    Session session = sessions.get(runId);
    return session == null ? null : session.toolProfile;
  }

  /** Bind a run after JVM crash recovery and close stale calls from the old worker future. */
  public void registerAfterWorkerRestart(String runId) {
    Session existing = sessions.putIfAbsent(runId, new Session(false));
    if (existing == null) {
      int closed = stateService.closeForWorkerRestart(runId);
      log.info(
          "[RemoteTool] registered recovered session for run {}; closed {} stale call(s)",
          runId,
          closed);
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
                call.deadlineNanos()
                    .set(
                        deadlineAfterTimeout(
                            call.absoluteDeadlineEpochMs(), call.shutdownReserveMs()));
                record(call, LocalHandsState.CLIENT_RECONNECTED);
                emit(call);
              });
    }
    // A fresh JVM has no worker future to rebind. Return only lease-valid evidence so an old
    // client cannot accidentally replay a call that has already exceeded its remote wait window.
    return freshPending(stateService.pending(runId));
  }

  /**
   * Describes whether a reconnect can actually rebind an in-memory worker wait.
   *
   * <p>A fresh JVM has durable evidence of the call but not the blocked provider future. It must
   * therefore never pretend that a tool result can resume the old worker. Clients use this additive
   * status endpoint to distinguish a live handoff from a restart that requires a new run (or an
   * explicit future checkpoint/resume implementation).
   */
  public ReconnectStatus reconnectStatus(String runId) {
    Session session = sessions.get(runId);
    List<LocalHandsStateService.PendingCall> durable = stateService.pending(runId);
    Instant cutoff = Instant.now().minusMillis(Math.max(0L, restartRecoveryWindowMs));
    List<String> stale =
        durable.stream()
            .filter(call -> call.updatedAt() != null && call.updatedAt().isBefore(cutoff))
            .map(LocalHandsStateService.PendingCall::callId)
            .toList();
    List<LocalHandsStateService.PendingCall> fresh = freshPending(durable);
    if (session != null) {
      return new ReconnectStatus("CONNECTED", session.workerWaitRebound, fresh, stale);
    }
    if (!fresh.isEmpty() || !stale.isEmpty()) {
      return new ReconnectStatus("RESTART_REQUIRES_NEW_RUN", false, fresh, stale);
    }
    return new ReconnectStatus("NO_PENDING_CALLS", false, List.of(), List.of());
  }

  public record ReconnectStatus(
      String state,
      boolean workerWaitRebound,
      List<LocalHandsStateService.PendingCall> pendingCalls,
      List<String> staleCallIds) {}

  private List<LocalHandsStateService.PendingCall> freshPending(
      List<LocalHandsStateService.PendingCall> calls) {
    Instant cutoff = Instant.now().minusMillis(Math.max(0L, restartRecoveryWindowMs));
    return calls.stream()
        .filter(call -> call.updatedAt() == null || !call.updatedAt().isBefore(cutoff))
        .toList();
  }

  /** Reloadable pending calls for clients that reconnect without an active SSE replay cursor. */
  public List<LocalHandsStateService.PendingCall> pendingCalls(String runId) {
    // The endpoint is a reload/reconnect surface, not an unbounded audit export. Apply the same
    // lease window as reconnectStatus so a client cannot replay an expired call after a long
    // disconnect or JVM restart.
    return freshPending(stateService.pending(runId));
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
    return dispatch(runId, taskId, toolName, argsJson, null, null);
  }

  public String dispatch(
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      Long absoluteDeadlineEpochMs,
      Long shutdownReserveMs) {
    Session s = sessions.get(runId);
    if (s == null) {
      return errorEnvelope("INTERNAL", "no remote session bound for run " + runId);
    }
    Long effectiveDeadline = earlierDeadline(s.absoluteDeadlineEpochMs, absoluteDeadlineEpochMs);
    long effectiveReserve = Math.max(orZero(s.shutdownReserveMs), orZero(shutdownReserveMs));
    long usableMillis =
        AbsoluteDeadlineBudget.usableMillis(
            effectiveDeadline, effectiveReserve, System.currentTimeMillis());
    if (usableMillis < 1L) {
      return errorEnvelope(
          "DEADLINE_EXCEEDED", "remote tool refused to preserve terminal shutdown reserve");
    }
    // A crash can happen after the local client executed a mutating command but before the old
    // worker received its HTTP response. Crash recovery resumes from the last context snapshot;
    // consume the exact durable post-restart result when available instead of issuing the command
    // a second time. The lookup is intentionally exact and only matches calls fenced by
    // WORKER_RESTARTED, so normal repeated commands and healthy reconnects are unchanged.
    try {
      java.util.Optional<LocalHandsStateService.RestartedResult> recovered =
          stateService.findRestartedResult(runId, taskId, toolName, argsJson);
      if (recovered.isPresent()) {
        log.info(
            "[RemoteTool] reusing durable post-restart result run={} task={} tool={} oldCall={}",
            runId,
            taskId,
            toolName,
            recovered.get().callId());
        return recovered.get().resultEnvelope();
      }
    } catch (RuntimeException ex) {
      // Durable evidence is an optimisation and must never make the remote worker unavailable.
      log.debug(
          "[RemoteTool] restarted-result lookup failed for run {}: {}", runId, ex.getMessage());
    }
    s.workerWaitRebound = true;
    String callId = UUID.randomUUID().toString();
    CompletableFuture<String> future = new CompletableFuture<>();
    String permissionKind = permissionKind(toolName);
    try {
      String frameJson =
          frame(callId, runId, taskId, toolName, argsJson, effectiveDeadline, effectiveReserve);
      PendingRemoteCall call =
          new PendingRemoteCall(
              callId,
              runId,
              taskId,
              toolName,
              argsJson,
              permissionKind,
              frameJson,
              effectiveDeadline,
              effectiveReserve,
              new AtomicLong(deadlineAfterTimeout(effectiveDeadline, effectiveReserve)),
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
          "CLIENT_TIMEOUT", "client tool '" + toolName + "' exceeded its bounded deadline");
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
      if (!finish(call, LocalHandsState.COMPLETED, resultEnvelopeJson)) {
        // Clients are allowed to retry a result when the 204 response was lost.  The
        // first result is authoritative; do not append another terminal event or let a
        // racing duplicate change the durable replay state.
        log.debug("[RemoteTool] duplicate result ignored for callId={} (run {})", callId, runId);
      }
    } else {
      // A client can retry after the worker JVM restarted and the in-memory future disappeared.
      // Preserve that result durably instead of treating it as an unrelated/invalid call.  This
      // does not claim to resume the old worker; it only closes the durable handoff exactly once.
      if (!stateService.acceptRestartedResult(
          runId, callId, resultEnvelopeJson, restartRecoveryWindowMs)) {
        log.debug("[RemoteTool] result for unknown callId={} (run {})", callId, runId);
      }
    }
  }

  /** Fail a malformed client frame immediately instead of leaving the worker blocked to timeout. */
  public boolean rejectMalformed(String runId, String callId) {
    Session session = sessions.get(runId);
    if (session == null) return false;
    PendingRemoteCall call = session.pending.get(callId);
    if (call == null) return false;
    return finish(
        call,
        LocalHandsState.CLIENT_PROTOCOL_ERROR,
        errorEnvelope("CLIENT_PROTOCOL_ERROR", "client returned a malformed tool-result envelope"));
  }

  /**
   * Accept exactly one terminal result for a pending call.
   *
   * <p>The client may retry a result after losing the HTTP response. {@link
   * CompletableFuture#complete(Object)} is first-writer-wins, but recording the lifecycle event
   * before that call used to let two racing retries append competing {@code COMPLETED} events. The
   * synchronized transition keeps the durable evidence and the in-memory waiter aligned. This is
   * deliberately process-local; a JVM restart still cannot resume the old worker future (the
   * documented restart boundary).
   */
  private boolean finish(PendingRemoteCall call, LocalHandsState state, String resultEnvelope) {
    synchronized (call) {
      if (call.future().isDone()) {
        return false;
      }
      if (state == LocalHandsState.COMPLETED) {
        stateService.recordCompleted(
            call.runId(),
            call.callId(),
            call.taskId(),
            call.toolName(),
            call.permissionKind(),
            call.frameJson(),
            resultEnvelope);
      } else {
        record(call, state);
      }
      return call.future().complete(resultEnvelope);
    }
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

  private long deadlineAfterTimeout(Long absoluteDeadlineEpochMs, Long shutdownReserveMs) {
    long usable =
        AbsoluteDeadlineBudget.usableMillis(
            absoluteDeadlineEpochMs, shutdownReserveMs, System.currentTimeMillis());
    long bounded = usable == Long.MAX_VALUE ? toolTimeoutMs : Math.min(toolTimeoutMs, usable);
    return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, bounded));
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
      String callId,
      String runId,
      String taskId,
      String toolName,
      String argsJson,
      Long absoluteDeadlineEpochMs,
      Long shutdownReserveMs) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("callId", callId);
    node.put("runId", runId);
    if (taskId != null) node.put("taskId", taskId);
    node.put("name", toolName);
    node.put("permissionKind", permissionKind(toolName));
    if (absoluteDeadlineEpochMs != null && absoluteDeadlineEpochMs > 0) {
      node.put("absoluteDeadlineEpochMs", absoluteDeadlineEpochMs);
      node.put("shutdownReserveMs", Math.max(0L, orZero(shutdownReserveMs)));
    }
    node.set("args", parseArgs(argsJson));
    return node.toString();
  }

  private static long orZero(Long value) {
    return value == null ? 0L : value;
  }

  private static Long earlierDeadline(Long first, Long second) {
    if (first == null || first <= 0) return second;
    if (second == null || second <= 0) return first;
    return Math.min(first, second);
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
