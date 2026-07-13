package com.navneet.executespec.agentsession;

import static org.mockito.Mockito.mock;

import com.navneet.executespec.agentsession.AgentSessionController.SessionSummaryEmailRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AgentSessionControllerMailTest {

  @Test
  void sessionSummaryEmailEndpointReturnsGoneForRetiredRoute() {
    AgentSessionController controller =
        new AgentSessionController(
            mock(AgentSessionService.class),
            mock(AgentActionCatalog.class),
            mock(AgentStreamTurnRunner.class),
            mock(AgentAttachmentService.class),
            mock(SubagentExecutionService.class));

    var exception =
        org.assertj.core.api.Assertions.catchThrowable(
            () ->
                controller.sessionSummaryEmail(
                    user(), "session-1", new SessionSummaryEmailRequest(2, 0.42, 1000, 500)));

    org.assertj.core.api.Assertions.assertThat(exception)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(410);
    org.assertj.core.api.Assertions.assertThat(exception).hasMessageContaining("retired");
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setEmail("u1@example.com");
    return new AppUserDetails(user, null);
  }
}
