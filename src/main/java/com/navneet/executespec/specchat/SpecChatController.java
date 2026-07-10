package com.navneet.executespec.specchat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CLI/Plugin chat endpoint — per docs/design/cli-plugin-spec-shape-b-2026-05-28.md Phase 1.
 *
 * <p>{@code POST /api/spec/chat} accepts a full conversation (client sends the whole history each
 * turn — decision #2 chat persistence is LOCAL-ONLY) and returns an SSE stream of {@code token /
 * tool_call / done} events. The server is stateless for chat content; only an audit row per session
 * is persisted (see {@link SpecChatAudit} + V218).
 *
 * <p>Authentication: standard JWT or PAT (the {@code es_pat_...} bearer V215 added). The PAT path
 * is the one the CLI + VS Code plugin use; resolved to {@link AppUserDetails} by {@code
 * JwtAuthenticationFilter}.
 *
 * <p>The controller owns request validation, the audit-row upsert, IP-hash capture, and the SSE
 * emitter lifecycle. The actual agentic loop lives in {@link SpecChatService} and runs only when
 * the {@code spec.chat.enabled} flag is ON (V229, default OFF); when OFF the endpoint returns an
 * explicit not-enabled {@code done} event so the default pipeline is byte-identical.
 *
 * <p>RL-2026-05-052.
 */
@RestController
@RequestMapping("/api/spec")
@RequiredArgsConstructor
@Slf4j
public class SpecChatController {

