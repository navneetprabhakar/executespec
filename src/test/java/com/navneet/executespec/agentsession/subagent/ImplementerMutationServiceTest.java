package com.navneet.executespec.agentsession.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.tool.Tool;
import com.navneet.executespec.tool.ToolRegistry;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImplementerMutationServiceTest {

  private final SubagentProtocolService protocol = mock(SubagentProtocolService.class);
  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final ToolRegistry tools = mock(ToolRegistry.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final AppUserDetails user =
      new AppUserDetails(
          AppUser.builder().id("user").email("u@example.com").status("ACTIVE").build(), null);

  private ImplementerWorkspaceScope workspaceScope;

  @BeforeEach
  void setUp() throws Exception {
    workspaceScope =
        new ImplementerWorkspaceScope(Files.createTempDirectory("implementer").toString());
  }

  @Test
  void flagOffDoesNotTouchSessionOrTools() {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(false);
    ToolResult result =
        service().mutate("parent", "reservation", null, "writeFile", null, user, "org");
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    verify(sessions, org.mockito.Mockito.never()).findById("parent");
  }

  @Test
  void webSessionIsRejectedBeforeMutation() {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(true);
    when(sessions.findById("parent")).thenReturn(Optional.of(session("WEB", "user")));
    ToolResult result =
        service().mutate("parent", "reservation", null, "writeFile", null, user, "org");
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    verify(tools, org.mockito.Mockito.never()).find("writeFile");
  }

  @Test
  void onlyFileMutationToolsReachReservationAndTool() {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(true);
    when(sessions.findById("parent")).thenReturn(Optional.of(session("CLI", "user")));
    ToolResult expected = ToolResult.ok("written");
    Tool tool = mock(Tool.class);
    when(tools.find("writeFile")).thenReturn(tool);
    when(tool.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(expected);

    ToolResult result =
        service()
            .mutate(
                "parent",
                "reservation",
                "src",
                "writeFile",
                new ObjectMapper().createObjectNode(),
                user,
                "org");

    assertThat(result).isSameAs(expected);
    Path expectedRoot = workspaceScope.resolve(session("CLI", "user"), "src");
    verify(protocol)
        .authorizeWriter("parent", "reservation", expectedRoot.toString(), "user", "org");
  }

  @Test
  void callerSuppliedAbsoluteServerPathIsRefused() throws Exception {
    Path elsewhere = Files.createTempDirectory("host-directory");
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(true);
    when(sessions.findById("parent")).thenReturn(Optional.of(session("CLI", "user")));

    ToolResult result =
        service()
            .mutate("parent", "reservation", elsewhere.toString(), "writeFile", null, user, "org");

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    verify(tools, org.mockito.Mockito.never()).find("writeFile");
    verify(protocol, org.mockito.Mockito.never())
        .authorizeWriter(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void reservationRequiresSessionOwnership() {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(true);
    when(sessions.findById("parent")).thenReturn(Optional.of(session("CLI", "someone-else")));

    assertThatThrownBy(() -> service().reserve("parent", "request", null, user, "org"))
        .isInstanceOf(SecurityException.class)
        .hasMessage("agent session ownership mismatch");
    verify(protocol, org.mockito.Mockito.never())
        .reserveWriter(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  private ImplementerMutationService service() {
    return new ImplementerMutationService(
        protocol, sessions, tools, new ObjectMapper(), flags, workspaceScope);
  }

  private AgentSession session(String surface, String userId) {
    return AgentSession.builder()
        .id("parent")
        .userId(userId)
        .orgId("org")
        .clientSurface(surface)
        .build();
  }
}
