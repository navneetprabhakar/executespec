package com.navneet.executespec.agentsession.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.cost.RunCostTracker;
import com.navneet.executespec.specchat.SpecChatService;
import com.navneet.executespec.tool.ToolRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImplementerExecutionServiceTest {

  private final FeatureFlagService flags = org.mockito.Mockito.mock(FeatureFlagService.class);
  private final AgentSessionRepository sessions =
      org.mockito.Mockito.mock(AgentSessionRepository.class);
  private final AgentTurnRepository turns = org.mockito.Mockito.mock(AgentTurnRepository.class);
  private final AgentActionEventRepository events =
      org.mockito.Mockito.mock(AgentActionEventRepository.class);
  private final ImplementerMutationService mutations =
      org.mockito.Mockito.mock(ImplementerMutationService.class);
  private final SubagentProtocolService protocol =
      org.mockito.Mockito.mock(SubagentProtocolService.class);
  private final ToolRegistry tools = org.mockito.Mockito.mock(ToolRegistry.class);
  private final SpecChatService chat = org.mockito.Mockito.mock(SpecChatService.class);
  private final AppUserDetails user =
      new AppUserDetails(
          AppUser.builder().id("u1").email("u@example.com").status("ACTIVE").build(), null);
  private Path root;

  @BeforeEach
  void setUp() throws Exception {
    root = Files.createTempDirectory("implementer-execution");
    when(flags.isEnabled(ImplementerExecutionService.FLAG)).thenReturn(true);
    when(turns.countBySessionId("session")).thenReturn(0L);
    when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  void featureFlagOffFailsBeforeSessionLookup() {
    when(flags.isEnabled(ImplementerExecutionService.FLAG)).thenReturn(false);
    ImplementerExecutionService service = service();
    assertThatThrownBy(
            () ->
                service.execute(
                    "session",
                    "reservation",
                    root.toString(),
                    "change it",
                    null,
                    null,
                    2,
                    null,
                    user,
                    "org"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("IMPLEMENTER is disabled");
    verify(sessions, never()).findById("session");
  }

  @Test
  void webSessionIsRejectedBeforeReservationAuthorization() {
    when(sessions.findById("session")).thenReturn(Optional.of(session("WEB")));
    ImplementerExecutionService service = service();
    assertThatThrownBy(
            () ->
                service.execute(
                    "session",
                    "reservation",
                    root.toString(),
                    "change it",
                    null,
                    null,
                    2,
                    null,
                    user,
                    "org"))
        .isInstanceOf(SecurityException.class)
        .hasMessage("IMPLEMENTER requires a CLI or VS Code session");
    verify(protocol, never()).authorizeWriter(any(), any(), any(), any(), any());
  }

  @Test
  void successfulModelPassPersistsCheckpointAndReleasesReservation() {
    when(sessions.findById("session")).thenReturn(Optional.of(session("CLI")));
    when(protocol.authorizeWriter(
            eq("session"), eq("reservation"), eq(root.toString()), eq("u1"), eq("org")))
        .thenReturn(activeReservation());
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              Runnable beforeRound = invocation.getArgument(13);
              beforeRound.run();
              sink.toolCall("writeFile", "{}", "write-1");
              sink.toolResult("write-1", "{\"ok\":true}");
              sink.toolCall("buildProject", "{}", "build-1");
              sink.toolResult("build-1", "{\"ok\":true}");
              sink.token("implemented and verified");
              sink.done(1, new RunCostTracker.Snapshot(3, 2, 0, 0, 0.01, 0));
              return null;
            })
        .when(chat)
        .runTurnBoundedWithTools(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any());

    ImplementerExecutionService.ExecutionResult result =
        service()
            .execute(
                "session",
                "reservation",
                root.toString(),
                "change it",
                "ANTHROPIC",
                null,
                2,
                null,
                user,
                "org");

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.summary()).contains("implemented");
    verify(protocol)
        .releaseWriter(
            eq("session"),
            eq("reservation"),
            eq("implementer execution terminal"),
            eq("u1"),
            eq("org"));
    ArgumentCaptor<com.navneet.executespec.agentsession.entity.AgentActionEvent> captured =
        ArgumentCaptor.forClass(com.navneet.executespec.agentsession.entity.AgentActionEvent.class);
    verify(events, org.mockito.Mockito.atLeast(2)).save(captured.capture());
    assertThat(captured.getAllValues()).extracting("type").contains("IMPLEMENTER_CHECKPOINT");
  }

  @Test
  void failureStillReleasesReservationAndPersistsFailureCheckpoint() {
    when(sessions.findById("session")).thenReturn(Optional.of(session("CLI")));
    when(protocol.authorizeWriter(
            eq("session"), eq("reservation"), eq(root.toString()), eq("u1"), eq("org")))
        .thenReturn(activeReservation());
    doAnswer(
            invocation -> {
              throw new RuntimeException("provider timeout");
            })
        .when(chat)
        .runTurnBoundedWithTools(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any());
    assertThatThrownBy(
            () ->
                service()
                    .execute(
                        "session",
                        "reservation",
                        root.toString(),
                        "change it",
                        null,
                        null,
                        2,
                        null,
                        user,
                        "org"))
        .hasMessage("provider timeout");
    verify(protocol)
        .releaseWriter(
            eq("session"),
            eq("reservation"),
            eq("implementer execution terminal"),
            eq("u1"),
            eq("org"));
  }

  @Test
  void modelToolBindingRoutesMutationThroughReservationService() {
    when(sessions.findById("session")).thenReturn(Optional.of(session("CLI")));
    when(protocol.authorizeWriter(
            eq("session"), eq("reservation"), eq(root.toString()), eq("u1"), eq("org")))
        .thenReturn(activeReservation());
    var expected = com.navneet.executespec.tool.ToolResult.ok("authorized");
    when(mutations.mutate(any(), any(), any(), any(), any(), any(), any())).thenReturn(expected);
    doAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              java.util.function.BiFunction<
                      String,
                      com.fasterxml.jackson.databind.JsonNode,
                      com.navneet.executespec.tool.ToolResult>
                  guard = invocation.getArgument(2);
              assertThat(guard.apply("writeFile", new ObjectMapper().createObjectNode()))
                  .isSameAs(expected);
              return List.of();
            })
        .when(tools)
        .bindForImplementer(any(), any(), any());
    doAnswer(
            invocation -> {
              SpecChatService.Sink sink = invocation.getArgument(0);
              sink.toolCall("writeFile", "{}", "write-1");
              sink.toolResult("write-1", "{\"ok\":true}");
              sink.toolCall("buildProject", "{}", "build-1");
              sink.toolResult("build-1", "{\"ok\":true}");
              sink.done(1, new RunCostTracker.Snapshot(0, 0, 0, 0, 0, 0));
              return null;
            })
        .when(chat)
        .runTurnBoundedWithTools(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyInt(), any(), any());

    service()
        .execute(
            "session",
            "reservation",
            root.toString(),
            "change it",
            null,
            null,
            1,
            null,
            user,
            "org");
    verify(mutations)
        .mutate(
            eq("session"),
            eq("reservation"),
            eq(root.toString()),
            eq("writeFile"),
            any(),
            eq(user),
            eq("org"));
  }

  private ImplementerExecutionService service() {
    return new ImplementerExecutionService(
        flags, sessions, turns, events, mutations, protocol, tools, chat, new ObjectMapper());
  }

  private AgentSession session(String surface) {
    return AgentSession.builder()
        .id("session")
        .orgId("org")
        .userId("u1")
        .clientSurface(surface)
        .status("ACTIVE")
        .defaultProvider("ANTHROPIC")
        .title("test")
        .build();
  }

  private SubagentProtocol.WriterReservation activeReservation() {
    return new SubagentProtocol.WriterReservation(
        "reservation",
        "session",
        "request",
        root.toString(),
        "WRITE_SINGLE_WRITER",
        "ACTIVE",
        Instant.now(),
        null,
        null);
  }
}
