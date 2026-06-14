package com.navneet.executespec.tool.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    String envelope = normalizeEnvelope(body.result()).toString();
    dispatcher.complete(runId, body.callId(), envelope);
    return ResponseEntity.noContent().build();
  }

  private JsonNode normalizeEnvelope(JsonNode result) {
    if (result == null
        || !result.isObject()
        || !result.has("ok")
        || !result.get("ok").isBoolean()) {
      return errorEnvelope("INTERNAL", "malformed tool result");
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

  private JsonNode errorEnvelope(String kind, String message) {
    ObjectNode out = objectMapper.createObjectNode();
    out.put("ok", false);
    out.put("kind", kind);
    out.put("message", message);
    return out;
  }

  private String contentText(JsonNode content) {
    return content.isTextual() ? content.asText() : content.toString();
  }
}
