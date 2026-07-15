package com.navneet.executespec.agentsession.subagent;

import static org.assertj.core.api.Assertions.assertThat;
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

import org.junit.jupiter.api.Test;

class ImplementerMutationServiceTest {

  private final SubagentProtocolService protocol = mock(SubagentProtocolService.class);
  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final ToolRegistry tools = mock(ToolRegistry.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final AppUserDetails user =
      new AppUserDetails(
          AppUser.builder().id("user").email("u@example.com").status("ACTIVE").build(), null);

  @Test
  void flagOffDoesNotTouchSessionOrTools() throws Exception {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(false);
    var service =
        new ImplementerMutationService(protocol, sessions, tools, new ObjectMapper(), flags);
    Path root = Files.createTempDirectory("implementer");
    ToolResult result =
        service.mutate("parent", "reservation", root.toString(), "writeFile", null, user, "org");
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    verify(sessions, org.mockito.Mockito.never()).findById("parent");
  }

  @Test
  void webSessionIsRejectedBeforeMutation() throws Exception {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(true);
    when(sessions.findById("parent"))
        .thenReturn(
            Optional.of(
                AgentSession.builder()
                    .id("parent")
                    .userId("user")
                    .orgId("org")
                    .clientSurface("WEB")
                    .build()));
    var service =
        new ImplementerMutationService(protocol, sessions, tools, new ObjectMapper(), flags);
    Path root = Files.createTempDirectory("implementer");
    ToolResult result =
        service.mutate("parent", "reservation", root.toString(), "writeFile", null, user, "org");
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    verify(tools, org.mockito.Mockito.never()).find("writeFile");
  }

  @Test
  void onlyFileMutationToolsReachReservationAndTool() throws Exception {
    when(flags.isEnabled(ImplementerMutationService.FLAG)).thenReturn(true);
    when(sessions.findById("parent"))
        .thenReturn(
            Optional.of(
                AgentSession.builder()
                    .id("parent")
                    .userId("user")
                    .orgId("org")
                    .clientSurface("CLI")
                    .build()));
    ToolResult expected = ToolResult.ok("written");
    Tool tool = mock(Tool.class);
    when(tools.find("writeFile")).thenReturn(tool);
    when(tool.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(expected);
    var service =
        new ImplementerMutationService(protocol, sessions, tools, new ObjectMapper(), flags);
    Path root = Files.createTempDirectory("implementer");
    ToolResult result =
        service.mutate(
            "parent",
            "reservation",
            root.toString(),
            "writeFile",
            new ObjectMapper().createObjectNode(),
            user,
            "org");
    assertThat(result).isSameAs(expected);
    verify(protocol)
        .authorizeWriter(
            "parent", "reservation", root.toAbsolutePath().normalize().toString(), "user", "org");
  }
}
