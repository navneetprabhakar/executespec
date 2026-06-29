package com.navneet.executespec.tool.remote;

import java.util.Set;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtAuthenticationFilter;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Receives local tool-execution results from the CLI / VS Code client and hands them to the {@link
 * RemoteToolDispatcher}, unblocking the server-side agent's pending tool call. Additive surface for
 * the local-hands/remote-brain model (RL-2026-05-043); a no-op for runs not bound to a remote
 * session.
 */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
@Slf4j
public class RemoteToolController {

  private static final int MAX_CONTENT_CHARS = 60_000;
  private static final int MAX_MESSAGE_CHARS = 4_000;
  private static final Set<String> TERMINAL_STATUSES =
      Set.of("COMPLETE", "FAILED", "CANCELLED", "PARTIAL");
  private static final Set<String> ERROR_KINDS =
      Set.of(
          "INVALID_ARGS",
          "NOT_FOUND",
          "ALREADY_EXISTS",
          "STALE_READ",
          "IO_ERROR",
          "HTTP_ERROR",
          "FORBIDDEN",
          "COMMAND_FAILED",
          "TIMEOUT",
          "UNAVAILABLE",
          "INTERNAL");
  private static final Pattern SECRET_PATTERN =
      Pattern.compile(
          "(?i)(es_pat_[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]{16,}|ghp_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]+|"
              + "xox[baprs]-[A-Za-z0-9-]+|AIza[A-Za-z0-9_-]{20,}|anthropic-[A-Za-z0-9_-]{20,}|"
              + "(api[_-]?key|access[_-]?token|secret|password)=([^\\s&]+))");

  private final RemoteToolDispatcher dispatcher;
  private final RunAccessGuard guard;
  private final ObjectMapper objectMapper;

  /** Mirror of the client's {@code ToolResultFrame}: the call id + the result envelope. */
  record ToolResultBody(String callId, String runId, JsonNode result) {
    ToolResultBody(String callId, JsonNode result) {
      this(callId, null, result);
    }
  }

  @PostMapping("/{runId}/tool-results")
  public ResponseEntity<Void> submitToolResult(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String runId,
      @RequestBody ToolResultBody body,
      HttpServletRequest request) {
    // Only the run owner (the machine executing it) may return tool results.
    PipelineRun run = guard.requireRunWriteAccess(runId, userDetails);
    if (!isPatAuthenticated(request)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "Local-hands tool results require a CLI or VS Code Personal Access Token session.");
    }
    if (body == null || body.callId() == null || body.callId().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    if (body.runId() != null && !body.runId().isBlank() && !runId.equals(body.runId())) {
      log.warn(
          "[RemoteTool] rejected tool result with mismatched run id path={} body={}",
          runId,
          body.runId());
      return ResponseEntity.badRequest().build();
    }
    if (run.getStatus() != null && TERMINAL_STATUSES.contains(run.getStatus())) {
      log.warn(
          "[RemoteTool] rejected tool result after terminal state run={} status={} callId={}",
          runId,
          run.getStatus(),
          body.callId());
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
    String envelope = normalizeEnvelope(body.result()).toString();
    RemoteToolDispatcher.CompletionOutcome outcome =
        dispatcher.complete(runId, body.callId(), envelope);
    return switch (outcome) {
      case ACCEPTED -> ResponseEntity.noContent().build();
      case DUPLICATE -> ResponseEntity.status(HttpStatus.CONFLICT).build();
      case UNKNOWN_SESSION -> ResponseEntity.status(HttpStatus.CONFLICT).build();
      case UNKNOWN_CALL -> ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    };
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
      out.put(
          "content",
          bounded(
              content == null || content.isNull() ? "" : contentText(content), MAX_CONTENT_CHARS));
      return out;
    }
    JsonNode kind = result.get("kind");
    JsonNode message = result.get("message");
    String kindText =
        kind == null || kind.isNull() || kind.asText().isBlank()
            ? "INTERNAL"
            : kind.asText().trim().toUpperCase(java.util.Locale.ROOT);
    out.put("kind", ERROR_KINDS.contains(kindText) ? kindText : "INTERNAL");
    out.put(
        "message",
        message == null || message.isNull() || message.asText().isBlank()
            ? "client tool failed without a message"
            : bounded(message.asText(), MAX_MESSAGE_CHARS));
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

  private String bounded(String value, int maxChars) {
    String redacted = redactSecrets(value == null ? "" : value);
    if (redacted.length() <= maxChars) {
      return redacted;
    }
    return redacted.substring(0, maxChars)
        + "\n[truncated: original_chars="
        + redacted.length()
        + " limit="
        + maxChars
        + "]";
  }

  private String redactSecrets(String value) {
    return SECRET_PATTERN.matcher(value).replaceAll("[REDACTED_SECRET]");
  }

  static boolean isPatAuthenticated(HttpServletRequest request) {
    return request != null
        && JwtAuthenticationFilter.AUTH_SOURCE_PAT.equals(
            request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE));
  }
}
