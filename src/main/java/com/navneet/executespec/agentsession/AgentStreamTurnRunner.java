package com.navneet.executespec.agentsession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
      specChatService.runTurn(
          sink,
          user,
          ctx.sessionId(),
          ctx.projectId(),
          ctx.runId(),
          ctx.orgId(),
          conversation,
          ctx.provider(),
          AgentStreamTurnSupport.allowedReadOnlyTools());
      emitter.complete();
    } catch (Exception e) {
      if (AgentStreamTurnSupport.isClientDisconnect(e)) {
        sessionService.disconnectStreamTurn(ctx);
        emitter.complete();
        return;
      }
      sessionService.failStreamTurn(ctx, e.getMessage());
      try {
        send(
            emitter,
            "error",
            Map.of(
                "session_id", ctx.sessionId(),
                "turn_id", ctx.turnId(),
                "message", e.getMessage() == null ? "agent stream failed" : e.getMessage()));
      } catch (Exception ignored) {
        // emitter is already broken
      }
      emitter.completeWithError(e);
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
