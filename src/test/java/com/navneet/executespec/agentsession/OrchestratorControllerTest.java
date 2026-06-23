package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;

class OrchestratorControllerTest {

  @Test
  void turnDelegatesToOrchestratorService() {
    OrchestratorService service = mock(OrchestratorService.class);
    OrchestratorController controller = new OrchestratorController(service);
    OrchestratorTurnRequest request =
        new OrchestratorTurnRequest(
            "s1",
            "client-turn-1",
            "Explain this repo",
            "cli",
            "0.4.2",
            "p1",
            null,
            null,
            null,
            null,
            null);
    OrchestratorTurnResponse expected =
        new OrchestratorTurnResponse(
            "client-turn-1",
            "READ_ONLY_CODE_HELP",
            0.9,
            "read_only_code_help",
            false,
            "I will inspect or explain this without creating a run.",
            null,
            new OrchestratorPolicy(false, false, "No run."),
            "orch_trace_1");
    AppUserDetails user = user();
    when(service.decideTurn(request, user)).thenReturn(expected);

    OrchestratorTurnResponse actual = controller.turn(user, request);

    assertThat(actual).isEqualTo(expected);
    verify(service).decideTurn(request, user);
  }

  @Test
  void confirmRunDelegatesToOrchestratorService() {
    OrchestratorService service = mock(OrchestratorService.class);
    OrchestratorController controller = new OrchestratorController(service);
    OrchestratorRunConfirmationRequest request =
        new OrchestratorRunConfirmationRequest("s1", "client-turn-1", true, null, null, null);
    OrchestratorRunConfirmationResponse expected =
        new OrchestratorRunConfirmationResponse(
            "s1", "client-turn-1", "run-1", "CODE_RUN_REQUIRED", true, "orch_trace_2", "SUBMITTED");
    AppUserDetails user = user();
    when(service.confirmRunSubmission(request, user)).thenReturn(expected);

    OrchestratorRunConfirmationResponse actual = controller.confirmRun(user, request);

    assertThat(actual).isEqualTo(expected);
    verify(service).confirmRunSubmission(request, user);
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setTier("PRO");
    user.setEmail("u1@example.com");
    return new AppUserDetails(user, null);
  }
}
