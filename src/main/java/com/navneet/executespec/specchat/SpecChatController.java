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

import com.navneet.executespec.agentsession.AgentIntentService;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.InputSanitizer;

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
 * <p>The enabled loop is dispatched through {@link SpecChatTurnDispatcher} — bounded globally and
 * per user — instead of running on the servlet thread, so provider latency cannot consume the HTTP
 * worker pool. Over-ceiling turns are rejected with HTTP 429 before any emitter is streamed.
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
  private final AgentIntentService agentIntentService;
  private final InputSanitizer inputSanitizer;
  private final ContextSanitizer contextSanitizer;
  private final ChatSecurityAuditService chatSecurityAuditService;
  private final SpecChatTurnDispatcher turnDispatcher;

  /**
   * Hard ceiling on one compatibility chat turn. The old emitter had no timeout at all, so a wedged
   * provider call held the response open indefinitely. Raised 2026-08-08 (10 → 20 min) for slow
   * models: a single slow-model turn can consume the ~11-min transport window, so 10 min could cut
   * one legitimate turn plus a retry. Stays under the 30-min servlet async ceiling.
   */
  static final long TURN_TIMEOUT_MS = 20 * 60 * 1000L;

  @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chat(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestBody SpecChatRequest request,
      @RequestHeader(value = "X-Forwarded-For", required = false) String ignoredForwardedFor,
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
    try {
      request = sanitizeRequest(request);
    } catch (RuntimeException denied) {
      chatSecurityAuditService.record(
          "CHAT_INPUT_DENIED",
          request.sessionId(),
          request.projectId(),
          userDetails,
          "spec_chat_input",
          ChatToolPolicy.Capability.SPEND_AI,
          "DENIED");
      throw denied;
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
    // RR-875 — a project-scoped chat may be driven by an external collaborator whose home org is
    // not
    // the project's owner. Security-audit events for that project must be filed under the org that
    // OWNS the host project, not the actor's home org, or the host tenant's compliance queries miss
    // allowed/tool-use activity on its own project. The authorized Project returned by the guard is
    // the authoritative host-org source. Null for non-project sessions (falls back to actor org).
    String hostOrgId = null;
    // Project-scoped chat binds project read tools. Validate the caller before creating the audit
    // row or entering the agent loop so an arbitrary projectId cannot become an IDOR oracle.
    if (request.projectId() != null && !request.projectId().isBlank()) {
      try {
        var hostProject = projectAccessGuard.assertCanWrite(request.projectId(), userDetails);
        hostOrgId = hostProject == null ? null : hostProject.getOrgId();
      } catch (RuntimeException denied) {
        chatSecurityAuditService.record(
            "CHAT_TURN_DENIED",
            request.sessionId(),
            request.projectId(),
            userDetails,
            "spec_chat_turn",
            ChatToolPolicy.Capability.SPEND_AI,
            "DENIED");
        throw denied;
      }
    }
    final String hostOrgIdFinal = hostOrgId;
    String ip = clientIp(http);
    String ipHash = sha256(ip);

    String effectiveProvider =
        request.providerOverride() != null && !request.providerOverride().isBlank()
            ? aiProviderPreferenceService.updatePreferredProvider(
                userDetails, request.providerOverride())
            : aiProviderPreferenceService.preferredProvider(userDetails);

    SpecChatAudit audit = upsertAudit(request, userId, orgId, ipHash, effectiveProvider);
    chatSecurityAuditService.record(
        "CHAT_TURN_ALLOWED",
        request.sessionId(),
        request.projectId(),
        userDetails,
        "spec_chat_turn",
        ChatToolPolicy.Capability.SPEND_AI,
        "ALLOWED",
        hostOrgIdFinal);

    log.info(
        "spec-chat session={} user={} org={} project={} turn={} ipHash={}",
        request.sessionId(),
        userId,
        orgId,
        request.projectId(),
        audit.getTurnCount(),
        ipHash);

    SseEmitter emitter = new SseEmitter(TURN_TIMEOUT_MS);

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

    // Flag ON → run the real agentic loop OFF the servlet thread. The loop performs multiple
    // provider rounds plus outbound web calls; running it inline pinned one Tomcat worker (and its
    // connection) for that entire duration, so a handful of slow turns could exhaust the web tier
    // and block auth/billing/run-control. Admission is bounded per user and per JVM.
    final SpecChatRequest turnRequest = request;
    boolean admitted =
        turnDispatcher.submit(
            userId,
            request.sessionId(),
            () ->
                runTurnOffRequestThread(
                    emitter,
                    turnRequest,
                    userDetails,
                    userId,
                    orgId,
                    hostOrgIdFinal,
                    effectiveProvider,
                    audit.getId()));
    if (!admitted) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS,
          "too many concurrent chat turns — wait for the current turn to finish");
    }
    return emitter;
  }

  private void runTurnOffRequestThread(
      SseEmitter emitter,
      SpecChatRequest request,
      AppUserDetails userDetails,
      String userId,
      String orgId,
      String hostOrgId,
      String effectiveProvider,
      String auditId) {
    try {
      emitter.send(SseEmitter.event().name("ack").data(Map.of("audit_id", auditId)));
      // Emit the same typed route evidence used by the v2 agent-session endpoint. This keeps the
      // compatibility path observable without granting the legacy chat loop any new authority:
      // the route classifier can only propose a route, while existing chat tools still enforce
      // action and plan-gate policy.
      emitRouteDecision(emitter, request, effectiveProvider, userDetails, hostOrgId);
      specChatService.runTurn(
          sseSink(emitter, userDetails, request, hostOrgId),
          userDetails,
          request.sessionId(),
          request.projectId(),
          orgId,
          request.conversation(),
          effectiveProvider);
      emitter.complete();
    } catch (Exception e) {
      // A client disconnect surfaces here as an IO failure from the sink and stops the loop, which
      // is the intended cancellation: no further provider round is started for a gone caller.
      log.warn(
          "spec-chat turn failed (session {}, user {}): {}",
          request.sessionId(),
          userId,
          contextSanitizer.sanitizeException(e));
      try {
        emitter.completeWithError(e);
      } catch (RuntimeException ignored) {
        log.debug("spec-chat emitter already closed for session {}", request.sessionId());
      }
    }
  }

  private void emitRouteDecision(
      SseEmitter emitter,
      SpecChatRequest request,
      String effectiveProvider,
      AppUserDetails principal,
      String hostOrgId) {
    try {
      OrchestratorDecision decision =
          agentIntentService.decide(
              new InterpretIntentRequest(
                  request.sessionId(),
                  latestUserMessage(request.conversation()),
                  request.projectId(),
                  null,
                  new ClientCapabilities(false, false, false, false)),
              effectiveProvider,
              null);
      send(
          emitter,
          "route_decision",
          Map.of(
              "route", decision.route(),
              "confidence", decision.confidence(),
              "reason_code", decision.reasonCode(),
              "requires_user_confirmation", decision.requiresUserConfirmation(),
              "assistant_reply", decision.assistantReply() == null ? "" : decision.assistantReply(),
              "trace_id", decision.traceId() == null ? "" : decision.traceId()));
      chatSecurityAuditService.record(
          decision.requiresUserConfirmation()
              ? "CHAT_CONFIRMATION_REQUIRED"
              : "CHAT_ROUTE_DECISION",
          request.sessionId(),
          request.projectId(),
          principal,
          decision.route(),
          decision.policy() != null && decision.policy().runApiAllowed()
              ? ChatToolPolicy.Capability.SPEND_AI
              : ChatToolPolicy.Capability.PROJECT_READ,
          decision.requiresUserConfirmation() ? "REQUIRED" : "ROUTED",
          hostOrgId);
    } catch (RuntimeException e) {
      // Advisory route evidence must not turn a compatibility chat request into an outage.
      log.debug(
          "legacy spec-chat route evidence unavailable: {}", contextSanitizer.sanitizeException(e));
    }
  }

  private static String latestUserMessage(List<Map<String, Object>> conversation) {
    for (int i = conversation.size() - 1; i >= 0; i--) {
      Map<String, Object> turn = conversation.get(i);
      if (turn == null || !"user".equalsIgnoreCase(String.valueOf(turn.get("role")))) continue;
      Object content = turn.get("content");
      if (content instanceof String text && !text.isBlank()) return text.trim();
      if (content != null) return String.valueOf(content).trim();
    }
    return "";
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
              if (!userId.equals(existing.getUserId()) || !orgId.equals(existing.getOrgId())) {
                throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "session is not owned by caller");
              }
              String requestedProject =
                  request.projectId() == null || request.projectId().isBlank()
                      ? null
                      : request.projectId();
              if (!java.util.Objects.equals(requestedProject, existing.getProjectId())) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "session project scope cannot be changed");
              }
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
  private SpecChatService.Sink sseSink(
      SseEmitter emitter, AppUserDetails principal, SpecChatRequest request, String hostOrgId) {
    return new SpecChatService.Sink() {
      private final java.util.Map<String, String> toolById =
          new java.util.concurrent.ConcurrentHashMap<>();

      @Override
      public void token(String delta) {
        send(
            emitter,
            "token",
            Map.of("delta", delta == null ? "" : contextSanitizer.sanitizeOutput(delta)));
      }

      @Override
      public void toolCall(String tool, String argsJson, String id) {
        if (id != null && tool != null) toolById.put(id, tool);
        chatSecurityAuditService.record(
            "CHAT_TOOL_REQUESTED",
            request.sessionId(),
            request.projectId(),
            principal,
            tool,
            ChatToolPolicy.capability(tool),
            "REQUESTED",
            hostOrgId);
        send(
            emitter,
            "tool_call",
            Map.of(
                "tool", tool == null ? "" : tool,
                "args", argsJson == null ? "" : contextSanitizer.sanitizeOutput(argsJson),
                "id", id == null ? "" : id));
      }

      @Override
      public void toolResult(String id, String resultJson) {
        String tool = id == null ? null : toolById.remove(id);
        chatSecurityAuditService.record(
            "CHAT_TOOL_RESULT",
            request.sessionId(),
            request.projectId(),
            principal,
            tool,
            ChatToolPolicy.capability(tool),
            resultJson != null && resultJson.contains("\"ok\":true") ? "OK" : "ERROR",
            hostOrgId);
        send(
            emitter,
            "tool_result",
            Map.of(
                "id",
                id == null ? "" : id,
                "result",
                resultJson == null ? "" : contextSanitizer.sanitizeOutput(resultJson)));
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

  private SpecChatRequest sanitizeRequest(SpecChatRequest request) {
    List<Map<String, Object>> sanitized = new java.util.ArrayList<>();
    for (Map<String, Object> turn : request.conversation()) {
      if (turn == null) continue;
      Map<String, Object> copy = new java.util.LinkedHashMap<>(turn);
      String role = String.valueOf(copy.getOrDefault("role", "user"));
      Object raw = copy.get("content");
      String content = raw == null ? "" : inputSanitizer.normalize(String.valueOf(raw));
      if ("user".equalsIgnoreCase(role)) {
        // The legacy marker is client-authored compatibility metadata, not an attestation. Scan
        // the complete payload before splitting it so a forged marker cannot bypass ingress.
        inputSanitizer.scan(content);
        int localContext = content.indexOf("EXECUTESPEC_LOCAL_CONTEXT:");
        String instruction = localContext < 0 ? content : content.substring(0, localContext);
        if (localContext >= 0) {
          content =
              instruction
                  + "\n\n"
                  + com.navneet.executespec.agent.PromptTrustBoundary.wrap(
                      "LOCAL_CONTEXT",
                      contextSanitizer.sanitizeInput(
                          content.substring(localContext), "legacy-local-context"));
        }
      } else if ("assistant".equalsIgnoreCase(role)) {
        content = contextSanitizer.sanitizeOutput(content);
      } else {
        content = contextSanitizer.sanitizeInput(content, "legacy-client-context");
      }
      copy.put("content", content);
      sanitized.add(java.util.Collections.unmodifiableMap(copy));
    }
    return new SpecChatRequest(
        request.sessionId(),
        request.projectId(),
        request.localContext(),
        List.copyOf(sanitized),
        request.providerOverride());
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

  private static String clientIp(HttpServletRequest http) {
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
