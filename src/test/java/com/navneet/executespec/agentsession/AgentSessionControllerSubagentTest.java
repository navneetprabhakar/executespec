package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.agentsession.subagent.SubagentProtocol;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;

import org.junit.jupiter.api.Test;

class AgentSessionControllerSubagentTest {

  private final AgentSessionService sessions = mock(AgentSessionService.class);
  private final AgentActionCatalog catalog = mock(AgentActionCatalog.class);
  private final AgentStreamTurnRunner stream = mock(AgentStreamTurnRunner.class);
  private final AgentAttachmentService attachments = mock(AgentAttachmentService.class);
  private final SubagentExecutionService execution = mock(SubagentExecutionService.class);
  private final AgentSessionController controller =
      new AgentSessionController(sessions, catalog, stream, attachments, execution);
  private final AppUserDetails user =
      new AppUserDetails(
          AppUser.builder()
              .id("user-1")
              .email("u@example.com")
              .globalRole("USER")
              .status("ACTIVE")
              .orgId("org-1")
              .build(),
          null);

  @Test
  void executeSubagent_requiresPathAndPayloadParentToMatch() {
    var request =
        new SubagentProtocol.SpawnRequest(
            "req-1", "other-parent", "turn-1", SubagentProtocol.Role.SCOUT, "locate code", 2, 0.1);

    assertThatThrownBy(() -> controller.executeSubagent(user, "parent-1", request))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("parent session id");
  }

  @Test
  void executeSubagent_usesAuthenticatedOrgAndReturnsPersistedResult() {
    var request =
        new SubagentProtocol.SpawnRequest(
            "req-1", "parent-1", "turn-1", SubagentProtocol.Role.SCOUT, "locate code", 2, 0.1);
    var handle =
        new SubagentProtocol.Handle(
            "child-1", "parent-1", "turn-1", SubagentProtocol.Role.SCOUT, "locate code", 1, 2, 0.1, "COMPLETED");
    var expected = new SubagentProtocol.Result(handle, "found it", List.of(), 1, 0.01, Instant.now());
    when(execution.execute(request, user, "org-1")).thenReturn(expected);

    assertThat(controller.executeSubagent(user, "parent-1", request)).isSameAs(expected);
    verify(execution).execute(request, user, "org-1");
  }

  @Test
  void cancelSubagents_returnsCountAndDelegatesParentScope() {
    when(execution.cancelChildren("parent-1", "stop", user, "org-1")).thenReturn(2);

    assertThat(controller.cancelSubagents(user, "parent-1", new AgentSessionController.CancelSubagentsRequest("stop")))
        .containsEntry("cancelled", 2);
    verify(execution).cancelChildren("parent-1", "stop", user, "org-1");
  }
}
