package com.navneet.executespec.agentsession;

import java.util.List;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentActionDescriptor;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.AgentSessionResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ContextResolveResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.GateDecisionCard;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.IntentDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentSessionController {

  private final AgentSessionService sessionService;
  private final AgentActionCatalog actionCatalog;

  @GetMapping("/actions")
  public List<AgentActionDescriptor> actions() {
    return actionCatalog.list();
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

  @PostMapping("/sessions/{sessionId}/turns")
  public TurnResponse turn(
      @AuthenticationPrincipal AppUserDetails user,
      @PathVariable String sessionId,
      @RequestBody TurnRequest request) {
    return sessionService.recordTurn(sessionId, request, user);
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
