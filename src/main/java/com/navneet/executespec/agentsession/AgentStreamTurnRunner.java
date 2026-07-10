package com.navneet.executespec.agentsession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AgentStreamTurnRunner {

  private final AgentSessionService sessionService;
  private final SpecChatService specChatService;
  private final FeatureFlagService featureFlags;
  private final AgentAttachmentService attachmentService;

  public void run(
      SseEmitter emitter,
      AgentSessionService.StreamTurnContext ctx,
      AppUserDetails user,
      StreamTurnRequest request) {
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

      // Provider-free scripted stream used only by the local/dev Web release fixture. The
      // session id is minted by WebQaFixtureController and never comes from a customer request.
      if (ctx.sessionId().startsWith("web-qa-")) {
        runWebQaFixtureStream(emitter, ctx);
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

      AccumulatingSink sink = new AccumulatingSink(emitter, sessionService, ctx);
      List<Map<String, Object>> conversation =
          AgentStreamTurnSupport.withAttachmentBlock(
              AgentStreamTurnSupport.conversationOrCurrentMessage(request),
              attachmentService.injectionBlock(ctx.sessionId(), request.attachmentIds()));
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
        sessionService.disconnectStreamTurn(ctx);
        emitter.complete();
        return;
      }
      String message = userFacingStreamError(e, ctx.provider());
      sessionService.failStreamTurn(ctx, message);
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

  private void runWebQaFixtureStream(
      SseEmitter emitter, AgentSessionService.StreamTurnContext ctx) {
    try {
      Thread.sleep(120);
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
      emitter.complete();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      sessionService.disconnectStreamTurn(ctx);
      emitter.complete();
    }
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

  private static String userFacingStreamError(Throwable error, String provider) {
    String raw = error == null ? "" : error.getMessage();
    if (raw == null || raw.isBlank()) {
      return "Agent stream failed.";
    }
    if (!looksLikeProviderCredentialFailure(raw)) {
      return raw;
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
    private final List<String> tokens = new ArrayList<>();

    private AccumulatingSink(
        SseEmitter emitter,
        AgentSessionService sessionService,
        AgentSessionService.StreamTurnContext ctx) {
      this.emitter = emitter;
      this.sessionService = sessionService;
      this.ctx = ctx;
    }

    @Override
    public void token(String delta) {
      tokens.add(delta == null ? "" : delta);
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
      sessionService.completeStreamTurn(ctx, String.join("\n", tokens), cost);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("session_id", ctx.sessionId());
      data.put("turn_id", ctx.turnId());
      data.put("turn_count", turnCount);
      data.put("cost_usd", cost == null ? 0.0 : cost.costUsd());
      data.put("input_tokens", cost == null ? 0 : cost.inputTokens());
      data.put("output_tokens", cost == null ? 0 : cost.outputTokens());
      data.put("reasoning_tokens", cost == null ? 0 : cost.reasoningTokens());
      send(emitter, "done", data);
    }
  }
}
