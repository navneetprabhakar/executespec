package com.navneet.executespec.agentsession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class AgentStreamTurnRunner {

  private final AgentSessionService sessionService;
  private final SpecChatService specChatService;
  private final FeatureFlagService featureFlags;
  private final AgentAttachmentService attachmentService;
  private final AgentEventRecorder eventRecorder;
  private final RunCostTracker costTracker;
  private final org.springframework.core.env.Environment environment;

  /** Compatibility constructor retained for focused tests and older package-local callers. */
  AgentStreamTurnRunner(
      AgentSessionService sessionService,
      SpecChatService specChatService,
      FeatureFlagService featureFlags,
      AgentAttachmentService attachmentService) {
    this(sessionService, specChatService, featureFlags, attachmentService, null);
  }

  AgentStreamTurnRunner(
      AgentSessionService sessionService,
      SpecChatService specChatService,
      FeatureFlagService featureFlags,
      AgentAttachmentService attachmentService,
      AgentEventRecorder eventRecorder) {
    this(sessionService, specChatService, featureFlags, attachmentService, eventRecorder, null);
  }

  AgentStreamTurnRunner(
      AgentSessionService sessionService,
      SpecChatService specChatService,
      FeatureFlagService featureFlags,
      AgentAttachmentService attachmentService,
      AgentEventRecorder eventRecorder,
      RunCostTracker costTracker) {
    this(
        sessionService,
        specChatService,
        featureFlags,
        attachmentService,
        eventRecorder,
        costTracker,
        null);
  }

  @Autowired
  AgentStreamTurnRunner(
      AgentSessionService sessionService,
      SpecChatService specChatService,
      FeatureFlagService featureFlags,
      AgentAttachmentService attachmentService,
      AgentEventRecorder eventRecorder,
      RunCostTracker costTracker,
      org.springframework.core.env.Environment environment) {
    this.sessionService = sessionService;
    this.specChatService = specChatService;
    this.featureFlags = featureFlags;
    this.attachmentService = attachmentService;
    this.eventRecorder = eventRecorder;
    this.costTracker = costTracker;
    this.environment = environment;
  }

  public void run(
      SseEmitter emitter,
      AgentSessionService.StreamTurnContext ctx,
      AppUserDetails user,
      StreamTurnRequest request) {
    AccumulatingSink sink = null;
    StringBuilder fixtureText = new StringBuilder();
    // RR-488 / RR-1453 — the per-turn cost baseline. Success settles the delta computed inside
    // SpecChatService, but disconnect and failure happen out here after that internal baseline is
    // lost. Snapshot the scope before dispatch so a terminal path records only this turn's spend
    // instead of the whole run/session cumulative total (or zero).
    RunCostTracker.Snapshot turnBaseline = null;
    try {
      send(
          emitter,
          "ack",
          Map.of(
              "session_id", ctx.sessionId(),
              "turn_id", ctx.turnId(),
              "sequence", ctx.sequence()));
      send(
          emitter,
          "route_decision",
          Map.of(
              "session_id", ctx.sessionId(),
              "turn_id", ctx.turnId(),
              "route", ctx.decision().route(),
              "confidence", ctx.decision().confidence(),
              "reason_code", ctx.decision().reasonCode(),
              "run_api_allowed", ctx.decision().policy().runApiAllowed(),
              "requires_confirmation", ctx.decision().requiresUserConfirmation()));
      record(
          ctx,
          "ROUTE_DECISION",
          Map.of(
              "route", ctx.decision().route(),
              "confidence", ctx.decision().confidence(),
              "reason_code", ctx.decision().reasonCode(),
              "run_api_allowed", ctx.decision().policy().runApiAllowed(),
              "requires_confirmation", ctx.decision().requiresUserConfirmation()));

      // Provider-free scripted stream used only by the local/dev Web release fixture. RR-487:
      // admission rejects a customer-minted `web-qa-` id (AgentSessionCreationService), and this
      // branch additionally fails closed in any non-local/dev runtime so a stray fixture session
      // can
      // never emit fabricated tool/verification evidence in production.
      if (ctx.sessionId().startsWith("web-qa-")) {
        if (fixtureProfileActive()) {
          runWebQaFixtureStream(emitter, ctx, fixtureText);
          return;
        }
        String note = "fixture stream unavailable in this environment";
        sessionService.failStreamTurn(ctx, note);
        try {
          send(
              emitter,
              "error",
              Map.of("session_id", ctx.sessionId(), "turn_id", ctx.turnId(), "message", note));
        } catch (Exception ignored) {
          // emitter already broken
        }
        emitter.complete();
        return;
      }

      if (!featureFlags.isEnabled("spec.chat.enabled")) {
        String note = "spec.chat.enabled is OFF";
        sessionService.completeStreamTurn(
            ctx, note, new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
        send(
            emitter,
            "done",
            Map.of(
                "session_id", ctx.sessionId(),
                "turn_id", ctx.turnId(),
                "turn_count", ctx.sequence(),
                "note", note));
        emitter.complete();
        return;
      }

      if (ctx.decision().requiresUserConfirmation()
          || "CLARIFY".equals(ctx.decision().route())
          || "SECURITY_REJECT".equals(ctx.decision().route())
          || "LOCAL_TOOL_ACTION".equals(ctx.decision().route())) {
        finishDecisionOnlyTurn(emitter, ctx);
        return;
      }

      turnBaseline = latestCost(ctx);
      sink = new AccumulatingSink(emitter, sessionService, ctx, eventRecorder);
      List<Map<String, Object>> conversation =
          AgentStreamTurnSupport.withAttachmentBlock(
              AgentStreamTurnSupport.conversationOrCurrentMessage(request),
              attachmentService.injectionBlock(ctx.sessionId(), request.attachmentIds()));
      conversation =
          attachmentService.withMediaAttachments(
              conversation, ctx.sessionId(), request.attachmentIds());
      if (ctx.model() == null || ctx.model().isBlank()) {
        specChatService.runTurn(
            sink,
            user,
            ctx.sessionId(),
            ctx.projectId(),
            ctx.runId(),
            ctx.orgId(),
            conversation,
            ctx.provider(),
            null,
            AgentStreamTurnSupport.allowedReadOnlyTools(),
            ctx.turnId());
      } else {
        specChatService.runTurn(
            sink,
            user,
            ctx.sessionId(),
            ctx.projectId(),
            ctx.runId(),
            ctx.orgId(),
            conversation,
            ctx.provider(),
            ctx.model(),
            AgentStreamTurnSupport.allowedReadOnlyTools(),
            ctx.turnId());
      }
      emitter.complete();
    } catch (Exception e) {
      if (AgentStreamTurnSupport.isClientDisconnect(e)) {
        String partial = sink == null ? partialText(fixtureText) : sink.partialSummary();
        sessionService.disconnectStreamTurn(ctx, partial, turnDelta(turnBaseline, latestCost(ctx)));
        emitter.complete();
        return;
      }
      String message = userFacingStreamError(e, ctx.provider());
      // RR-1453 — a paid provider/tool failure must still settle this turn's usage, not zero.
      sessionService.failStreamTurn(ctx, message, turnDelta(turnBaseline, latestCost(ctx)));
      try {
        send(
            emitter,
            "error",
            Map.of(
                "session_id", ctx.sessionId(),
                "turn_id", ctx.turnId(),
                "message", message));
      } catch (Exception ignored) {
        // emitter is already broken
      }
      // The response is already committed as text/event-stream. Completing with an exception
      // re-enters Spring's JSON exception handlers and produces a second broken response.
      emitter.complete();
    }
  }

  private RunCostTracker.Snapshot latestCost(AgentSessionService.StreamTurnContext ctx) {
    if (costTracker == null) return null;
    String scope = ctx.runId() == null || ctx.runId().isBlank() ? ctx.sessionId() : ctx.runId();
    return costTracker.snapshot(scope);
  }

  /**
   * RR-488 — the monotonic per-turn delta between a pre-dispatch baseline and the current scope
   * snapshot. Clamped at zero so a resettable/replica snapshot can never record negative usage, and
   * null-safe so a missing tracker or baseline degrades to a zero-cost settlement rather than
   * attributing cumulative run/session cost to one interrupted turn.
   */
  private static RunCostTracker.Snapshot turnDelta(
      RunCostTracker.Snapshot before, RunCostTracker.Snapshot after) {
    if (after == null) return null;
    if (before == null) return after;
    return new RunCostTracker.Snapshot(
        Math.max(0, after.inputTokens() - before.inputTokens()),
        Math.max(0, after.outputTokens() - before.outputTokens()),
        Math.max(0, after.cacheWriteTokens() - before.cacheWriteTokens()),
        Math.max(0, after.cacheReadTokens() - before.cacheReadTokens()),
        Math.max(0.0, after.costUsd() - before.costUsd()),
        Math.max(0, after.calls() - before.calls()),
        Math.max(0, after.reasoningTokens() - before.reasoningTokens()));
  }

  /**
   * RR-487 — the provider-free fixture stream is only allowed when the local or dev profile is
   * active. A null environment (focused test constructor) keeps the fixture behaviour tests depend
   * on; a real runtime that is not local/dev fails closed.
   */
  private boolean fixtureProfileActive() {
    if (environment == null) return true;
    return environment.acceptsProfiles(
        org.springframework.core.env.Profiles.of("local", "dev", "test"));
  }

  private void runWebQaFixtureStream(
      SseEmitter emitter, AgentSessionService.StreamTurnContext ctx, StringBuilder partialText) {
    try {
      Thread.sleep(120);
      appendPartial(partialText, "Fixture stream started. ");
      send(emitter, "token", Map.of("delta", "Fixture stream started. "));
      Thread.sleep(120);
      send(
          emitter,
          "tool_call",
          Map.of("tool", "readFile", "args", "{\"path\":\"src/app.ts\"}", "id", "web-qa-read"));
      Thread.sleep(120);
      send(
          emitter,
          "tool_result",
          Map.of("id", "web-qa-read", "result", "{\"ok\":true,\"path\":\"src/app.ts\"}"));
      Thread.sleep(120);
      appendPartial(partialText, "Verification evidence is ready for review.");
      send(emitter, "token", Map.of("delta", "Verification evidence is ready for review."));
      sessionService.completeStreamTurn(
          ctx,
          "Fixture stream started. Verification evidence is ready for review.",
          new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0));
      send(
          emitter,
          "done",
          Map.of(
              "session_id",
              ctx.sessionId(),
              "turn_id",
              ctx.turnId(),
              "turn_count",
              ctx.sequence(),
              "cost_usd",
              0.0));
      record(ctx, "TURN_DONE", Map.of("turn_count", ctx.sequence(), "cost_usd", 0.0));
      emitter.complete();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      sessionService.disconnectStreamTurn(ctx, partialText(partialText));
      emitter.complete();
    }
  }

  private static void appendPartial(StringBuilder target, String value) {
    if (target != null && value != null) target.append(value);
  }

  private static String partialText(StringBuilder value) {
    return value == null || value.isEmpty() ? null : value.toString();
  }

  private void finishDecisionOnlyTurn(
      SseEmitter emitter, AgentSessionService.StreamTurnContext ctx) {
    String summary = ctx.decision().assistantReply() == null ? "" : ctx.decision().assistantReply();
    if (ctx.decision().proposedAction() != null) {
      var action = ctx.decision().proposedAction();
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("session_id", ctx.sessionId());
      payload.put("turn_id", ctx.turnId());
      payload.put("route", ctx.decision().route());
      payload.put("action", action);
      payload.put("action_type", action.type());
      payload.put("requires_confirmation", action.requiresConfirmation());
      payload.put("summary", action.confirmationPrompt());
      payload.put("spec", AgentActionArguments.stringArg(action.arguments(), "spec"));
      record(ctx, "PROPOSAL", payload);
      send(emitter, "proposal", payload);
    }
    if (!summary.isBlank()) {
      send(emitter, "token", Map.of("delta", summary));
    }
    sessionService.completeStreamTurn(
        ctx,
        summary,
        new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0),
        ctx.decision().requiresUserConfirmation() ? "NEEDS_CONFIRMATION" : "OK");
    if (!summary.isBlank()) record(ctx, "TURN_TEXT", boundedTextPayload(summary));
    record(
        ctx,
        "TURN_DONE",
        Map.of(
            "turn_count",
            ctx.sequence(),
            "cost_usd",
            0.0,
            "status",
            ctx.decision().requiresUserConfirmation() ? "NEEDS_CONFIRMATION" : "OK"));
    send(
        emitter,
        "done",
        Map.of(
            "session_id",
            ctx.sessionId(),
            "turn_id",
            ctx.turnId(),
            "turn_count",
            ctx.sequence(),
            "cost_usd",
            0.0,
            "input_tokens",
            0,
            "output_tokens",
            0,
            "reasoning_tokens",
            0));
    emitter.complete();
  }

  private static void send(SseEmitter emitter, String event, Object data) {
    try {
      emitter.send(SseEmitter.event().name(event).data(data));
    } catch (IOException io) {
      throw new UncheckedIOException(io);
    }
  }

  private void record(AgentSessionService.StreamTurnContext ctx, String type, Object payload) {
    if (eventRecorder != null) {
      eventRecorder.append(ctx.sessionId(), ctx.turnId(), type, payload);
    }
  }

  private static Map<String, Object> boundedTextPayload(String text) {
    final int maxChars = 16_000;
    boolean truncated = text.length() > maxChars;
    return Map.of("text", truncated ? text.substring(0, maxChars) : text, "truncated", truncated);
  }

  private static String userFacingStreamError(Throwable error, String provider) {
    String raw = error == null ? "" : error.getMessage();
    if (raw == null || raw.isBlank()) {
      return "Agent stream failed.";
    }
    if (!looksLikeProviderCredentialFailure(raw)) {
      return new com.navneet.executespec.security.ContextSanitizer().sanitizeException(error);
    }
    String providerLabel =
        provider == null || provider.isBlank() ? "Selected provider" : provider.trim();
    return providerLabel
        + " authentication failed. Check the provider key in Settings > Provider keys, switch to a"
        + " configured provider, or use managed AI credits before retrying.";
  }

  private static boolean looksLikeProviderCredentialFailure(String raw) {
    String normalized = raw.toLowerCase(Locale.ROOT);
    return normalized.contains("invalid x-api-key")
        || normalized.contains("invalid api key")
        || normalized.contains("api key")
        || normalized.contains("unauthorized")
        || normalized.contains("authentication")
        || normalized.contains("401");
  }

  private static final class AccumulatingSink implements SpecChatService.Sink {
    private final SseEmitter emitter;
    private final AgentSessionService sessionService;
    private final AgentSessionService.StreamTurnContext ctx;
    private final AgentEventRecorder eventRecorder;
    // RR-492 — a single bounded streaming buffer. The prior code also appended every full delta to
    // an unbounded List<String> and joined it as the persisted completion summary, so the 16k audit
    // bound did not protect the actual completion path. This buffer is both the audit text and the
    // persisted summary.
    private final StringBuilder text = new StringBuilder();
    private boolean textTruncated;

    private AccumulatingSink(
        SseEmitter emitter,
        AgentSessionService sessionService,
        AgentSessionService.StreamTurnContext ctx,
        AgentEventRecorder eventRecorder) {
      this.emitter = emitter;
      this.sessionService = sessionService;
      this.ctx = ctx;
      this.eventRecorder = eventRecorder;
    }

    @Override
    public void token(String delta) {
      String safeDelta = delta == null ? "" : delta;
      final int maxChars = 16_000;
      int remaining = maxChars - text.length();
      if (remaining > 0) text.append(safeDelta, 0, Math.min(remaining, safeDelta.length()));
      if (safeDelta.length() > Math.max(remaining, 0)) textTruncated = true;
      send(emitter, "token", Map.of("delta", safeDelta));
    }

    @Override
    public void toolCall(String tool, String argsJson, String id) {
      record(
          "TOOL_CALL",
          Map.of(
              "tool", tool == null ? "" : tool,
              "args", argsJson == null ? "" : argsJson,
              "id", id == null ? "" : id));
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
      record(
          "TOOL_RESULT",
          Map.of("id", id == null ? "" : id, "result", resultJson == null ? "" : resultJson));
      send(
          emitter,
          "tool_result",
          Map.of("id", id == null ? "" : id, "result", resultJson == null ? "" : resultJson));
    }

    @Override
    public void done(int turnCount, RunCostTracker.Snapshot cost) {
      // RR-492 — persist the bounded buffer, never an unbounded join of every delta.
      sessionService.completeStreamTurn(ctx, text.toString(), cost);
      if (text.length() > 0) {
        record("TURN_TEXT", Map.of("text", text.toString(), "truncated", textTruncated));
      }
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("session_id", ctx.sessionId());
      data.put("turn_id", ctx.turnId());
      data.put("turn_count", turnCount);
      data.put("cost_usd", cost == null ? 0.0 : cost.costUsd());
      data.put("input_tokens", cost == null ? 0 : cost.inputTokens());
      data.put("output_tokens", cost == null ? 0 : cost.outputTokens());
      data.put("reasoning_tokens", cost == null ? 0 : cost.reasoningTokens());
      record("TURN_DONE", data);
      send(emitter, "done", data);
    }

    private void record(String type, Object payload) {
      if (eventRecorder != null) {
        eventRecorder.append(ctx.sessionId(), ctx.turnId(), type, payload);
      }
    }

    private String partialSummary() {
      if (text.isEmpty()) return null;
      return text.toString();
    }
  }
}
