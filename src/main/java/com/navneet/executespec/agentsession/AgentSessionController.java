package com.navneet.executespec.agentsession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionEventRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionRecordResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentActionDescriptor;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CapabilityTool;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.GateDecisionCard;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSessionController {

  private final AgentSessionService sessionService;
  private final AgentActionCatalog actionCatalog;
  private final SpecChatService specChatService;
  private final FeatureFlagService featureFlags;
  private final com.navneet.executespec.auth.service.MailService mailService;

  @GetMapping("/actions")
  public List<AgentActionDescriptor> actions() {
    return actionCatalog.list();
  }

  @GetMapping("/v2/capabilities")
  public List<CapabilityTool> capabilities(
      @AuthenticationPrincipal AppUserDetails user,
      @RequestParam(defaultValue = "CLI") String surface) {
    return sessionService.capabilities(user, surface);
  }

  @PostMapping("/sessions")
  public AgentSessionResponse createSession(
      @AuthenticationPrincipal AppUserDetails user, @RequestBody CreateSessionRequest request) {
    return sessionService.createSession(request, user);
  }

  @GetMapping("/sessions")
  public List<AgentSessionResponse> sessions(
      @AuthenticationPrincipal AppUserDetails user, @RequestParam(defaultValue = "20") int limit) {
    return sessionService.listSessions(user, limit);
  }

  @GetMapping("/sessions/{sessionId}")
  public AgentSessionResponse session(
      @AuthenticationPrincipal AppUserDetails user, @PathVariable String sessionId) {
    return sessionService.getSession(sessionId, user);
  }

  @GetMapping("/sessions/{sessionId}/events")
  public List<AgentActionEvent> sessionEvents(
      @AuthenticationPrincipal AppUserDetails user, @PathVariable String sessionId) {
    return sessionService.listEvents(sessionId, user);
  }

  @PostMapping("/v2/sessions/{sessionId}/actions/plan")
  public ActionPlanResponse planAction(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestBody ActionPlanRequest request) {
    return sessionService.planAction(sessionId, request, user);
  }

  @PostMapping("/v2/sessions/{sessionId}/actions/{actionId}/events")
  public ActionRecordResponse actionEvent(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @PathVariable String actionId,
      @RequestBody ActionEventRequest request) {
    return sessionService.recordActionEvent(sessionId, actionId, request, user);
  }

  @PostMapping("/v2/sessions/{sessionId}/actions/{actionId}/result")
  public ActionRecordResponse actionResult(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @PathVariable String actionId,
      @RequestBody ActionResultRequest request) {
    return sessionService.recordActionResult(sessionId, actionId, request, user);
  }

  /**
   * #2b: send the ONE per-session usage summary email at session end. The CLI rolls up local-hands
   * task cost/tokens client-side and posts the totals here; we email the authenticated user once.
   */
  @PostMapping("/sessions/{sessionId}/summary-email")
  public org.springframework.http.ResponseEntity<Void> sessionSummaryEmail(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestBody SessionSummaryEmailRequest request) {
    com.navneet.executespec.auth.entity.AppUser appUser = user.getUser();
    if (appUser.getEmail() != null && !appUser.getEmail().isBlank()) {
      mailService.sendSessionSummaryEmail(
          appUser.getEmail(),
          appUser.getFirstName(),
          request.taskCount(),
          request.costUsd(),
          request.inputTokens(),
          request.outputTokens());
    }
    return org.springframework.http.ResponseEntity.accepted().build();
  }

  /** #2b request body: per-session usage totals rolled up by the CLI. */
  public record SessionSummaryEmailRequest(
      int taskCount, double costUsd, long inputTokens, long outputTokens) {}

  @PostMapping("/sessions/{sessionId}/turns")
  public TurnResponse turn(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestBody TurnRequest request) {
    return sessionService.recordTurn(sessionId, request, user);
  }

  @PostMapping(
      value = "/sessions/{sessionId}/turns/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter streamTurn(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestBody StreamTurnRequest request) {
    AgentSessionService.StreamTurnContext ctx =
        sessionService.beginStreamTurn(sessionId, request, user);
    SseEmitter emitter = new SseEmitter(0L);
    var snapshot = ContextSnapshotFactory.builder().build().captureAll();
    Thread.ofVirtual()
        .name("agent-turn-" + ctx.turnId())
        .start(snapshot.wrap(() -> runStreamTurn(emitter, ctx, user, request)));
    return emitter;
  }

  private void runStreamTurn(
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

      AccumulatingSink sink = new AccumulatingSink(emitter, sessionService, ctx);
      specChatService.runTurn(
          sink,
          user,
          ctx.sessionId(),
          ctx.projectId(),
          ctx.orgId(),
          conversationOrCurrentMessage(request),
          ctx.provider());
      emitter.complete();
    } catch (Exception e) {
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

  @PostMapping("/intent/interpret")
  public IntentDecision interpret(@RequestBody InterpretIntentRequest request) {
    return sessionService.interpret(request);
  }

  @PostMapping("/context/resolve")
  public ContextResolveResponse resolveContext(
      @AuthenticationPrincipal AppUserDetails user, @RequestBody ContextResolveRequest request) {
    return sessionService.resolveContext(request, user);
  }

  @GetMapping("/gates")
  public List<GateDecisionCard> gates(
      @AuthenticationPrincipal AppUserDetails user, @RequestParam(defaultValue = "20") int limit) {
    return sessionService.listGates(user, limit);
  }

  @GetMapping("/gates/{runId}")
  public GateDecisionCard gate(
      @AuthenticationPrincipal AppUserDetails user, @PathVariable String runId) {
    return sessionService.getGate(runId, user);
  }

  private static List<Map<String, Object>> conversationOrCurrentMessage(StreamTurnRequest request) {
    if (request != null && request.conversation() != null && !request.conversation().isEmpty()) {
      return request.conversation();
    }
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "user");
    message.put("content", request == null ? "" : request.message());
    return List.of(message);
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
