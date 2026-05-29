package com.navneet.executespec.specchat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
 * <p><b>Phase 1 scope (this commit):</b> request validation, audit-row upsert, IP-hash capture,
 * placeholder SSE-emitter response. The full chat loop (LlmRouter dispatch + tool execution) lands
 * in a follow-up commit per the design doc phase plan.
 *
 * <p>RL-2026-05-052.
 */
@RestController
@RequestMapping("/api/spec")
@RequiredArgsConstructor
@Slf4j
public class SpecChatController {

  private final SpecChatAuditRepository auditRepository;

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody SpecChatRequest request,
      @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
      HttpServletRequest http) {

    if (userDetails == null) {
      throw new IllegalStateException("auth required (JWT or PAT)");
    }
    if (request == null || request.sessionId() == null || request.sessionId().isBlank()) {
      throw new IllegalArgumentException("sessionId is required");
    }
    if (request.conversation() == null || request.conversation().isEmpty()) {
      throw new IllegalArgumentException("conversation must contain at least the current turn");
    }

    String orgId =
        userDetails.getOrgMember() != null
            ? String.valueOf(userDetails.getOrgMember().getOrgId())
            : null;
    if (orgId == null) {
      throw new IllegalStateException(
          "user is not a member of any org — CLI/Plugin chat requires an org context");
    }

    String userId = String.valueOf(userDetails.getUser().getId());
    String ip = clientIp(forwardedFor, http);
    String ipHash = sha256(ip);

    SpecChatAudit audit = upsertAudit(request, userId, orgId, ipHash);

    log.info(
        "spec-chat session={} user={} org={} project={} turn={} ipHash={}",
        request.sessionId(),
        userId,
        orgId,
        request.projectId(),
        audit.getTurnCount(),
        ipHash);

    SseEmitter emitter = new SseEmitter(0L);
    try {
      emitter.send(SseEmitter.event().name("ack").data(Map.of("audit_id", audit.getId())));
      emitter.send(
          SseEmitter.event()
              .name("token")
              .data(
                  Map.of(
                      "delta",
                      "[Phase 1 skeleton — chat loop lands in follow-up commit per"
                          + " RL-2026-05-052]")));
      emitter.send(
          SseEmitter.event()
              .name("done")
              .data(
                  Map.of(
                      "session_id",
                      request.sessionId(),
                      "audit_id",
                      audit.getId(),
                      "turn_count",
                      audit.getTurnCount())));
      emitter.complete();
    } catch (Exception e) {
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
      throw new IllegalStateException("auth required");
    }
    return auditRepository
        .findBySessionId(sessionId)
        .map(
            a -> {
              if (!String.valueOf(userDetails.getUser().getId()).equals(a.getUserId())) {
                throw new IllegalStateException("forbidden");
              }
              return ResponseEntity.ok(a);
            })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  private SpecChatAudit upsertAudit(
      SpecChatRequest request, String userId, String orgId, String ipHash) {
    return auditRepository
        .findBySessionId(request.sessionId())
        .map(
            existing -> {
              existing.setLastTurnAt(Instant.now());
              existing.setTurnCount(existing.getTurnCount() + 1);
              if (request.providerOverride() != null) {
                existing.setModelUsed(request.providerOverride());
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
                        .modelUsed(request.providerOverride())
                        .build()));
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
