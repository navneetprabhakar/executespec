package com.navneet.executespec.agentsession;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.navneet.executespec.agentsession.AgentSessionController.SessionSummaryEmailRequest;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.MailService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AgentSessionControllerMailTest {

  @Test
  void sessionSummaryEmailEndpointIsNoopForLocalSessions() {
    MailService mailService = mock(MailService.class);
    AgentSessionController controller =
        new AgentSessionController(
            mock(AgentSessionService.class),
            mock(AgentActionCatalog.class),
            mock(AgentStreamTurnRunner.class),
            mock(AgentAttachmentService.class));

    var response =
        controller.sessionSummaryEmail(
            user(), "session-1", new SessionSummaryEmailRequest(2, 0.42, 1000, 500));

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode())
        .isEqualTo(HttpStatus.ACCEPTED);
    verify(mailService, never())
        .sendSessionSummaryEmail(
            anyString(), anyString(), anyInt(), anyDouble(), anyLong(), anyLong());
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
