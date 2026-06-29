package com.navneet.executespec.agentsession;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Stable orchestration boundary for structured user turns.
 *
 * <p>The controller depends on this facade instead of the broader agent-session service so run
 * submission remains isolated behind {@link AgentRunSubmissionGate}. The underlying session service
 * still owns persistence and trace append operations.
 */
@Service
@RequiredArgsConstructor
public class OrchestratorService {

  private final AgentSessionService agentSessionService;

  public OrchestratorTurnResponse decideTurn(OrchestratorTurnRequest request, AppUserDetails user) {
    return agentSessionService.orchestrateTurn(request, user);
  }

  public OrchestratorRunConfirmationResponse confirmRunSubmission(
      OrchestratorRunConfirmationRequest request, AppUserDetails user, boolean patAuthenticated) {
    return agentSessionService.confirmRunSubmission(request, user, patAuthenticated);
  }
}
