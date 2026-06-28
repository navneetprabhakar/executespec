package com.navneet.executespec.agentsession;

import jakarta.servlet.http.HttpServletRequest;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.JwtAuthenticationFilter;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Structured turn-orchestration API. It decides route/policy only; it never submits runs. */
@RestController
@RequestMapping("/api/orchestrator")
@RequiredArgsConstructor
public class OrchestratorController {

  private final OrchestratorService orchestratorService;

  @PostMapping("/turn")
  public OrchestratorTurnResponse turn(
      @AuthenticationPrincipal AppUserDetails user, @RequestBody OrchestratorTurnRequest request) {
    return orchestratorService.decideTurn(request, user);
  }

  @PostMapping("/turn/confirm")
  public OrchestratorRunConfirmationResponse confirmRun(
      @AuthenticationPrincipal AppUserDetails user,
      @RequestBody OrchestratorRunConfirmationRequest request,
      HttpServletRequest httpRequest) {
    return orchestratorService.confirmRunSubmission(request, user, isPatAuthenticated(httpRequest));
  }

  private static boolean isPatAuthenticated(HttpServletRequest request) {
    return JwtAuthenticationFilter.AUTH_SOURCE_PAT.equals(
        request.getAttribute(JwtAuthenticationFilter.AUTH_SOURCE_ATTRIBUTE));
  }
}