  private final SpecChatAuditRepository auditRepository;
  private final SpecChatService specChatService;
  private final FeatureFlagService featureFlags;
  private final AiProviderPreferenceService aiProviderPreferenceService;
  private final ProjectAccessGuard projectAccessGuard;

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody SpecChatRequest request,
      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
      HttpServletRequest http) {

    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required (JWT or PAT)");
    }
    if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
    }
    if (request.conversation() == null || request.conversation().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "conversation must contain at least the current turn");
    }

    String orgId =
        userDetails.getOrgMember() != null
            ? String.valueOf(userDetails.getOrgMember().getOrgId())
            : null;
    if (orgId == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "user is not a member of any org — CLI/Plugin chat requires an org context");
    }

    String userId = String.valueOf(userDetails.getUser().getId());
    // Project-scoped chat binds project read tools. Validate the caller before creating the audit
    // row or entering the agent loop so an arbitrary projectId cannot become an IDOR oracle.
    if (request.projectId() != null && !request.projectId().isBlank()) {
      projectAccessGuard.assertCanWrite(request.projectId(), userDetails);
    }
    String ip = clientIp(forwardedFor, http);
    String ipHash = sha256(ip);

    String effectiveProvider =
        request.providerOverride() != null && !request.providerOverride().isBlank()
            ? aiProviderPreferenceService.updatePreferredProvider(
                userDetails, request.providerOverride())
            : aiProviderPreferenceService.preferredProvider(userDetails);

    SpecChatAudit audit = upsertAudit(request, userId, orgId, ipHash, effectiveProvider);

    log.info(
        "spec-chat session={} user={} org={} project={} turn={} ipHash={}",
        request.sessionId(),
        userId,
        orgId,
        request.projectId(),
        audit.getTurnCount(),
        ipHash);

    SseEmitter emitter = new SseEmitter(0L);

    // Flag OFF (default) → explicit not-enabled response; the real loop never runs, so the default
    // pipeline is byte-identical. The audit row above is still written (an unenabled session
    // counts).
    if (!featureFlags.isEnabled("spec.chat.enabled")) {
      try {
        emitter.send(SseEmitter.event().name("ack").data(Map.of("audit_id", audit.getId())));
        emitter.send(
            SseEmitter.event()
                .name("done")
                .data(
                    Map.of(
                        "session_id", request.sessionId(),
                        "audit_id", audit.getId(),
                        "turn_count", audit.getTurnCount(),
                        "note", "spec.chat.enabled is OFF")));
        emitter.complete();
      } catch (Exception e) {
        emitter.completeWithError(e);
      }
      return emitter;
    }

    // Flag ON → run the real agentic loop. Phase 1 runs it inline in the request thread (the
    // feature
    // is flag-gated); SpecChatService documents the async-before-prod requirement.
    try {
      emitter.send(SseEmitter.event().name("ack").data(Map.of("audit_id", audit.getId())));
      specChatService.runTurn(
          sseSink(emitter),
          userDetails,
          request.sessionId(),
          request.projectId(),
          orgId,
          request.conversation(),
          effectiveProvider);
      emitter.complete();
    } catch (Exception e) {
      log.warn(
          "spec-chat turn failed (session {}, user {}): {}",
          request.sessionId(),
          userId,
          e.getMessage());
      emitter.completeWithError(e);
    }
    return emitter;
  }

  /** Audit row read endpoint — caller can verify a session exists + its current state. */
  @org.springframework.web.bind.annotation.GetMapping("/chat/audit/{sessionId}")
  public ResponseEntity<SpecChatAudit> getAudit(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @org.springframework.web.bind.annotation.PathVariable String sessionId) {
    if (userDetails == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
    }
    return auditRepository
        .findBySessionId(sessionId)
        .map(
            a -> {
              if (!String.valueOf(userDetails.getUser().getId()).equals(a.getUserId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
              }
              return ResponseEntity.ok(a);
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private SpecChatAudit upsertAudit(
      SpecChatRequest request, String userId, String orgId, String ipHash, String provider) {
    return auditRepository
        .findBySessionId(request.sessionId())
        .map(
            existing -> {
              existing.setLastTurnAt(Instant.now());
              existing.setTurnCount(existing.getTurnCount() + 1);
              if (provider != null) {
                existing.setModelUsed(provider);
              }
              return auditRepository.save(existing);
            })
        .orElseGet(
            () ->
                auditRepository.save(
                    SpecChatAudit.builder()
                        .id(UUID.randomUUID().toString())
                        .sessionId(request.sessionId())
                        .projectId(request.projectId())
                        .userId(userId)
                        .orgId(orgId)
                        .turnCount(1)
                        .ipHash(ipHash)
                        .modelUsed(provider)
                        .build()));
  }

  /** Adapt {@link SpecChatService.Sink} to this request's SSE emitter (null-safe data maps). */
  private SpecChatService.Sink sseSink(SseEmitter emitter) {
    return new SpecChatService.Sink() {
      @Override
      public void token(String delta) {
        send(emitter, "token", Map.of("delta", delta == null ? "" : delta));
      }

      @Override
      public void toolCall(String tool, String argsJson, String id) {
        send(
            emitter,
            "tool_call",
            Map.of(
                "tool", tool == null ? "" : tool,
                "args", argsJson == null ? "" : argsJson,
                "id", id == null ? "" : id));
      }

      @Override
      public void toolResult(String id, String resultJson) {
        send(
            emitter,
            "tool_result",
            Map.of("id", id == null ? "" : id, "result", resultJson == null ? "" : resultJson));
      }

      @Override
      public void done(int turnCount, RunCostTracker.Snapshot cost) {
        send(
            emitter,
            "done",
            Map.of(
                "turn_count", turnCount,
                "cost_usd", cost.costUsd(),
                "input_tokens", cost.inputTokens(),
                "output_tokens", cost.outputTokens()));
      }
    };
  }

  /**
   * Send one SSE event; a client-disconnect IOException surfaces to the caller's completeWithError.
   */
  private static void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (IOException io) {
      throw new UncheckedIOException(io);
    }
  }

  private static String clientIp(String forwardedFor, HttpServletRequest http) {
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      return forwardedFor.split(",")[0].trim();
    }
    return http.getRemoteAddr();
  }

  private static String sha256(String s) {
    if (s == null) return null;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      return null;
    }
  }

  /** Request body for {@code POST /api/spec/chat}. */
  public record SpecChatRequest(
      String sessionId,
      String projectId,
      Map<String, Object> localContext,
      List<Map<String, Object>> conversation,
      String providerOverride) {}
}
