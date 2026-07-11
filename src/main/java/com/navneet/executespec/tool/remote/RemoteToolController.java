package com.navneet.executespec.tool.remote;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.agentsession.LocalHandsStateService.PendingCall;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Receives local tool-execution results from the CLI / VS Code client and hands them to the {@link
 * RemoteToolDispatcher}, unblocking the server-side agent's pending tool call. Additive surface for
 * the local-hands/remote-brain model (RL-2026-05-043); a no-op for runs not bound to a remote
 * session.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RemoteToolController {

  private final RemoteToolDispatcher dispatcher;
  private final RunAccessGuard guard;
  private final ObjectMapper objectMapper;

  /** Mirror of the client's {@code ToolResultFrame}: the call id + the result envelope. */
  record ToolResultBody(String callId, JsonNode result) {}

  @PostMapping("/{runId}/tool-results")
  public ResponseEntity<Void> submitToolResult(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String runId,
      @RequestBody ToolResultBody body) {
    // Only the run owner (the machine executing it) may return tool results.
    guard.requireRunWriteAccess(runId, userDetails);
    if (body == null || body.callId() == null || body.callId().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    JsonNode normalized = normalizeEnvelope(body.result());
    if (normalized == null) {
      // Fail the pending transport call with a bounded protocol envelope. Leaving it pending makes
      // one bad client frame stall the worker until the full remote-tool timeout.
      dispatcher.rejectMalformed(runId, body.callId());
      return ResponseEntity.badRequest().build();
    }
    String envelope = normalized.toString();
    dispatcher.complete(runId, body.callId(), envelope);
    return ResponseEntity.noContent().build();
  }

  /** Reload pending local-hands calls after a client reconnects and re-emit them over run SSE. */
  @PostMapping("/{runId}/tool-session/reconnect")
  public ResponseEntity<List<PendingCall>> reconnect(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String runId) {
    guard.requireRunWriteAccess(runId, userDetails);
    return ResponseEntity.ok(dispatcher.reconnect(runId));
  }

  /**
   * Reports whether reconnect actually re-bound a live worker wait. This is intentionally separate
   * from the legacy reconnect list response: after a JVM restart the durable action stream can
   * describe the pending call, but the original in-memory worker future no longer exists.
   */
  @GetMapping("/{runId}/tool-session/reconnect-status")
  public ResponseEntity<RemoteToolDispatcher.ReconnectStatus> reconnectStatus(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String runId) {
    guard.requireRunWriteAccess(runId, userDetails);
    return ResponseEntity.ok(dispatcher.reconnectStatus(runId));
  }

  /** Explicit client transport disconnect; pending calls remain resumable until their deadline. */
  @PostMapping("/{runId}/tool-session/disconnect")
  public ResponseEntity<Void> disconnect(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String runId) {
    guard.requireRunWriteAccess(runId, userDetails);
    dispatcher.disconnect(runId);
    return ResponseEntity.noContent().build();
  }

  /** Read-only recovery surface for clients that lost their SSE replay cursor. */
  @GetMapping("/{runId}/pending-tool-calls")
  public ResponseEntity<List<PendingCall>> pendingCalls(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String runId) {
    guard.requireRunAccess(runId, userDetails);
    return ResponseEntity.ok(dispatcher.pendingCalls(runId));
  }

  private JsonNode normalizeEnvelope(JsonNode result) {
    if (result == null
        || !result.isObject()
        || !result.has("ok")
        || !result.get("ok").isBoolean()) {
      return null;
    }
    ObjectNode out = objectMapper.createObjectNode();
    boolean ok = result.get("ok").asBoolean();
    out.put("ok", ok);
    if (ok) {
      JsonNode content = result.get("content");
      out.put("content", content == null || content.isNull() ? "" : contentText(content));
      return out;
    }
    JsonNode kind = result.get("kind");
    JsonNode message = result.get("message");
    out.put(
        "kind",
        kind == null || kind.isNull() || kind.asText().isBlank() ? "INTERNAL" : kind.asText());
    out.put(
        "message",
        message == null || message.isNull() || message.asText().isBlank()
            ? "client tool failed without a message"
            : message.asText());
    return out;
  }

  private String contentText(JsonNode content) {
    return content.isTextual() ? content.asText() : content.toString();
  }
}
