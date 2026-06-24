package com.navneet.executespec.agentsession;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionEventRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionRecordResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentActionDescriptor;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AttachmentResponse;
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

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.micrometer.context.ContextSnapshotFactory;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSessionController {

  private final AgentSessionService sessionService;
  private final AgentActionCatalog actionCatalog;
  private final AgentStreamTurnRunner streamTurnRunner;
  private final AgentAttachmentService attachmentService;

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

  @GetMapping("/sessions/{sessionId}/turns")
  public List<AgentTurnResponse> sessionTurns(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) String before) {
    return sessionService.listTurns(sessionId, before, limit, user);
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
   * Deprecated compatibility endpoint. Local CLI/VS Code sessions must not send email; newer
   * clients keep usage/cost visibility in the terminal. Keep the route as a no-op so older clients
   * can exit cleanly without triggering mail.
   */
  @PostMapping("/sessions/{sessionId}/summary-email")
  public ResponseEntity<Void> sessionSummaryEmail(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestBody SessionSummaryEmailRequest request) {
    return ResponseEntity.accepted().build();
  }

  /** Legacy request body retained for older clients that still POST this route. */
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
        .start(snapshot.wrap(() -> streamTurnRunner.run(emitter, ctx, user, request)));
    return emitter;
  }

  @PostMapping(
      value = "/sessions/{sessionId}/attachments",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public AttachmentResponse uploadAttachment(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestParam("file") MultipartFile file) {
    // Ownership guard — throws if the session isn't the caller's (same check as getSession).
    sessionService.getSession(sessionId, user);
    return attachmentService.validateAndStore(sessionId, file, user);
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
}
